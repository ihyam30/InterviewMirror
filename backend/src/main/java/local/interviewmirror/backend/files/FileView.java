package local.interviewmirror.backend.files;

import java.time.Instant;
import java.util.UUID;

public record FileView(UUID id, String originalFilename, String contentType, long sizeBytes, Instant createdAt) {}
