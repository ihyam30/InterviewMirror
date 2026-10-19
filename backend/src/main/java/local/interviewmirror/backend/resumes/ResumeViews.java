package local.interviewmirror.backend.resumes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class ResumeViews {
    private ResumeViews() {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Resume(UUID id, String type, String title, String originalFilename, long sizeBytes,
            String contentType, Instant createdAt, Instant updatedAt, long accessCount,
            String analyzeStatus, String analyzeError, String deleteStatus, boolean usableForInterview,
            JsonNode content, String resumeText, Analysis latestAnalysis, long interviewCount) {}
    public record Analysis(UUID id, UUID resumeId, String rubricVersion, int overallScore,
            int contentScore, int structureScore, int skillMatchScore, int expressionScore,
            int projectScore, JsonNode strengths, JsonNode suggestions, Instant analyzedAt) {}
    public record Interview(UUID id, String title, String status, Instant createdAt) {}
    public record Detail(Resume resume, Page<Analysis> analyses, Page<Interview> interviews) {}
}
