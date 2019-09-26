package com.wavefront.agent.listeners;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.wavefront.agent.auth.TokenAuthenticator;
import com.wavefront.agent.channel.ChannelUtils;
import com.wavefront.agent.channel.HealthCheckManager;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.agent.preprocessor.ReportableEntityPreprocessor;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.ingester.GraphiteDecoder;
import com.wavefront.ingester.ReportPointSerializer;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import wavefront.report.ReportPoint;

import static com.wavefront.agent.channel.ChannelUtils.writeExceptionText;
import static com.wavefront.agent.channel.ChannelUtils.writeHttpResponse;

/**
 * This class handles incoming messages in write_http format.
 *
 * @author Clement Pang (clement@wavefront.com).
 * @author vasily@wavefront.com
 */
@ChannelHandler.Sharable
public class WriteHttpJsonPortUnificationHandler extends AbstractHttpOnlyHandler {
  private static final Logger logger = Logger.getLogger(
      WriteHttpJsonPortUnificationHandler.class.getCanonicalName());
  private static final Logger blockedPointsLogger = Logger.getLogger("RawBlockedPoints");

  /**
   * The point handler that takes report metrics one data point at a time and handles batching and retries, etc
   */
  private final ReportableEntityHandler<ReportPoint> pointHandler;
  private final String defaultHost;

  @Nullable
  private final Supplier<ReportableEntityPreprocessor> preprocessorSupplier;
  private final ObjectMapper jsonParser;
  /**
   *  Graphite decoder to re-parse modified points.
   */
  private final GraphiteDecoder recoder = new GraphiteDecoder(Collections.emptyList());

  /**
   * Create a new instance.
   *
   * @param handle             handle/port number.
   * @param healthCheckManager shared health check endpoint handler.
   * @param handlerFactory     factory for ReportableEntityHandler objects.
   * @param defaultHost        default host name to use, if none specified.
   * @param preprocessor       preprocessor.
   */
  @SuppressWarnings("unchecked")
  public WriteHttpJsonPortUnificationHandler(
      final String handle, final TokenAuthenticator authenticator,
      final HealthCheckManager healthCheckManager,
      final ReportableEntityHandlerFactory handlerFactory, final String defaultHost,
      @Nullable final Supplier<ReportableEntityPreprocessor> preprocessor) {
    this(handle, authenticator, healthCheckManager, handlerFactory.getHandler(
        HandlerKey.of(ReportableEntityType.POINT, handle)), defaultHost, preprocessor);
  }

  @VisibleForTesting
  protected WriteHttpJsonPortUnificationHandler(
      final String handle, final TokenAuthenticator authenticator,
      final HealthCheckManager healthCheckManager,
      final ReportableEntityHandler<ReportPoint> pointHandler, final String defaultHost,
      @Nullable final Supplier<ReportableEntityPreprocessor> preprocessor) {
    super(authenticator, healthCheckManager, handle);
    this.pointHandler = pointHandler;
    this.defaultHost = defaultHost;
    this.preprocessorSupplier = preprocessor;
    this.jsonParser = new ObjectMapper();
  }

  @Override
  protected void handleHttpMessage(final ChannelHandlerContext ctx,
                                   final FullHttpRequest incomingRequest) {
    StringBuilder output = new StringBuilder();
    URI uri = ChannelUtils.parseUri(ctx, incomingRequest);
    if (uri == null) return;

    HttpResponseStatus status = HttpResponseStatus.OK;
    String requestBody = incomingRequest.content().toString(CharsetUtil.UTF_8);
    try {
      JsonNode metrics = jsonParser.readTree(requestBody);
      if (!metrics.isArray()) {
        logger.warning("metrics is not an array!");
        pointHandler.reject((ReportPoint) null, "[metrics] is not an array!");
        status = HttpResponseStatus.BAD_REQUEST;
        writeHttpResponse(ctx, status, output, incomingRequest);
        return;
      }
      reportMetrics(metrics);
      writeHttpResponse(ctx, status, output, incomingRequest);
    } catch (Exception e) {
      status = HttpResponseStatus.BAD_REQUEST;
      writeExceptionText(e, output);
      logWarning("WF-300: Failed to handle incoming write_http request", e, ctx);
      writeHttpResponse(ctx, status, output, incomingRequest);
    }
  }

  private void reportMetrics(JsonNode metrics) {
    ReportableEntityPreprocessor preprocessor = preprocessorSupplier == null ?
        null : preprocessorSupplier.get();
    String[] messageHolder = new String[1];
    for (final JsonNode metric : metrics) {
      JsonNode host = metric.get("host");
      String hostName;
      if (host != null) {
        hostName = host.textValue();
        if (hostName == null || hostName.isEmpty()) {
          hostName = defaultHost;
        }
      } else {
        hostName = defaultHost;
      }

      JsonNode time = metric.get("time");
      long ts = 0;
      if (time != null) {
        ts = time.asLong() * 1000;
      }
      JsonNode values = metric.get("values");
      if (values == null) {
        pointHandler.reject((ReportPoint) null, "[values] missing in JSON object");
        logger.warning("Skipping - [values] missing in JSON object.");
        continue;
      }
      int index = 0;
      for (final JsonNode value : values) {
        String metricName = getMetricName(metric, index);
        ReportPoint.Builder builder = ReportPoint.newBuilder()
            .setMetric(metricName)
            .setTable("dummy")
            .setTimestamp(ts)
            .setHost(hostName);
        if (value.isDouble()) {
          builder.setValue(value.asDouble());
        } else {
          builder.setValue(value.asLong());
        }
        List<ReportPoint> parsedPoints = Lists.newArrayListWithExpectedSize(1);
        ReportPoint point = builder.build();
        if (preprocessor != null && preprocessor.forPointLine().getTransformers().size() > 0) {
          //
          String pointLine = ReportPointSerializer.pointToString(point);
          pointLine = preprocessor.forPointLine().transform(pointLine);
          recoder.decodeReportPoints(pointLine, parsedPoints, "dummy");
        } else {
          parsedPoints.add(point);
        }
        for (ReportPoint parsedPoint : parsedPoints) {
          if (preprocessor != null) {
            preprocessor.forReportPoint().transform(point);
            if (!preprocessor.forReportPoint().filter(point, messageHolder)) {
              if (messageHolder[0] != null) {
                pointHandler.reject(point, messageHolder[0]);
              } else {
                pointHandler.block(point);
              }
              continue;
            }
          }
          pointHandler.report(parsedPoint);
        }
        index++;
      }
    }
  }

  /**
   * Generates a metric name from json format:
   {
   "values": [197141504, 175136768],
   "dstypes": ["counter", "counter"],
   "dsnames": ["read", "write"],
   "time": 1251533299,
   "interval": 10,
   "host": "leeloo.lan.home.verplant.org",
   "plugin": "disk",
   "plugin_instance": "sda",
   "type": "disk_octets",
   "type_instance": ""
   }

   host "/" plugin ["-" plugin instance] "/" type ["-" type instance] =>
   {plugin}[.{plugin_instance}].{type}[.{type_instance}]
   */
  private static String getMetricName(final JsonNode metric, int index) {
    JsonNode plugin = metric.get("plugin");
    JsonNode plugin_instance = metric.get("plugin_instance");
    JsonNode type = metric.get("type");
    JsonNode type_instance = metric.get("type_instance");

    if (plugin == null || type == null) {
      throw new IllegalArgumentException("plugin or type is missing");
    }

    StringBuilder sb = new StringBuilder();
    sb.append(plugin.textValue());
    sb.append('.');
    if (plugin_instance != null) {
      String value = plugin_instance.textValue();
      if (value != null && !value.isEmpty()) {
        sb.append(value);
        sb.append('.');
      }
    }
    sb.append(type.textValue());
    sb.append('.');
    if (type_instance != null) {
      String value = type_instance.textValue();
      if (value != null && !value.isEmpty()) {
        sb.append(value);
        sb.append('.');
      }
    }

    JsonNode dsnames = metric.get("dsnames");
    if (dsnames == null || !dsnames.isArray() || dsnames.size() <= index) {
      throw new IllegalArgumentException("dsnames is not set");
    }
    sb.append(dsnames.get(index).textValue());
    return sb.toString();
  }
}
