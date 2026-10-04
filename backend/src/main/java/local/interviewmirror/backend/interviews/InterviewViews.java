package local.interviewmirror.backend.interviews;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InterviewViews {
    private InterviewViews() {}

    public record Turn(
            String schemaVersion,
            UUID id,
            int sequence,
            InterviewTurnType type,
            int mainQuestionIndex,
            Integer followupIndex,
            String sourceQuestionId,
            String question,
            String answer,
            String status,
            Instant askedAt,
            Instant answeredAt) {}

    public record Interview(
            String schemaVersion,
            UUID id,
            InterviewMode mode,
            InterviewStatus status,
            String title,
            UUID resumeId,
            UUID questionBankId,
            int mainQuestionTarget,
            int currentMainIndex,
            int currentFollowupCount,
            UUID activeTurnId,
            String completionReason,
            boolean transitionPending,
            boolean replacementAvailable,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            long version,
            Turn activeTurn) {}

    public record Created(Interview interview) {}
    public record TurnList(String schemaVersion, UUID interviewId, List<Turn> turns) {}
}
