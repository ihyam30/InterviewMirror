package local.interviewmirror.backend.resumes;

import java.time.Instant;
import java.util.UUID;

public record ResumeRecord(UUID id, UUID userId, String fileHash, String title, String originalFilename,
        long fileSize, String contentType, String storageKey, String resumeText, String structuredContent,
        Instant uploadedAt, long accessCount, String analyzeStatus, String analyzeError,
        Instant analyzeUpdatedAt, int analyzeRecoveryCount, int analyzeRetryCount, UUID analyzeAttemptId, String deleteStatus) {}
