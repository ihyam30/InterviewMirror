package local.interviewmirror.backend.resources;

import java.time.Instant;
import java.util.UUID;

public record DemoResource(UUID id, UUID ownerId, String resourceType, String title, String content,
                           UUID fileId, Instant createdAt, Instant updatedAt) {}
