package com.wavefront.agent.data;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.RecyclableRateLimiter;
import com.google.common.util.concurrent.RecyclableRateLimiterImpl;
import com.google.common.util.concurrent.RecyclableRateLimiterWithMetrics;
import com.wavefront.agent.ProxyConfig;
import com.wavefront.data.ReportableEntityType;

import javax.annotation.Nullable;
import java.util.Map;

import static com.wavefront.agent.config.ReportableConfig.reportSettingAsGauge;
import static org.apache.commons.lang3.ObjectUtils.firstNonNull;

/**
 * Generates entity-specific wrappers for dynamic proxy settings.
 *
 * @author vasily@wavefront.com
 */
public class EntityPropertiesFactoryImpl implements EntityPropertiesFactory {

  private final Map<ReportableEntityType, EntityProperties> wrappers;
  private final EntityProperties.GlobalProperties global;

  /**
   * @param proxyConfig proxy settings container
   */
  public EntityPropertiesFactoryImpl(ProxyConfig proxyConfig) {
    global = new GlobalPropertiesImpl(proxyConfig);
    EntityProperties pointProperties = new PointsProperties(proxyConfig, global);
    wrappers = ImmutableMap.<ReportableEntityType, EntityProperties>builder().
        put(ReportableEntityType.POINT, pointProperties).
        put(ReportableEntityType.DELTA_COUNTER, pointProperties).
        put(ReportableEntityType.HISTOGRAM, new HistogramsProperties(proxyConfig, global)).
        put(ReportableEntityType.SOURCE_TAG, new SourceTagsProperties(proxyConfig, global)).
        put(ReportableEntityType.TRACE, new SpansProperties(proxyConfig, global)).
        put(ReportableEntityType.TRACE_SPAN_LOGS, new SpanLogsProperties(proxyConfig, global)).
        put(ReportableEntityType.EVENT, new EventsProperties(proxyConfig, global)).build();
  }

  @Override
  public EntityProperties get(ReportableEntityType entityType) {
    return wrappers.get(entityType);
  }

  @Override
  public EntityProperties.GlobalProperties getGlobalProperties() {
    return global;
  }

  private static final class GlobalPropertiesImpl implements EntityProperties.GlobalProperties {
    private final ProxyConfig wrapped;
    private Double retryBackoffBaseSeconds = null;
    private short histogramStorageAccuracy = 32;
    private Double traceSamplingRate = null;

    GlobalPropertiesImpl(ProxyConfig wrapped) {
      this.wrapped = wrapped;
      reportSettingAsGauge(this::getRetryBackoffBaseSeconds, "dynamic.retryBackoffBaseSeconds");
    }

    @Override
    public double getRetryBackoffBaseSeconds() {
      return firstNonNull(retryBackoffBaseSeconds, wrapped.getRetryBackoffBaseSeconds());
    }

    @Override
    public void setRetryBackoffBaseSeconds(@Nullable Double retryBackoffBaseSeconds) {
      this.retryBackoffBaseSeconds = retryBackoffBaseSeconds;
    }

    @Override
    public short getHistogramStorageAccuracy() {
      return histogramStorageAccuracy;
    }

    @Override
    public void setHistogramStorageAccuracy(short histogramStorageAccuracy) {
      this.histogramStorageAccuracy = histogramStorageAccuracy;
    }

    public Double getTraceSamplingRate() {
      if (traceSamplingRate != null) {
        // use the minimum of backend provided and local proxy configured sampling rates.
        return Math.min(traceSamplingRate, wrapped.getTraceSamplingRate());
      } else {
        return wrapped.getTraceSamplingRate();
      }
    }

    public void setTraceSamplingRate(Double traceSamplingRate) {
      this.traceSamplingRate = traceSamplingRate;
    }
  }

  /**
   * Common base for all wrappers (to avoid code duplication)
   */
  private static abstract class AbstractEntityProperties implements EntityProperties {
    private Integer itemsPerBatch = null;
    protected final ProxyConfig wrapped;
    protected final GlobalProperties globalProperties;
    private final RecyclableRateLimiter rateLimiter;

    public AbstractEntityProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      this.wrapped = wrapped;
      this.globalProperties = globalProperties;
      this.rateLimiter = getRateLimit() > 0 ?
          new RecyclableRateLimiterWithMetrics(RecyclableRateLimiterImpl.create(
              getRateLimit(), getRateLimitMaxBurstSeconds()), getRateLimiterName()) :
          null;
      reportSettingAsGauge(this::getPushFlushInterval, "dynamic.pushFlushInterval");
    }

    @Override
    public int getItemsPerBatch() {
      return firstNonNull(itemsPerBatch, getItemsPerBatchOriginal());
    }

    @Override
    public void setItemsPerBatch(@Nullable Integer itemsPerBatch) {
      this.itemsPerBatch = itemsPerBatch;
    }

    @Override
    public boolean isSplitPushWhenRateLimited() {
      return wrapped.isSplitPushWhenRateLimited();
    }

    @Override
    public GlobalProperties getGlobalProperties() {
      return globalProperties;
    }

    @Override
    public int getRateLimitMaxBurstSeconds() {
      return wrapped.getPushRateLimitMaxBurstSeconds();
    }

    @Override
    public RecyclableRateLimiter getRateLimiter() {
      return rateLimiter;
    }

    abstract protected String getRateLimiterName();

    @Override
    public int getFlushThreads() {
      return wrapped.getFlushThreads();
    }

    @Override
    public int getPushFlushInterval() {
      return wrapped.getPushFlushInterval();
    }

    @Override
    public int getMinBatchSplitSize() {
      return DEFAULT_MIN_SPLIT_BATCH_SIZE;
    }

    @Override
    public int getMemoryBufferLimit() {
      return wrapped.getPushMemoryBufferLimit();
    }

    @Override
    public TaskQueueLevel getTaskQueueLevel() {
      return wrapped.getTaskQueueLevel();
    }
  }

  /**
   * Base class for entity types that do not require separate subscriptions.
   */
  private static abstract class CoreEntityProperties extends AbstractEntityProperties {
    public CoreEntityProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
    }

    @Override
    public boolean isFeatureDisabled() {
      return false;
    }

    @Override
    public void setFeatureDisabled(boolean featureDisabledFlag) {
      throw new UnsupportedOperationException("Can't disable this feature");
    }
  }

  /**
   * Base class for entity types that do require a separate subscription and can be controlled
   * remotely.
   */
  private static abstract class SubscriptionBasedEntityProperties extends AbstractEntityProperties {
    private boolean featureDisabled = false;

    public SubscriptionBasedEntityProperties(ProxyConfig wrapped,
                                             GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
    }

    @Override
    public boolean isFeatureDisabled() {
      return featureDisabled;
    }

    @Override
    public void setFeatureDisabled(boolean featureDisabledFlag) {
      this.featureDisabled = featureDisabledFlag;
    }
  }

  /**
   * Runtime properties wrapper for points
   */
  private static final class PointsProperties extends CoreEntityProperties {
    public PointsProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
      reportSettingAsGauge(this::getItemsPerBatch, "dynamic.pushFlushMaxPoints");
      reportSettingAsGauge(this::getMemoryBufferLimit, "dynamic.pushMemoryBufferLimit");
    }

    @Override
    protected String getRateLimiterName() {
      return "limiter";
    }

    @Override
    public int getItemsPerBatchOriginal() {
      return wrapped.getPushFlushMaxPoints();
    }

    @Override
    public double getRateLimit() {
      return wrapped.getPushRateLimit();
    }
  }

  /**
   * Runtime properties wrapper for histograms
   */
  private static final class HistogramsProperties extends SubscriptionBasedEntityProperties {
    public HistogramsProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
      reportSettingAsGauge(this::getItemsPerBatch, "dynamic.pushFlushMaxHistograms");
      reportSettingAsGauge(this::getMemoryBufferLimit, "dynamic.pushMemoryBufferLimit");
    }

    @Override
    protected String getRateLimiterName() {
      return "limiter.histograms";
    }

    @Override
    public int getItemsPerBatchOriginal() {
      return wrapped.getPushFlushMaxHistograms();
    }

    @Override
    public double getRateLimit() {
      return wrapped.getPushRateLimitHistograms();
    }
  }

  /**
   * Runtime properties wrapper for source tags
   */
  private static final class SourceTagsProperties extends CoreEntityProperties {
    public SourceTagsProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
      reportSettingAsGauge(this::getItemsPerBatch, "dynamic.pushFlushMaxSourceTags");
      reportSettingAsGauge(this::getMemoryBufferLimit, "dynamic.pushMemoryBufferLimitSourceTags");
    }

    @Override
    protected String getRateLimiterName() {
      return "limiter.sourceTags";
    }

    @Override
    public int getItemsPerBatchOriginal() {
      return wrapped.getPushFlushMaxSourceTags();
    }

    @Override
    public double getRateLimit() {
      return wrapped.getPushRateLimitSourceTags();
    }

    @Override
    public int getMemoryBufferLimit() {
      return 16 * wrapped.getPushFlushMaxSourceTags();
    }

    @Override
    public int getFlushThreads() {
      return wrapped.getFlushThreadsSourceTags();
    }
  }

  /**
   * Runtime properties wrapper for spans
   */
  private static final class SpansProperties extends SubscriptionBasedEntityProperties {
    public SpansProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
      reportSettingAsGauge(this::getItemsPerBatch, "dynamic.pushFlushMaxSpans");
      reportSettingAsGauge(this::getMemoryBufferLimit, "dynamic.pushMemoryBufferLimit");
    }

    @Override
    protected String getRateLimiterName() {
      return "limiter.spans";
    }

    @Override
    public int getItemsPerBatchOriginal() {
      return wrapped.getPushFlushMaxSpans();
    }

    @Override
    public double getRateLimit() {
      return wrapped.getPushRateLimitSpans();
    }
  }

  /**
   * Runtime properties wrapper for span logs
   */
  private static final class SpanLogsProperties extends SubscriptionBasedEntityProperties {
    public SpanLogsProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
      reportSettingAsGauge(this::getItemsPerBatch, "dynamic.pushFlushMaxSpanLogs");
      reportSettingAsGauge(this::getMemoryBufferLimit, "dynamic.pushMemoryBufferLimit");
    }

    @Override
    protected String getRateLimiterName() {
      return "limiter.spanLogs";
    }

    @Override
    public int getItemsPerBatchOriginal() {
      return wrapped.getPushFlushMaxSpanLogs();
    }

    @Override
    public double getRateLimit() {
      return wrapped.getPushRateLimitSpanLogs();
    }
  }

  /**
   * Runtime properties wrapper for events
   */
  private static final class EventsProperties extends CoreEntityProperties {
    public EventsProperties(ProxyConfig wrapped, GlobalProperties globalProperties) {
      super(wrapped, globalProperties);
      reportSettingAsGauge(this::getItemsPerBatch, "dynamic.pushFlushMaxEvents");
      reportSettingAsGauge(this::getMemoryBufferLimit, "dynamic.pushMemoryBufferLimitEvents");
    }

    @Override
    protected String getRateLimiterName() {
      return "limiter.events";
    }

    @Override
    public int getItemsPerBatchOriginal() {
      return wrapped.getPushFlushMaxEvents();
    }

    @Override
    public double getRateLimit() {
      return wrapped.getPushRateLimitEvents();
    }

    @Override
    public int getMemoryBufferLimit() {
      return 16 * wrapped.getPushFlushMaxEvents();
    }

    @Override
    public int getFlushThreads() {
      return wrapped.getFlushThreadsEvents();
    }
  }
}
