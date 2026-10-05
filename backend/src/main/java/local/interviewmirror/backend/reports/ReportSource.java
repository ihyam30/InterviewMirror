package local.interviewmirror.backend.reports;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ReportSource(UUID interviewId, UUID ownerId, String mode, String status,
        String title, String completionReason, String modelProvider, String modelId,
        String jdText, JsonNode sourceSnapshot, Instant completedAt, List<SourceTurn> turns) {
    public record SourceTurn(UUID id, int sequence, String type, int mainQuestionIndex,
            Integer followupIndex, String sourceQuestionId, String question, String answer, Instant answeredAt) {}
}
