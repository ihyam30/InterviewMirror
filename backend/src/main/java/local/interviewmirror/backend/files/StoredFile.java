package local.interviewmirror.backend.files;

import java.time.Instant;
import java.util.UUID;

public record StoredFile(UUID id, UUID ownerId, String objectKey, String originalFilename,
                         String contentType, long sizeBytes, Instant createdAt) {
    public FileView toView() { return new FileView(id, originalFilename, contentType, sizeBytes, createdAt); }
}
