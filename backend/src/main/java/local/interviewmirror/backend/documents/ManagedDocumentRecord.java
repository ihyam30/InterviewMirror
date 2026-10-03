package local.interviewmirror.backend.documents;

import java.time.Instant;
import java.util.UUID;

public record ManagedDocumentRecord(
        UUID id,
        UUID ownerId,
        UUID fileId,
        DocumentType type,
        String title,
        DocumentStatus status,
        String parsedContent,
        String content,
        int contentVersion,
        Integer confirmedVersion,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt,
        ParseTaskRecord task) {
}
