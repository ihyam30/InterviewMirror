package local.interviewmirror.backend.reports;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class ReportSummaryEvidenceSanitizer {
    static final String UNVERIFIED_STATUS = "UNVERIFIED";
    static final String UNVERIFIED_TEXT = "系统未能确认总体评价与本场回答证据的一致性，因此暂不展示该总结。请参考下方逐题反馈，或重新生成报告。";

    private ReportSummaryEvidenceSanitizer() {}

    static boolean requiresRetry(JsonNode summary) {
        if (summary == null || summary.isMissingNode() || summary.isNull()) return false;
        String status = summary.path("overallReviewEvidenceStatus").asString("");
        String text = summary.path("overallReview").asString("");
        return "UNVERIFIED".equals(status) || "EXTRACTIVE_FALLBACK".equals(status)
                || text.startsWith("证据核验未通过，以下仅为原始回答摘录：");
    }

    static void sanitize(ObjectNode summary) {
        if (summary == null || !requiresRetry(summary)) return;
        summary.put("overallReviewEvidenceStatus", UNVERIFIED_STATUS);
        summary.put("overallReview", UNVERIFIED_TEXT);
        summary.putArray("overallReviewEvidence");
    }
}
