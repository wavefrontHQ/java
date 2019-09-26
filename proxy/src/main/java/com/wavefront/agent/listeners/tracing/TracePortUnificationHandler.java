package com.wavefront.agent.listeners.tracing;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wavefront.agent.auth.TokenAuthenticator;
import com.wavefront.agent.channel.HealthCheckManager;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.agent.listeners.AbstractLineDelimitedHandler;
import com.wavefront.agent.preprocessor.ReportableEntityPreprocessor;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.ingester.ReportableEntityDecoder;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import wavefront.report.Span;
import wavefront.report.SpanLogs;

import static com.wavefront.agent.channel.ChannelUtils.formatErrorMessage;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.ERROR_SPAN_TAG_KEY;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.ERROR_SPAN_TAG_VAL;

/**
 * Process incoming trace-formatted data.
 *
 * Accepts incoming messages of either String or FullHttpRequest type: single Span in a string,
 * or multiple points in the HTTP post body, newline-delimited.
 *
 * @author vasily@wavefront.com
 */
@ChannelHandler.Sharable
public class TracePortUnificationHandler extends AbstractLineDelimitedHandler {
  private static final Logger logger = Logger.getLogger(
      TracePortUnificationHandler.class.getCanonicalName());

  private static final ObjectMapper JSON_PARSER = new ObjectMapper();

  private final ReportableEntityHandler<Span> handler;
  private final ReportableEntityHandler<SpanLogs> spanLogsHandler;
  private final ReportableEntityDecoder<String, Span> decoder;
  private final ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder;
  private final Supplier<ReportableEntityPreprocessor> preprocessorSupplier;
  private final Sampler sampler;
  private final boolean alwaysSampleErrors;
  private final Supplier<Boolean> traceDisabled;
  private final Supplier<Boolean> spanLogsDisabled;
  private final RateLimiter warningLoggerRateLimiter = RateLimiter.create(0.2);

  private final Counter discardedSpans;
  private final Counter discardedSpansBySampler;

  @SuppressWarnings("unchecked")
  public TracePortUnificationHandler(
      final String handle, final TokenAuthenticator tokenAuthenticator,
      final HealthCheckManager healthCheckManager,
      final ReportableEntityDecoder<String, Span> traceDecoder,
      final ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder,
      @Nullable final Supplier<ReportableEntityPreprocessor> preprocessor,
      final ReportableEntityHandlerFactory handlerFactory, final Sampler sampler,
      final boolean alwaysSampleErrors, final Supplier<Boolean> traceDisabled,
      final Supplier<Boolean> spanLogsDisabled) {
    this(handle, tokenAuthenticator, healthCheckManager, traceDecoder, spanLogsDecoder,
        preprocessor, handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE, handle)),
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE_SPAN_LOGS, handle)),
        sampler, alwaysSampleErrors, traceDisabled, spanLogsDisabled);
  }

  @VisibleForTesting
  public TracePortUnificationHandler(
      final String handle, final TokenAuthenticator tokenAuthenticator,
      final HealthCheckManager healthCheckManager,
      final ReportableEntityDecoder<String, Span> traceDecoder,
      final ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder,
      @Nullable final Supplier<ReportableEntityPreprocessor> preprocessor,
      final ReportableEntityHandler<Span> handler,
      final ReportableEntityHandler<SpanLogs> spanLogsHandler, final Sampler sampler,
      final boolean alwaysSampleErrors, final Supplier<Boolean> traceDisabled,
      final Supplier<Boolean> spanLogsDisabled) {
    super(tokenAuthenticator, healthCheckManager, handle);
    this.decoder = traceDecoder;
    this.spanLogsDecoder = spanLogsDecoder;
    this.handler = handler;
    this.spanLogsHandler = spanLogsHandler;
    this.preprocessorSupplier = preprocessor;
    this.sampler = sampler;
    this.alwaysSampleErrors = alwaysSampleErrors;
    this.traceDisabled = traceDisabled;
    this.spanLogsDisabled = spanLogsDisabled;
    this.discardedSpans = Metrics.newCounter(new MetricName("spans." + handle, "", "discarded"));
    this.discardedSpansBySampler = Metrics.newCounter(new MetricName("spans." + handle, "",
        "sampler.discarded"));
  }

  @Override
  protected void processLine(final ChannelHandlerContext ctx, @Nonnull String message) {
    if (traceDisabled.get()) {
      if (warningLoggerRateLimiter.tryAcquire()) {
        logger.warning("Ingested spans discarded because tracing feature is not enabled on the " +
            "server");
      }
      discardedSpans.inc();
      return;
    }
    if (message.startsWith("{") && message.endsWith("}")) { // span logs
      if (spanLogsDisabled.get()) {
        if (warningLoggerRateLimiter.tryAcquire()) {
          logger.warning("Ingested span logs discarded because the feature is not enabled on the " +
              "server");
        }
        return;
      }
      try {
        List<SpanLogs> output = Lists.newArrayListWithCapacity(1);
        spanLogsDecoder.decode(JSON_PARSER.readTree(message), output, "dummy");
        for (SpanLogs object : output) {
          spanLogsHandler.report(object);
        }
      } catch (Exception e) {
        spanLogsHandler.reject(message, formatErrorMessage(message, e, ctx));
      }
      return;
    }

    ReportableEntityPreprocessor preprocessor = preprocessorSupplier == null ?
        null : preprocessorSupplier.get();
    String[] messageHolder = new String[1];

    // transform the line if needed
    if (preprocessor != null) {
      message = preprocessor.forPointLine().transform(message);

      if (!preprocessor.forPointLine().filter(message, messageHolder)) {
        if (messageHolder[0] != null) {
          handler.reject((Span) null, messageHolder[0]);
        } else {
          handler.block(null, message);
        }
        return;
      }
    }

    List<Span> output = Lists.newArrayListWithCapacity(1);
    try {
      decoder.decode(message, output, "dummy");
    } catch (Exception e) {
      handler.reject(message, formatErrorMessage(message, e, ctx));
      return;
    }

    for (Span object : output) {
      if (preprocessor != null) {
        preprocessor.forSpan().transform(object);
        if (!preprocessor.forSpan().filter(object, messageHolder)) {
          if (messageHolder[0] != null) {
            handler.reject(object, messageHolder[0]);
          } else {
            handler.block(object);
          }
          return;
        }
      }
      // check whether error span tag exists.
      boolean sampleError = alwaysSampleErrors && object.getAnnotations().stream().anyMatch(
          t -> t.getKey().equals(ERROR_SPAN_TAG_KEY) && t.getValue().equals(ERROR_SPAN_TAG_VAL));
      if (sampleError || sample(object)) {
        handler.report(object);
      }
    }
  }

  private boolean sample(Span object) {
    if (sampler.sample(object.getName(),
        UUID.fromString(object.getTraceId()).getLeastSignificantBits(), object.getDuration())) {
      return true;
    }
    discardedSpansBySampler.inc();
    return false;
  }
}
