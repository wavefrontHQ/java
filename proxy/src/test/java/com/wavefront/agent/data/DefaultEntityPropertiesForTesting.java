package com.wavefront.agent.data;

import com.google.common.util.concurrent.RecyclableRateLimiter;
import com.google.common.util.concurrent.RecyclableRateLimiterImpl;

import javax.annotation.Nullable;

/**
 * @author vasily@wavefront.com
 */
public class DefaultEntityPropertiesForTesting implements EntityProperties {
  private final GlobalProperties global = new GlobalPropertiesForTestingImpl();

  @Override
  public int getItemsPerBatchOriginal() {
    return DEFAULT_BATCH_SIZE;
  }

  @Override
  public boolean isSplitPushWhenRateLimited() {
    return DEFAULT_SPLIT_PUSH_WHEN_RATE_LIMITED;
  }

  @Override
  public double getRateLimit() {
    return NO_RATE_LIMIT;
  }

  @Override
  public int getRateLimitMaxBurstSeconds() {
    return DEFAULT_MAX_BURST_SECONDS;
  }

  @Override
  public RecyclableRateLimiter getRateLimiter() {
    return RecyclableRateLimiterImpl.create(NO_RATE_LIMIT, getRateLimitMaxBurstSeconds());
  }

  @Override
  public int getFlushThreads() {
    return 2;
  }

  @Override
  public int getPushFlushInterval() {
    return 100000;
  }

  @Override
  public int getItemsPerBatch() {
    return DEFAULT_BATCH_SIZE;
  }

  @Override
  public void setItemsPerBatch(@Nullable Integer itemsPerBatch) {
  }

  @Override
  public int getMinBatchSplitSize() {
    return DEFAULT_MIN_SPLIT_BATCH_SIZE;
  }

  @Override
  public int getMemoryBufferLimit() {
    return DEFAULT_MIN_SPLIT_BATCH_SIZE;
  }

  @Override
  public TaskQueueLevel getTaskQueueLevel() {
    return TaskQueueLevel.ANY_ERROR;
  }

  @Override
  public boolean isFeatureDisabled() {
    return false;
  }

  @Override
  public void setFeatureDisabled(boolean featureDisabled) {
  }

  @Override
  public GlobalProperties getGlobalProperties() {
    return global;
  }

  private static class GlobalPropertiesForTestingImpl implements GlobalProperties {

    @Override
    public double getRetryBackoffBaseSeconds() {
      return DEFAULT_RETRY_BACKOFF_BASE_SECONDS;
    }

    @Override
    public void setRetryBackoffBaseSeconds(@Nullable Double retryBackoffBaseSeconds) {
    }

    @Override
    public short getHistogramStorageAccuracy() {
      return 32;
    }

    @Override
    public void setHistogramStorageAccuracy(short histogramStorageAccuracy) {
    }

    @Override
    public Double getTraceSamplingRate() {
      return 1.0d;
    }

    @Override
    public void setTraceSamplingRate(Double traceSamplingRate) {
    }
  }
}
