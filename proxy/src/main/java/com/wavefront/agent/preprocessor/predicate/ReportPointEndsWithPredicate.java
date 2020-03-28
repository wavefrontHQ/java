package com.wavefront.agent.preprocessor.predicate;

import com.wavefront.agent.preprocessor.PreprocessorUtil;

import wavefront.report.ReportPoint;

/**
 * Predicate mimicking {@link String#endsWith(String)} for Wavefront reportable entities.
 *
 * @author Anil Kodali (akodali@vmware.com).
 */
public class ReportPointEndsWithPredicate extends ComparisonPredicate<ReportPoint>{

  public ReportPointEndsWithPredicate(String scope, String value) {
    super(scope, value);
  }

  @Override
  public boolean test(ReportPoint reportPoint) {
    String pointVal = PreprocessorUtil.getReportableEntityComparableValue(scope, reportPoint);
    if (pointVal != null) {
      return pointVal.endsWith(value);
    }
    return false;
  }
}
