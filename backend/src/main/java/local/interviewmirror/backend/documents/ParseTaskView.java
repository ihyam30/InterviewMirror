package local.interviewmirror.backend.documents;

import java.time.Instant;
import java.util.UUID;

public record ParseTaskView(
        UUID id,
        String status,
        int retryCount,
        int attemptCount,
        String errorCode,
        String errorMessage,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Long durationMs) {

    static ParseTaskView from(ParseTaskRecord task) {
        return task == null ? null : new ParseTaskView(task.id(), task.status(), task.retryCount(),
                task.attemptCount(), task.errorCode(), task.errorMessage(), task.queuedAt(), task.startedAt(),
                task.completedAt(), task.durationMs());
    }
}
