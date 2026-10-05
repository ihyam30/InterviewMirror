package local.interviewmirror.backend.reports;

import java.util.List;

public interface ReportModel {
    ReportOutput generateReport(String context);
    default DimensionReviewOutput reviewUnassessedDimensions(String context, List<String> dimensions) {
        return new DimensionReviewOutput(List.of());
    }
    SummaryEvidenceReview verifySummaryEvidence(String context);
    GapOutput generateGapAnalysis(String context);
    boolean isConfigured();
    String provider();
    String modelId();

    record ReportOutput(String overallReview, Integer overallScore,
            List<DimensionOutput> scores, List<TurnOutput> turns, List<InsightOutput> strengths,
            List<InsightOutput> risks, List<RecommendationOutput> recommendations,
            List<LearningOutput> learningPath, List<String> overallReviewEvidenceIds) {}
    record DimensionReviewOutput(List<DimensionOutput> scores) {}
    record SummaryEvidenceReview(boolean directlySupported, List<String> evidenceIds) {}
    record SummaryClaimReview(boolean directlySupported, List<String> evidenceIds) {}
    record DimensionOutput(String key, String status, Integer value, String rationale, List<String> evidenceIds) {}
    record TurnOutput(String turnId, String feedback, List<String> strengths, List<String> improvements,
            List<String> evidenceIds) {}
    record InsightOutput(String text, List<String> evidenceIds) {}
    record RecommendationOutput(String action, String why, List<String> evidenceIds) {}
    record LearningOutput(Integer order, String objective, List<String> activities, List<String> evidenceIds) {}
    record GapOutput(List<RequirementOutput> requirements) {}
    record RequirementOutput(String text, String importance, Double resumeScore, Double interviewScore,
            Double confidence, String rationale, String recommendation, List<String> jdEvidenceIds,
            List<String> resumeEvidenceIds, List<String> interviewEvidenceIds, List<String> evidenceIds) {
        public RequirementOutput(String text, String importance, Double resumeScore, Double interviewScore,
                Double confidence, String rationale, String recommendation, List<String> evidenceIds) {
            this(text, importance, resumeScore, interviewScore, confidence, rationale, recommendation,
                    null, null, null, evidenceIds);
        }
    }
}
