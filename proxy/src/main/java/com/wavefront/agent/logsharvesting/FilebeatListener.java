package com.wavefront.agent.logsharvesting;

import com.google.common.annotations.VisibleForTesting;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.wavefront.agent.PointHandler;
import com.wavefront.agent.config.ConfigurationException;
import com.wavefront.agent.config.LogsIngestionConfig;
import com.wavefront.agent.config.MetricMatcher;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.WavefrontHistogram;

import org.logstash.beats.IMessageListener;
import org.logstash.beats.Message;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import io.netty.channel.ChannelHandlerContext;
import sunnylabs.report.TimeSeries;

/**
 * Listens for messages from Filebeat and processes them, sending telemetry to a given sink.
 *
 * @author Mori Bellamy (mori@wavefront.com)
 */
public class FilebeatListener implements IMessageListener {
  protected static final Logger logger = Logger.getLogger(FilebeatListener.class.getCanonicalName());
  private static final ReadProcessor readProcessor = new ReadProcessor();
  private final FlushProcessor flushProcessor;
  private final PointHandler pointHandler;
  private final MetricsRegistry metricsRegistry;
  // A map from "true" to the currently loaded logs ingestion config.
  private final LoadingCache<Boolean, LogsIngestionConfig> logsIngestionConfigLoadingCache;
  private LogsIngestionConfig lastIngestionConfig;
  private final LoadingCache<MetricName, Metric> metricCache;
  private final Counter received, unparsed, parsed, sent, malformed;
  private final Histogram drift;
  private final Supplier<Long> currentMillis;
  private final MetricsReporter metricsReporter;

  /**
   * @param pointHandler                play parsed metrics
   * @param logsIngestionConfigSupplier supplied configuration object for logs harvesting. May be reloaded. Must return
   *                                    "null" on any problems, as opposed to throwing
   * @param prefix                      all harvested metrics start with this prefix
   * @param currentMillis               supplier of the current time in millis
   * @throws ConfigurationException if the first config from logsIngestionConfigSupplier is null
   */
  public FilebeatListener(PointHandler pointHandler, Supplier<LogsIngestionConfig> logsIngestionConfigSupplier,
                          String prefix, Supplier<Long> currentMillis) throws ConfigurationException {
    // Parse initial config. We'll throw an exception if the very first config
    // is bad, which will abort future attempts at parsing logs.
    lastIngestionConfig = logsIngestionConfigSupplier.get();
    if (lastIngestionConfig == null) {
      throw new ConfigurationException("Could not get initial config for FilebeatListener.");
    }
    lastIngestionConfig.verifyAndInit();  // Might throw.
    logger.info("Loaded initial config: " + lastIngestionConfig.toString());
    // Set up hotloading. Any bad configs are ignored and an error is logged.
    this.logsIngestionConfigLoadingCache = Caffeine.<Boolean, LogsIngestionConfig>newBuilder()
        .expireAfterWrite(lastIngestionConfig.configReloadIntervalSeconds, TimeUnit.SECONDS)
        .build((ignored) -> {
          LogsIngestionConfig nextConfig = logsIngestionConfigSupplier.get();
          if (nextConfig == null) {
            logger.warning("Could not load a new logs ingestion config file, check above for a stack trace.");
          } else if (!lastIngestionConfig.toString().equals(nextConfig.toString())) {
            nextConfig.verifyAndInit();  // If it throws, we keep the last (good) config.
            lastIngestionConfig = nextConfig;
            logger.info("Loaded new config: " + lastIngestionConfig.toString());
          }
          return lastIngestionConfig;
        });
    // Force reloads every N seconds, so we can log helpful messages to the user.
    new Timer().schedule(new TimerTask() {
      @Override
      public void run() {
        logsIngestionConfigLoadingCache.get(true);
      }
    }, lastIngestionConfig.aggregationIntervalSeconds, lastIngestionConfig.aggregationIntervalSeconds);

    // Logs harvesting metrics.
    this.received = Metrics.newCounter(new MetricName("logsharvesting", "", "received"));
    this.unparsed = Metrics.newCounter(new MetricName("logsharvesting", "", "unparsed"));
    this.parsed = Metrics.newCounter(new MetricName("logsharvesting", "", "parsed"));
    this.malformed = Metrics.newCounter(new MetricName("logsharvesting", "", "malformed"));
    this.sent = Metrics.newCounter(new MetricName("logsharvesting", "", "sent"));
    this.drift = Metrics.newHistogram(new MetricName("logsharvesting", "", "drift"));
    this.currentMillis = currentMillis;
    this.flushProcessor = new FlushProcessor(sent, currentMillis);

    // Set up user specified metric harvesting.
    this.pointHandler = pointHandler;
    this.metricsRegistry = new MetricsRegistry();
    this.metricCache = Caffeine.<MetricName, Metric>newBuilder()
        .expireAfterAccess(lastIngestionConfig.expiryMillis, TimeUnit.MILLISECONDS)
        .<MetricName, Metric>removalListener((metricName, metric, reason) -> {
          if (metricName == null || metric == null) {
            logger.severe("Application error, pulled null key or value from metricCache.");
            return;
          }
          metricsRegistry.removeMetric(metricName);
        })
        .build(new MetricCacheLoader(metricsRegistry, currentMillis));

    // Continually flush user metrics to Wavefront.
    this.metricsReporter = new MetricsReporter(
        metricsRegistry, flushProcessor, "FilebeatMetricsReporter", pointHandler, prefix);
    this.metricsReporter.start(lastIngestionConfig.aggregationIntervalSeconds, TimeUnit.SECONDS);
  }

  @VisibleForTesting
  MetricsReporter getMetricsReporter() {
    return metricsReporter;
  }

  @VisibleForTesting
  LoadingCache<MetricName, Metric> getMetricCache() {
    return metricCache;
  }

  @Override
  public void onNewMessage(ChannelHandlerContext ctx, Message message) {
    received.inc();
    FilebeatMessage filebeatMessage;
    boolean success = false;
    try {
      filebeatMessage = new FilebeatMessage(message);
    } catch (MalformedMessageException exn) {
      logger.severe("Malformed message received from filebeat, dropping.");
      malformed.inc();
      return;
    }

    if (filebeatMessage.getTimestampMillis() != null) {
      drift.update(currentMillis.get() - filebeatMessage.getTimestampMillis());
    }

    LogsIngestionConfig logsIngestionConfig = logsIngestionConfigLoadingCache.get(true);

    Double[] output = {null};
    for (MetricMatcher metricMatcher : logsIngestionConfig.counters) {
      TimeSeries timeSeries = metricMatcher.timeSeries(filebeatMessage, output);
      if (timeSeries == null) continue;
      readMetric(Counter.class, timeSeries, output[0]);
      success = true;
    }

    for (MetricMatcher metricMatcher : logsIngestionConfig.gauges) {
      TimeSeries timeSeries = metricMatcher.timeSeries(filebeatMessage, output);
      if (timeSeries == null) continue;
      readMetric(Gauge.class, timeSeries, output[0]);
      success = true;
    }

    for (MetricMatcher metricMatcher : logsIngestionConfig.histograms) {
      TimeSeries timeSeries = metricMatcher.timeSeries(filebeatMessage, output);
      if (timeSeries == null) continue;
      readMetric(logsIngestionConfig.useWavefrontHistograms ? WavefrontHistogram.class : Histogram.class,
          timeSeries, output[0]);
      success = true;
    }
    if (!success) unparsed.inc();
  }

  private void readMetric(Class clazz, TimeSeries timeSeries, Double value) {
    MetricName metricName = TimeSeriesUtils.toMetricName(clazz, timeSeries);
    Metric metric = metricCache.get(metricName);
    try {
      metric.processWith(readProcessor, metricName, new ReadProcessorContext(value));
    } catch (Exception e) {
      logger.severe("Could not process metric " + metricName.toString());
      e.printStackTrace();
    }
    parsed.inc();
  }

  @Override
  public void onNewConnection(ChannelHandlerContext ctx) {
  }

  @Override
  public void onConnectionClose(ChannelHandlerContext ctx) {
  }

  @Override
  public void onException(ChannelHandlerContext ctx, Throwable cause) {
  }

  @Override
  public void onChannelInitializeException(ChannelHandlerContext ctx, Throwable cause) {
  }
}
