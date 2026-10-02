package local.interviewmirror.backend.resources;

import java.time.Instant;
import java.util.UUID;

public record DemoResourceView(UUID id, String resourceType, String title, String content, UUID fileId,
                               Instant createdAt, Instant updatedAt) {
    public static DemoResourceView from(DemoResource resource) {
        return new DemoResourceView(resource.id(), resource.resourceType(), resource.title(), resource.content(),
                resource.fileId(), resource.createdAt(), resource.updatedAt());
    }
}
