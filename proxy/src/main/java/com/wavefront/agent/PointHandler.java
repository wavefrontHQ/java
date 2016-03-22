package com.wavefront.agent;

import com.google.common.annotations.VisibleForTesting;

import com.wavefront.agent.api.ForceQueueEnabledAgentAPI;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;

import org.apache.commons.lang.time.DateUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import sunnylabs.report.ReportPoint;

/**
 * Adds all graphite strings to a working list, and batches them up on a set schedule (100ms) to be
 * sent (through the daemon's logic) up to the collector on the server side.
 */
public class PointHandler {

  private static final Logger logger = Logger.getLogger(PointHandler.class.getCanonicalName());

  // What types of data should be validated and sent to the cloud?
  public static final String VALIDATION_NO_VALIDATION = "NO_VALIDATION";  // Validate nothing
  public static final String VALIDATION_NUMERIC_ONLY = "NUMERIC_ONLY";    // Validate/send
  // numerics; block text

  private final Counter outOfRangePointTimes;
  private final Counter illegalCharacterPoints;
  private final String validationLevel;
  private final int port;

  protected final int blockedPointsPerBatch;
  protected final PostPushDataTimedTask sendDataTask;

  public PointHandler(final ForceQueueEnabledAgentAPI agentAPI, final UUID daemonId, final int
      port, final String logLevel, final String validationLevel, final long millisecondsPerBatch,
                      final int blockedPointsPerBatch) {
    this.validationLevel = validationLevel;
    this.port = port;
    this.blockedPointsPerBatch = blockedPointsPerBatch;

    this.sendDataTask = new PostPushDataTimedTask(agentAPI, logLevel, daemonId, port);

    this.outOfRangePointTimes = Metrics.newCounter(new MetricName("point", "", "badtime"));
    this.illegalCharacterPoints = Metrics.newCounter(new MetricName("point", "", "badchars"));

    int numTimerThreadsUsed = Runtime.getRuntime().availableProcessors();
    logger.info("Using " + numTimerThreadsUsed + " timer threads for listener on port: " + port);
    ScheduledExecutorService es = Executors.newScheduledThreadPool(numTimerThreadsUsed);
    for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
      es.scheduleWithFixedDelay(this.sendDataTask, millisecondsPerBatch, millisecondsPerBatch,
          TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Send a point for reporting.
   *
   * @param point     Point to report.
   * @param debugLine Debug information to print to console when the line is rejected.
   */
  public void reportPoint(ReportPoint point, String debugLine) {
    try {
      Object pointValue = point.getValue();

      if (!charactersAreValid(point.getMetric())) {
        illegalCharacterPoints.inc();
        String errorMessage = "WF-400 " + port + ": Point metric has illegal character (" +
            debugLine + ")";
        throw new IllegalArgumentException(errorMessage);
      }

      if (!annotationKeysAreValid(point)) {
        String errorMessage = "WF-401 " + port + ": Point annotation key has illegal character ("
            + debugLine + ")";
        throw new IllegalArgumentException(errorMessage);
      }

      if (!pointInRange(point)) {
        outOfRangePointTimes.inc();
        String errorMessage = "WF-402 " + port + ": Point outside of reasonable time frame (" +
            debugLine + ")";
        throw new IllegalArgumentException(errorMessage);
      }

      if ((validationLevel != null) && (!validationLevel.equals(VALIDATION_NO_VALIDATION))) {
        // Is it the right type of point?
        switch (validationLevel) {
          case VALIDATION_NUMERIC_ONLY:
            if (!(pointValue instanceof Long) && !(pointValue instanceof Double)) {
              String errorMessage = "WF-403 " + port + ": Was not long/double object (" +
                  debugLine + ")";
              throw new IllegalArgumentException(errorMessage);
            }
            break;
        }
        this.sendDataTask.addPoint(pointToString(point));
      } else {
        // No validation was requested by user; send forward.
        this.sendDataTask.addPoint(pointToString(point));
      }
    } catch (IllegalArgumentException e) {
      if (this.sendDataTask.getBlockedSampleSize() < this.blockedPointsPerBatch) {
        this.sendDataTask.addBlockedSample(e.getMessage());
      }
      this.sendDataTask.incrementBlockedPoints();
    } catch (Exception ex) {
      logger.log(Level.SEVERE, "WF-500 Uncaught exception when handling point", ex);
    }
  }

  private static final long MILLIS_IN_YEAR = DateUtils.MILLIS_PER_DAY * 365;

  @VisibleForTesting
  static boolean annotationKeysAreValid(ReportPoint point) {
    for (String key : point.getAnnotations().keySet()) {
      if (!charactersAreValid(key)) {
        return false;
      }
    }
    return true;
  }

  @VisibleForTesting
  static boolean charactersAreValid(String input) {
    // Legal characters are 44-57 (,-./ and numbers), 65-90 (upper), 97-122 (lower), 95 (_)
    int l = input.length();
    if (l == 0) {
      return false;
    }

    for (int i = 0; i < l; i++) {
      char cur = input.charAt(i);
      if (!(44 <= cur && cur <= 57) && !(65 <= cur && cur <= 90) && !(97 <= cur && cur <= 122) &&
          cur != 95) {
        if (i != 0 || cur != 126) {
          // first character can be 126 (~)
          return false;
        }
      }
    }
    return true;
  }


  @VisibleForTesting
  static boolean pointInRange(ReportPoint point) {
    long pointTime = point.getTimestamp();
    long rightNow = System.currentTimeMillis();

    // within 1 year ago and 1 day ahead
    return (pointTime > (rightNow - MILLIS_IN_YEAR)) && (pointTime < (rightNow + DateUtils
        .MILLIS_PER_DAY));
  }

  protected String pointToString(ReportPoint point) {
    String toReturn = String.format("\"%s\" %s %d source=\"%s\"", point.getMetric().replaceAll
        ("\"", "\\\""), point.getValue(), point.getTimestamp() / 1000, point.getHost().replaceAll
        ("\"", "\\\""));
    for (Map.Entry<String, String> entry : point.getAnnotations().entrySet()) {
      toReturn += String.format(" \"%s\"=\"%s\"", entry.getKey().replaceAll("\"", "\\\""), entry
          .getValue().replaceAll("\"", "\\\""));
    }
    return toReturn;
  }
}
