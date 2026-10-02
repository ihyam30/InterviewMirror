package local.interviewmirror.backend.resources;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class ResourceRequests {
    private ResourceRequests() {}

    public record Create(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String resourceType,
                         @NotBlank @Size(max = 160) String title,
                         @Size(max = 20000) String content,
                         UUID fileId) {}

    public record Update(@NotBlank @Size(max = 160) String title,
                         @Size(max = 20000) String content,
                         UUID fileId) {}
}
