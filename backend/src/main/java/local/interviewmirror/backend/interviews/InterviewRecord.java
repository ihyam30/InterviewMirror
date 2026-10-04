package local.interviewmirror.backend.interviews;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

record InterviewRecord(UUID id, UUID ownerId, InterviewMode mode, InterviewStatus status,
        String clientRequestId, String requestFingerprint, String schemaVersion, String promptVersion,
        String modelProvider, String modelId, UUID resumeId, UUID questionBankId, String title, String jdText,
        JsonNode sourceSnapshot, JsonNode questionPlan, int mainQuestionTarget, int currentMainIndex,
        int currentFollowupCount, UUID activeTurnId, String completionReason, String transitionState,
        UUID transitionTurnId, String graphThreadId, Instant modelDataConsentAt, long version,
        Instant createdAt, Instant startedAt, Instant completedAt, Instant updatedAt) {}
