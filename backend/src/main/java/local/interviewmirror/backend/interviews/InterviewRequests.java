package local.interviewmirror.backend.interviews;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class InterviewRequests {
    private InterviewRequests() {}

    public static final class Create {
        @NotBlank @Size(max = 16) private String schemaVersion;
        @NotBlank @Size(max = 100) private String clientRequestId;
        @NotNull private InterviewMode mode;
        private UUID resumeId;
        private UUID questionBankId;
        @Size(max = 1500) private String jdText;
        @NotBlank @Size(max = 16) private String locale;
        @NotNull private Boolean modelDataConsent;
        private boolean jdTextPresent;

        public Create() {}
        public Create(String schemaVersion, String clientRequestId, InterviewMode mode,
                UUID resumeId, UUID questionBankId, String jdText, String locale, Boolean modelDataConsent) {
            this(schemaVersion, clientRequestId, mode, resumeId, questionBankId, jdText,
                    jdText != null, locale, modelDataConsent);
        }

        public Create(String schemaVersion, String clientRequestId, InterviewMode mode,
                UUID resumeId, UUID questionBankId, String jdText, boolean jdTextPresent,
                String locale, Boolean modelDataConsent) {
            this.schemaVersion = schemaVersion;
            this.clientRequestId = clientRequestId;
            this.mode = mode;
            this.resumeId = resumeId;
            this.questionBankId = questionBankId;
            this.jdText = jdText;
            this.jdTextPresent = jdTextPresent;
            this.locale = locale;
            this.modelDataConsent = modelDataConsent;
        }

        public String schemaVersion() { return schemaVersion; }
        public String clientRequestId() { return clientRequestId; }
        public InterviewMode mode() { return mode; }
        public UUID resumeId() { return resumeId; }
        public UUID questionBankId() { return questionBankId; }
        public String jdText() { return jdText; }
        public String locale() { return locale; }
        public Boolean modelDataConsent() { return modelDataConsent; }
        public boolean jdTextPresent() { return jdTextPresent; }

    }

    public record Answer(
            @NotNull UUID turnId,
            @NotBlank @Size(max = 100) String clientRequestId,
            @NotBlank @Size(max = 10000) String answer) {}

    public record Replace(
            @NotNull UUID turnId,
            @NotBlank @Size(max = 100) String clientRequestId) {}

    public record End(
            @NotBlank @Size(max = 100) String clientRequestId) {}
}
