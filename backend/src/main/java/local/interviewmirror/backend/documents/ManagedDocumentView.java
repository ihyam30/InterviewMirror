package local.interviewmirror.backend.documents;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ManagedDocumentView(
        UUID id,
        UUID fileId,
        DocumentType type,
        String title,
        String originalFilename,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        JsonNode parsedContent,
        JsonNode content,
        int contentVersion,
        Integer confirmedVersion,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt,
        ParseTaskView parseTask,
        boolean usableForInterview) {
}
