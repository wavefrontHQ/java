package com.wavefront.common;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;
import org.apache.commons.lang.StringUtils;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * White/Black list checker for a metric.
 */
public class MetricWhiteBlackList {
  @Nullable
  private final Pattern pointLineWhiteList;
  @Nullable
  private final Pattern pointLineBlackList;

  /**
   * Counter for number of rejected metrics.
   */
  private final Counter regexRejects;

  /**
   * Constructor.
   * @param pointLineWhiteListRegex the white list regular expression.
   * @param pointLineBlackListRegex the black list regular expression
   * @param rejectCounterName the name for the counter (validationRegex.{rejectCounterName})
   */
  public MetricWhiteBlackList(@Nullable final String pointLineWhiteListRegex,
                              @Nullable final String pointLineBlackListRegex,
                              final String rejectCounterName) {
    this.pointLineWhiteList = StringUtils.isBlank(pointLineWhiteListRegex)
      ? null : Pattern.compile(pointLineWhiteListRegex);

    this.pointLineBlackList = StringUtils.isBlank(pointLineBlackListRegex)
      ? null : Pattern.compile(pointLineBlackListRegex);

    this.regexRejects = Metrics.newCounter(
      new MetricName("validationRegex." + rejectCounterName, "", "points-rejected"));
  }

  /**
   * Check to see if the given point line or metric passes the white and black
   * list.
   * @param pointLine the line to check
   * @return true if the line passes checks; false o/w
   */
  public boolean passes(String pointLine) {
    if ((pointLineWhiteList != null && !pointLineWhiteList.matcher(pointLine).matches())
        || (pointLineBlackList != null && pointLineBlackList.matcher(pointLine).matches())) {
      regexRejects.inc();
      return false;
    }
    return true;
  }

}
