package com.wavefront.agent.preprocessor;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import wavefront.report.ReportPoint;

/**
 * Creates a new point tag with a specified value. If such point tag already exists, the value won't be overwritten.
 *
 * Created by Vasily on 9/13/16.
 */
public class ReportPointAddTagIfNotExistsTransformer extends ReportPointAddTagTransformer {


  public ReportPointAddTagIfNotExistsTransformer(final String tag,
                                                 final String value,
                                                 @Nullable final Predicate v2Predicate,
                                                 final PreprocessorRuleMetrics ruleMetrics) {
    super(tag, value, v2Predicate, ruleMetrics);
  }

  @Nullable
  @Override
  public ReportPoint apply(@Nullable ReportPoint reportPoint) {
    if (reportPoint == null) return null;
    long startNanos = ruleMetrics.ruleStart();

    if (!v2Predicate.test(reportPoint)) return reportPoint;

    if (reportPoint.getAnnotations().get(tag) == null) {
      reportPoint.getAnnotations().put(tag, PreprocessorUtil.expandPlaceholders(value, reportPoint));
      ruleMetrics.incrementRuleAppliedCounter();
    }
    ruleMetrics.ruleEnd(startNanos);
    return reportPoint;
  }
}
