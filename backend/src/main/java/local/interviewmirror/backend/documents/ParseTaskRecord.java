package local.interviewmirror.backend.documents;

import java.time.Instant;
import java.util.UUID;

public record ParseTaskRecord(
        UUID id,
        UUID documentId,
        UUID ownerId,
        String status,
        int retryCount,
        int attemptCount,
        String errorCode,
        String errorMessage,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String workerId,
        Instant leaseUntil) {
}
