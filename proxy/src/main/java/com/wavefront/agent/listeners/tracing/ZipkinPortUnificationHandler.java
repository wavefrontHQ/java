package com.wavefront.agent.listeners.tracing;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;

import com.wavefront.agent.Utils;
import com.wavefront.agent.auth.TokenAuthenticatorBuilder;
import com.wavefront.agent.channel.ChannelUtils;
import com.wavefront.agent.channel.HealthCheckManager;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.agent.listeners.AbstractHttpOnlyHandler;
import com.wavefront.agent.preprocessor.ReportableEntityPreprocessor;
import com.wavefront.common.NamedThreadFactory;
import com.wavefront.common.TraceConstants;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;

import org.apache.commons.lang.StringUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import wavefront.report.Annotation;
import wavefront.report.Span;
import wavefront.report.SpanLog;
import wavefront.report.SpanLogs;
import zipkin2.SpanBytesDecoderDetector;
import zipkin2.codec.BytesDecoder;

import static com.wavefront.agent.channel.ChannelUtils.writeExceptionText;
import static com.wavefront.agent.channel.ChannelUtils.writeHttpResponse;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.DEBUG_SPAN_TAG_KEY;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.ERROR_SPAN_TAG_KEY;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.ERROR_SPAN_TAG_VAL;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.reportHeartbeats;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.reportWavefrontGeneratedData;
import static com.wavefront.sdk.common.Constants.APPLICATION_TAG_KEY;
import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.COMPONENT_TAG_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SOURCE_KEY;

/**
 * Handler that processes Zipkin trace data over HTTP and converts them to Wavefront format.
 *
 * @author Anil Kodali (akodali@vmware.com)
 */
@ChannelHandler.Sharable
public class ZipkinPortUnificationHandler extends AbstractHttpOnlyHandler
    implements Runnable, Closeable {
  private static final Logger logger = Logger.getLogger(
      ZipkinPortUnificationHandler.class.getCanonicalName());
  private final String handle;
  private final ReportableEntityHandler<Span> spanHandler;
  private final ReportableEntityHandler<SpanLogs> spanLogsHandler;
  @Nullable
  private final WavefrontSender wfSender;
  @Nullable
  private final WavefrontInternalReporter wfInternalReporter;
  private final Supplier<Boolean> traceDisabled;
  private final Supplier<Boolean> spanLogsDisabled;
  private final Supplier<ReportableEntityPreprocessor> preprocessorSupplier;
  private final Sampler sampler;
  private final boolean alwaysSampleErrors;
  private final RateLimiter warningLoggerRateLimiter = RateLimiter.create(0.2);
  private final Counter discardedBatches;
  private final Counter processedBatches;
  private final Counter failedBatches;
  private final Counter discardedSpansBySampler;
  private final ConcurrentMap<HeartbeatMetricKey, Boolean> discoveredHeartbeatMetrics;
  private final ScheduledExecutorService scheduledExecutorService;

  private final static Set<String> ZIPKIN_VALID_PATHS = ImmutableSet.of("/api/v1/spans/", "/api/v2/spans/");
  private final static String ZIPKIN_VALID_HTTP_METHOD = "POST";
  private final static String ZIPKIN_COMPONENT = "zipkin";
  private final static String DEFAULT_SOURCE = "zipkin";
  private final static String DEFAULT_SERVICE = "defaultService";
  private final static String DEFAULT_SPAN_NAME = "defaultOperation";
  private final static String SPAN_TAG_ERROR = "error";
  private final String proxyLevelApplicationName;
  private final Set<String> traceDerivedCustomTagKeys;

  private static final Logger ZIPKIN_DATA_LOGGER = Logger.getLogger("ZipkinDataLogger");

  @SuppressWarnings("unchecked")
  public ZipkinPortUnificationHandler(String handle,
                                      final HealthCheckManager healthCheckManager,
                                      ReportableEntityHandlerFactory handlerFactory,
                                      @Nullable WavefrontSender wfSender,
                                      Supplier<Boolean> traceDisabled,
                                      Supplier<Boolean> spanLogsDisabled,
                                      @Nullable Supplier<ReportableEntityPreprocessor> preprocessor,
                                      Sampler sampler,
                                      boolean alwaysSampleErrors,
                                      @Nullable String traceZipkinApplicationName,
                                      Set<String> traceDerivedCustomTagKeys) {
    this(handle, healthCheckManager,
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE, handle)),
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE_SPAN_LOGS, handle)),
        wfSender, traceDisabled, spanLogsDisabled, preprocessor, sampler, alwaysSampleErrors,
        traceZipkinApplicationName, traceDerivedCustomTagKeys);
  }

  @VisibleForTesting
  ZipkinPortUnificationHandler(final String handle,
                               final HealthCheckManager healthCheckManager,
                               ReportableEntityHandler<Span> spanHandler,
                               ReportableEntityHandler<SpanLogs> spanLogsHandler,
                               @Nullable WavefrontSender wfSender,
                               Supplier<Boolean> traceDisabled,
                               Supplier<Boolean> spanLogsDisabled,
                               @Nullable Supplier<ReportableEntityPreprocessor> preprocessor,
                               Sampler sampler,
                               boolean alwaysSampleErrors,
                               @Nullable String traceZipkinApplicationName,
                               Set<String> traceDerivedCustomTagKeys) {
    super(TokenAuthenticatorBuilder.create().build(), healthCheckManager, handle);
    this.handle = handle;
    this.spanHandler = spanHandler;
    this.spanLogsHandler = spanLogsHandler;
    this.wfSender = wfSender;
    this.traceDisabled = traceDisabled;
    this.spanLogsDisabled = spanLogsDisabled;
    this.preprocessorSupplier = preprocessor;
    this.sampler = sampler;
    this.alwaysSampleErrors = alwaysSampleErrors;
    this.proxyLevelApplicationName = StringUtils.isBlank(traceZipkinApplicationName) ?
        "Zipkin" : traceZipkinApplicationName.trim();
    this.traceDerivedCustomTagKeys = traceDerivedCustomTagKeys;
    this.discardedBatches = Metrics.newCounter(new MetricName(
        "spans." + handle + ".batches", "", "discarded"));
    this.processedBatches = Metrics.newCounter(new MetricName(
        "spans." + handle + ".batches", "", "processed"));
    this.failedBatches = Metrics.newCounter(new MetricName(
        "spans." + handle + ".batches", "", "failed"));
    this.discardedSpansBySampler = Metrics.newCounter(new MetricName(
        "spans." + handle, "", "sampler.discarded"));
    this.discoveredHeartbeatMetrics = new ConcurrentHashMap<>();
    this.scheduledExecutorService = Executors.newScheduledThreadPool(1,
        new NamedThreadFactory("zipkin-heart-beater"));
    scheduledExecutorService.scheduleAtFixedRate(this, 1, 1, TimeUnit.MINUTES);

    if (wfSender != null) {
      wfInternalReporter = new WavefrontInternalReporter.Builder().
          prefixedWith("tracing.derived").withSource(DEFAULT_SOURCE).reportMinuteDistribution().
          build(wfSender);
      // Start the reporter
      wfInternalReporter.start(1, TimeUnit.MINUTES);
    } else {
      wfInternalReporter = null;
    }
  }

  @Override
  protected void handleHttpMessage(final ChannelHandlerContext ctx,
                                   final FullHttpRequest incomingRequest) {
    URI uri = ChannelUtils.parseUri(ctx, incomingRequest);
    if (uri == null) return;

    String path = uri.getPath().endsWith("/") ? uri.getPath() : uri.getPath() + "/";

    // Validate Uri Path and HTTP method of incoming Zipkin spans.
    if (!ZIPKIN_VALID_PATHS.contains(path)) {
      writeHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Unsupported URL path.", incomingRequest);
      logWarning("WF-400: Requested URI path '" + path + "' is not supported.", null, ctx);
      return;
    }
    if (!incomingRequest.method().toString().equalsIgnoreCase(ZIPKIN_VALID_HTTP_METHOD)) {
      writeHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Unsupported Http method.", incomingRequest);
      logWarning("WF-400: Requested http method '" + incomingRequest.method().toString() +
          "' is not supported.", null, ctx);
      return;
    }

    HttpResponseStatus status;
    StringBuilder output = new StringBuilder();

    // Handle case when tracing is disabled, ignore reported spans.
    if (traceDisabled.get()) {
      if (warningLoggerRateLimiter.tryAcquire()) {
        logger.info("Ingested spans discarded because tracing feature is not enabled on the " +
            "server");
      }
      discardedBatches.inc();
      output.append("Ingested spans discarded because tracing feature is not enabled on the " +
          "server.");
      status = HttpResponseStatus.ACCEPTED;
      writeHttpResponse(ctx, status, output, incomingRequest);
      return;
    }

    try {
      byte[] bytesArray = new byte[incomingRequest.content().nioBuffer().remaining()];
      incomingRequest.content().nioBuffer().get(bytesArray, 0, bytesArray.length);
      BytesDecoder<zipkin2.Span> decoder = SpanBytesDecoderDetector.decoderForListMessage(bytesArray);
      List<zipkin2.Span> zipkinSpanSink = new ArrayList<>();
      decoder.decodeList(bytesArray, zipkinSpanSink);
      processZipkinSpans(zipkinSpanSink);
      status = HttpResponseStatus.ACCEPTED;
      processedBatches.inc();
    } catch (Exception e) {
      failedBatches.inc();
      writeExceptionText(e, output);
      status = HttpResponseStatus.BAD_REQUEST;
      logger.log(Level.WARNING, "Zipkin batch processing failed", Throwables.getRootCause(e));
    }
    writeHttpResponse(ctx, status, output, incomingRequest);
  }

  private void processZipkinSpans(List<zipkin2.Span> zipkinSpans) {
    for (zipkin2.Span zipkinSpan : zipkinSpans) {
      processZipkinSpan(zipkinSpan);
    }
  }

  private void processZipkinSpan(zipkin2.Span zipkinSpan) {
    if (ZIPKIN_DATA_LOGGER.isLoggable(Level.FINEST)) {
      ZIPKIN_DATA_LOGGER.info("Inbound Zipkin span: " + zipkinSpan.toString());
    }
    // Add application tags, span references, span kind and http uri, responses etc.
    List<Annotation> annotations = new ArrayList<>();

    // Set Span's References.
    if (zipkinSpan.parentId() != null) {
      annotations.add(new Annotation(TraceConstants.PARENT_KEY,
          Utils.convertToUuidString(zipkinSpan.parentId())));
    }

    // Set Span Kind.
    if (zipkinSpan.kind() != null) {
      String kind = zipkinSpan.kind().toString().toLowerCase();
      annotations.add(new Annotation("span.kind", kind));
      if (zipkinSpan.annotations() != null && !zipkinSpan.annotations().isEmpty()) {
        annotations.add(new Annotation("_spanSecondaryId", kind));
      }
    }

    // Set Span's service name.
    String serviceName = zipkinSpan.localServiceName() == null ? DEFAULT_SERVICE :
        zipkinSpan.localServiceName();
    annotations.add(new Annotation(SERVICE_TAG_KEY, serviceName));

    String applicationName = this.proxyLevelApplicationName;
    String cluster = NULL_TAG_VAL;
    String shard = NULL_TAG_VAL;
    String componentTagValue = NULL_TAG_VAL;
    boolean isError = false;
    boolean isDebugSpanTag = false;

    // Set all other Span Tags.
    Set<String> ignoreKeys = new HashSet<>(ImmutableSet.of(SOURCE_KEY));
    if (zipkinSpan.tags() != null && zipkinSpan.tags().size() > 0) {
      for (Map.Entry<String, String> tag : zipkinSpan.tags().entrySet()) {
        if (!ignoreKeys.contains(tag.getKey().toLowerCase()) && !StringUtils.isBlank(tag.getValue())) {
          Annotation annotation = new Annotation(tag.getKey(), tag.getValue());
          switch (annotation.getKey()) {
            case APPLICATION_TAG_KEY:
              applicationName = annotation.getValue();
              continue;
            case CLUSTER_TAG_KEY:
              cluster = annotation.getValue();
              continue;
            case SHARD_TAG_KEY:
              shard = annotation.getValue();
              continue;
            case COMPONENT_TAG_KEY:
              componentTagValue = annotation.getValue();
              break;
            case ERROR_SPAN_TAG_KEY:
              isError = true;
              // Ignore the original error value
              annotation.setValue(ERROR_SPAN_TAG_VAL);
              break;
            // TODO : Import DEBUG_SPAN_TAG_KEY from wavefront-sdk-java constants.
            case DEBUG_SPAN_TAG_KEY:
              isDebugSpanTag = true;
              break;
          }
          annotations.add(annotation);
        }
      }
    }

    // Add all wavefront indexed tags. These are set based on below hierarchy.
    // Span Level > Proxy Level > Default
    annotations.add(new Annotation(APPLICATION_TAG_KEY, applicationName));
    annotations.add(new Annotation(CLUSTER_TAG_KEY, cluster));
    annotations.add(new Annotation(SHARD_TAG_KEY, shard));

    // Add additional annotations.
    if (zipkinSpan.localEndpoint() != null && zipkinSpan.localEndpoint().ipv4() != null) {
      annotations.add(new Annotation("ipv4", zipkinSpan.localEndpoint().ipv4()));
    }

    /** Add source of the span following the below:
     *    1. If "source" is provided by span tags , use it else
     *    2. Default "source" to "zipkin".
     */
    String sourceName = DEFAULT_SOURCE;
    if (zipkinSpan.tags() != null && zipkinSpan.tags().size() > 0) {
      if (zipkinSpan.tags().get(SOURCE_KEY) != null) {
        sourceName = zipkinSpan.tags().get(SOURCE_KEY);
      }
    }
    // Set spanName.
    String spanName = zipkinSpan.name() == null ? DEFAULT_SPAN_NAME : zipkinSpan.name();

    String spanId = Utils.convertToUuidString(zipkinSpan.id());
    String traceId = Utils.convertToUuidString(zipkinSpan.traceId());
    //Build wavefront span
    Span wavefrontSpan = Span.newBuilder().
        setCustomer("dummy").
        setName(spanName).
        setSource(sourceName).
        setSpanId(spanId).
        setTraceId(traceId).
        setStartMillis(zipkinSpan.timestampAsLong() / 1000).
        setDuration(zipkinSpan.durationAsLong() / 1000).
        setAnnotations(annotations).
        build();

    if (zipkinSpan.tags().containsKey(SPAN_TAG_ERROR)) {
      if (ZIPKIN_DATA_LOGGER.isLoggable(Level.FINER)) {
        ZIPKIN_DATA_LOGGER.info("Span id :: " + spanId + " with trace id :: " + traceId +
            " , includes error tag :: " + zipkinSpan.tags().get(SPAN_TAG_ERROR));
      }
    }
    // Log Zipkin spans as well as Wavefront spans for debugging purposes.
    if (ZIPKIN_DATA_LOGGER.isLoggable(Level.FINEST)) {
      ZIPKIN_DATA_LOGGER.info("Converted Wavefront span: " + wavefrontSpan.toString());
    }

    if (preprocessorSupplier != null) {
      ReportableEntityPreprocessor preprocessor = preprocessorSupplier.get();
      String[] messageHolder = new String[1];
      preprocessor.forSpan().transform(wavefrontSpan);
      if (!preprocessor.forSpan().filter(wavefrontSpan, messageHolder)) {
        if (messageHolder[0] != null) {
          spanHandler.reject(wavefrontSpan, messageHolder[0]);
        } else {
          spanHandler.block(wavefrontSpan);
        }
        return;
      }
    }

    boolean isDebug = zipkinSpan.debug() != null ? zipkinSpan.debug() : false;
    if (isDebugSpanTag || isDebug || (alwaysSampleErrors && isError) || sample(wavefrontSpan)) {
      spanHandler.report(wavefrontSpan);

      if (zipkinSpan.annotations() != null && !zipkinSpan.annotations().isEmpty()) {
        if (spanLogsDisabled.get()) {
          if (warningLoggerRateLimiter.tryAcquire()) {
            logger.info("Span logs discarded because the feature is not " +
                "enabled on the server!");
          }
        } else {
          SpanLogs spanLogs = SpanLogs.newBuilder().
              setCustomer("default").
              setTraceId(wavefrontSpan.getTraceId()).
              setSpanId(wavefrontSpan.getSpanId()).
              setSpanSecondaryId(zipkinSpan.kind() != null ?
                  zipkinSpan.kind().toString().toLowerCase() : null).
              setLogs(zipkinSpan.annotations().stream().map(
                  x -> SpanLog.newBuilder().
                      setTimestamp(x.timestamp()).
                      setFields(ImmutableMap.of("annotation", x.value())).
                      build()).
                  collect(Collectors.toList())).
              build();
          spanLogsHandler.report(spanLogs);
        }
      }
    }
    // report stats irrespective of span sampling.
    if (wfInternalReporter != null) {
      // report converted metrics/histograms from the span
      discoveredHeartbeatMetrics.putIfAbsent(reportWavefrontGeneratedData(wfInternalReporter,
          spanName, applicationName, serviceName, cluster, shard, sourceName, componentTagValue,
          isError, zipkinSpan.durationAsLong(), traceDerivedCustomTagKeys, annotations), true);
    }
  }

  private boolean sample(Span wavefrontSpan) {
    if (sampler.sample(wavefrontSpan.getName(),
        UUID.fromString(wavefrontSpan.getTraceId()).getLeastSignificantBits(),
        wavefrontSpan.getDuration())) {
      return true;
    }
    discardedSpansBySampler.inc();
    return false;
  }

  @Override
  public void run() {
    try {
      reportHeartbeats(ZIPKIN_COMPONENT, wfSender, discoveredHeartbeatMetrics);
    } catch (IOException e) {
      logger.log(Level.WARNING, "Cannot report heartbeat metric to wavefront");
    }
  }

  @Override
  public void close() throws IOException {
    scheduledExecutorService.shutdownNow();
  }
}
