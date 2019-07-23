package com.wavefront.agent.histogram.accumulator;

import com.tdunning.math.stats.AgentDigest;
import com.wavefront.agent.histogram.TimeProvider;
import com.wavefront.agent.histogram.Utils;

import java.util.Iterator;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;

import wavefront.report.Histogram;

/**
 * Caching wrapper around the backing store.
 *
 * @author vasily@wavefront.com
 */
public interface Accumulator {

  /**
   * Update {@code AgentDigest} in the cache with another {@code AgentDigest}.
   *
   * @param key   histogram key
   * @param value {@code AgentDigest} to be merged
   */
  void put(Utils.HistogramKey key, @Nonnull AgentDigest value);

  /**
   * Update {@code AgentDigest} in the cache with a double value. If such {@code AgentDigest} does not exist for
   * the specified key, it will be created with the specified compression and ttlMillis settings.
   *
   * @param key         histogram key
   * @param value       value to be merged into the {@code AgentDigest}
   * @param compression default compression level for new bins
   * @param ttlMillis   default time-to-dispatch for new bins
   */
  void put(Utils.HistogramKey key, double value, short compression, long ttlMillis);

  /**
   * Update {@code AgentDigest} in the cache with a {@code Histogram} value. If such {@code AgentDigest} does not exist
   * for the specified key, it will be created with the specified compression and ttlMillis settings.
   *
   * @param key         histogram key
   * @param value       a {@code Histogram} to be merged into the {@code AgentDigest}
   * @param compression default compression level for new bins
   * @param ttlMillis   default time-to-dispatch in milliseconds for new bins
   */
  void put(Utils.HistogramKey key, Histogram value, short compression, long ttlMillis);

  /**
   * Attempts to compute a mapping for the specified key and its current mapped value
   * (or null if there is no current mapping).
   *
   * @param key               key with which the specified value is to be associated
   * @param remappingFunction the function to compute a value
   * @return                  the new value associated with the specified key, or null if none
   */
  AgentDigest compute(Utils.HistogramKey key, BiFunction<? super Utils.HistogramKey, ? super AgentDigest,
      ? extends AgentDigest> remappingFunction);

  /**
   * Returns an iterator over "ripe" digests ready to be shipped
   *
   * @param clock a millisecond-precision epoch time source
   * @return an iterator over "ripe" digests ready to be shipped
   */
  Iterator<Utils.HistogramKey> getRipeDigestsIterator(TimeProvider clock);

  /**
   * Returns the number of items in the storage behind the cache
   *
   * @return number of items
   */
  long size();

  /**
   * Merge the contents of this cache with the corresponding backing store.
   */
  void flush();
}
