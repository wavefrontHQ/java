package com.wavefront.agent.histogram;

import com.google.common.annotations.VisibleForTesting;

import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.histogram.accumulator.Accumulator;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.MetricName;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import wavefront.report.ReportPoint;

import static java.lang.System.nanoTime;

/**
 * Dispatch task for marshalling "ripe" digests for shipment to the agent to a point handler.
 *
 * @author Tim Schmidt (tim@wavefront.com).
 */
public class PointHandlerDispatcher implements Runnable {
  private final static Logger logger = Logger.getLogger(
      PointHandlerDispatcher.class.getCanonicalName());

  private final Counter dispatchCounter;
  private final Counter dispatchErrorCounter;
  private final Counter dispatchProcessTime;

  private final Accumulator digests;
  private final ReportableEntityHandler<ReportPoint> output;
  private final TimeProvider clock;
  private final Integer dispatchLimit;

  public PointHandlerDispatcher(Accumulator digests,
                                ReportableEntityHandler<ReportPoint> output,
                                @Nullable Integer dispatchLimit,
                                @Nullable Utils.Granularity granularity) {
    this(digests, output, System::currentTimeMillis, dispatchLimit, granularity);
  }

  @VisibleForTesting
  PointHandlerDispatcher(Accumulator digests,
                         ReportableEntityHandler<ReportPoint> output,
                         TimeProvider clock,
                         @Nullable Integer dispatchLimit,
                         @Nullable Utils.Granularity granularity) {
    this.digests = digests;
    this.output = output;
    this.clock = clock;
    this.dispatchLimit = dispatchLimit;

    String prefix = "histogram.accumulator." + Utils.Granularity.granularityToString(granularity);
    this.dispatchCounter = Metrics.newCounter(new MetricName(prefix, "", "dispatched"));
    this.dispatchErrorCounter = Metrics.newCounter(new MetricName(prefix, "", "dispatch_errors"));
    Metrics.newGauge(new MetricName(prefix, "", "size"), new Gauge<Long>() {
      @Override
      public Long value() {
        return digests.size();
      }
    });
    this.dispatchProcessTime = Metrics.newCounter(new MetricName(prefix, "",
        "dispatch_process_millis"));
  }

  @Override
  public void run() {
    try {
      AtomicInteger dispatchedCount = new AtomicInteger(0);

      long startMillis = System.currentTimeMillis();
      Iterator<Utils.HistogramKey> index = digests.getRipeDigestsIterator(this.clock);
      while (index.hasNext()) {
        digests.compute(index.next(), (k, v) -> {
          if (v == null) {
            index.remove();
            return null;
          }
          try {
            ReportPoint out = Utils.pointFromKeyAndDigest(k, v);
            output.report(out);
            dispatchCounter.inc();
          } catch (Exception e) {
            dispatchErrorCounter.inc();
            logger.log(Level.SEVERE, "Failed dispatching entry " + k, e);
          }
          index.remove();
          dispatchedCount.incrementAndGet();
          return null;
        });
        if (dispatchLimit != null && dispatchedCount.get() >= dispatchLimit)
          break;
      }
      dispatchProcessTime.inc(System.currentTimeMillis() - startMillis);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "PointHandlerDispatcher error", e);
    }
  }
}
