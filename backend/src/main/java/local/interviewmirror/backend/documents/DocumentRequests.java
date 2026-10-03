package local.interviewmirror.backend.documents;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public final class DocumentRequests {
    private DocumentRequests() { }

    public record Update(
            @NotNull @Positive Integer contentVersion,
            @NotBlank @Size(max = 255) String title,
            @NotNull JsonNode content) { }

    public record CreateQuestionBank(@NotBlank @Size(max = 255) String title) { }
}
