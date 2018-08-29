package com.wavefront.examples;

import com.wavefront.integrations.dropwizard_metrics5.WavefrontReporter;

import io.dropwizard.metrics5.Counter;
import io.dropwizard.metrics5.MetricName;
import io.dropwizard.metrics5.MetricRegistry;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * Example for reporting dropwizard metrics into Wavefront via Direct Ingestion.
 *
 * @author Subramaniam Narayanan
 */
public class DirectReporting {
  public static void main(String args[]) throws InterruptedException {
    String server = args[0];
    String token = args[1];

    MetricRegistry registry = new MetricRegistry();
    HashMap<String, String> tags = new HashMap<>();
    tags.put("pointkey1", "ptag1");
    tags.put("pointkey2", "ptag2");
    // Create metric name object to associated with the metric type. The key is the
    // metric name and the value are the optional point tags.
    MetricName counterMetric = new MetricName("direct.dw5metric.foo.bar", tags);
    // Register the counter with the metric registry
    Counter counter = registry.counter(counterMetric);

    // Create a Wavefront Reporter as a direct reporter - requires knowledge of
    // Wavefront server to connect to along with a valid token.
    // NOTE: If the individual metric share the same key as the global point tag key, the
    // metric level value will override global level value for that point tag.
    // Example: Global point tag is    <"Key1", "Value-Global">
    // and metric level point tag is:  <"Key1", "Value-Metric1">
    // the point tag sent to Wavefront will be <"Key1", "Value-Metric1">
    WavefrontReporter reporter = WavefrontReporter.forRegistry(registry).
        withSource("app-1.company.com").
        withPointTag("gkey1", "gvalue1").
        buildDirect(server, token);
    reporter.start(5, TimeUnit.SECONDS);

    int i = 0;
    while (i++ < 30) {
      // Periodically update counter
      counter.inc(10);
      Thread.sleep(1000);
    }
    reporter.stop();
  }
}
