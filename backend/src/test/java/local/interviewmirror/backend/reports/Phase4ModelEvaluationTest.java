package local.interviewmirror.backend.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.interviewmirror.backend.InterviewMirrorApplication;
import local.interviewmirror.backend.files.ObjectStorage;
import local.interviewmirror.backend.interviews.InterviewGraphRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import static org.mockito.Mockito.mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real provider evaluation. Opt in explicitly with -Dphase4.model-eval=true. */
@SpringBootTest(classes = InterviewMirrorApplication.class)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "phase4.model-eval", matches = "true")
@Import(Phase4ModelEvaluationTest.EvaluationInfrastructure.class)
class Phase4ModelEvaluationTest {
    private static final String DATASET = "report-eval.v1.json";
    private static final List<String> DIMENSIONS = List.of("TECHNICAL_DEPTH", "PROJECT_EXPERIENCE", "JOB_MATCH",
            "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING");

    @Autowired ReportModel model;
    @Autowired ObjectMapper json;

    @Test
    void realModelMeetsReportEvidenceThresholdsOnSyntheticFixtures() throws Exception {
        assertTrue(model.isConfigured(), "Set INTERVIEW_MODEL_ENABLED=true and provider credentials for the opt-in evaluation.");
        JsonNode dataset = json.readTree(Files.readString(datasetPath(), StandardCharsets.UTF_8));
        JsonNode cases = dataset.path("cases");
        assertTrue(cases.size() >= 10, "Phase 4 evaluation requires at least 10 synthetic fixtures.");

        ReportService assembler = new ReportService(null, model, json);
        int completenessChecks = 0, completenessPassed = 0;
        int evidenceClaims = 0, evidenceBackedClaims = 0;
        int summaryClaims = 0, modelSupportedSummaryClaims = 0, unverifiedSummaryClaims = 0;
        List<Map<String, Object>> caseResults = new ArrayList<>();

        for (JsonNode fixture : cases) {
            String caseId = fixture.path("id").asString();
            ReportSource source = source(fixture);
            EvidenceCatalog catalog = new EvidenceCatalog(source);
            ReportModel.ReportOutput output = model.generateReport(assembler.reportPromptContext(source, catalog));
            JsonNode report = json.valueToTree(assembler.assembleReport(UUID.randomUUID(), source, catalog, output, 1));

            List<String> missingFields = missingReportFields(report, source);
            int[] completeness = new int[] {completenessCheckCount(source) - missingFields.size(), completenessCheckCount(source)};
            completenessChecks += completeness[1]; completenessPassed += completeness[0];
            int[] evidence = reportEvidence(report);
            evidenceClaims += evidence[1]; evidenceBackedClaims += evidence[0];
            summaryClaims++;
            String evidenceStatus = report.path("summary").path("overallReviewEvidenceStatus").asString();
            if ("MODEL_SUPPORTED".equals(evidenceStatus)) modelSupportedSummaryClaims++;
            else if ("EXTRACTIVE_FALLBACK".equals(evidenceStatus) || "UNVERIFIED".equals(evidenceStatus)) unverifiedSummaryClaims++;

            if ("USER_ENDED".equals(source.completionReason())
                    && !report.path("summary").path("overallReview").asString().contains("提前结束")) {
                completenessChecks++;
            } else if ("USER_ENDED".equals(source.completionReason())) {
                completenessChecks++; completenessPassed++;
            }
            caseResults.add(Map.of("caseId", caseId, "reportComplete", completeness[0] == completeness[1],
                    "missingFields", missingFields, "turns", source.turns().size(),
                    "overallReviewEvidenceStatus", report.path("summary").path("overallReviewEvidenceStatus").asString()));
        }

        double completenessRate = ratio(completenessPassed, completenessChecks);
        double evidenceCoverageRate = ratio(evidenceBackedClaims, evidenceClaims);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dataset", DATASET); summary.put("syntheticOnly", true); summary.put("provider", model.provider());
        summary.put("model", model.modelId()); summary.put("cases", cases.size());
        summary.put("reportCompleteness", completenessRate); summary.put("evidenceCoverage", evidenceCoverageRate);
        summary.put("summaryEvidenceClaims", summaryClaims);
        summary.put("modelSupportedSummaryClaims", modelSupportedSummaryClaims);
        summary.put("unverifiedSummaryClaims", unverifiedSummaryClaims);
        summary.put("caseResults", caseResults);
        Path resultPath = Path.of("target", "phase4-report-evaluation.json");
        Files.createDirectories(resultPath.getParent());
        Files.writeString(resultPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardCharsets.UTF_8);
        System.out.printf("PHASE4_MODEL_EVALUATION provider=%s model=%s cases=%d completeness=%.4f evidenceCoverage=%.4f summaryModelSupported=%d summaryUnverified=%d result=%s%n",
                model.provider(), model.modelId(), cases.size(), completenessRate, evidenceCoverageRate,
                modelSupportedSummaryClaims, unverifiedSummaryClaims,
                resultPath.toAbsolutePath());

        assertTrue(completenessRate >= .95, "Report completeness must be >= 95%.");
        assertTrue(evidenceCoverageRate >= .95, "Report evidence coverage must be >= 95%.");
    }

    private ReportSource source(JsonNode fixture) {
        String id = fixture.path("id").asString();
        UUID interviewId = UUID.nameUUIDFromBytes(("phase4-" + id).getBytes(StandardCharsets.UTF_8));
        JsonNode snapshot = json.createObjectNode().set("resume", json.createObjectNode()
                .put("id", "resume-" + id).put("title", "Synthetic resume " + id).set("content", fixture.path("resume")));
        ((tools.jackson.databind.node.ObjectNode) snapshot).put("jdText", fixture.path("jdText").asString(""));
        List<ReportSource.SourceTurn> turns = new ArrayList<>();
        int sequence = 1;
        for (JsonNode item : fixture.path("turns")) {
            UUID turnId = UUID.nameUUIDFromBytes((id + "-turn-" + sequence).getBytes(StandardCharsets.UTF_8));
            turns.add(new ReportSource.SourceTurn(turnId, sequence, "MAIN", sequence, null, id + "-question-" + sequence,
                    item.path("question").asString(), item.path("answer").asString(), Instant.parse("2026-01-01T00:00:00Z")));
            sequence++;
        }
        return new ReportSource(interviewId, UUID.nameUUIDFromBytes(("owner-" + id).getBytes(StandardCharsets.UTF_8)),
                fixture.path("mode").asString(), "COMPLETE", fixture.path("title").asString(),
                fixture.path("completionReason").asString(), model.provider(), model.modelId(),
                fixture.path("jdText").asString(""), snapshot, Instant.parse("2026-01-01T00:10:00Z"), turns);
    }

    private static List<String> missingReportFields(JsonNode report, ReportSource source) {
        List<String> missing = new ArrayList<>();
        for (String path : List.of("summary.overallReview", "summary.overallScoreStatus",
                "summary.overallReviewEvidenceStatus", "strengths", "risks", "recommendations", "learningPath", "nextActions")) {
            JsonNode value = report.at("/" + path.replace('.', '/'));
            boolean valid = List.of("strengths", "risks", "recommendations", "learningPath", "nextActions").contains(path)
                    ? value.isArray() && !value.isEmpty() : nonEmpty(value);
            if (!valid) missing.add(path);
        }
        JsonNode overallEvidence = report.path("summary").path("overallReviewEvidence");
        if (!hasTurnEvidence(overallEvidence)) missing.add("summary.overallReviewEvidence");
        for (String dimension : DIMENSIONS) {
            if (!nonEmpty(report.path("scores").path(dimension).path("status"))) missing.add("scores." + dimension + ".status");
        }
        for (int index = 0; index < source.turns().size(); index++) {
            if (index >= report.path("turns").size() || !nonEmpty(report.path("turns").get(index).path("feedback")))
                missing.add("turns[" + index + "].feedback");
        }
        if ("USER_ENDED".equals(source.completionReason())
                && !report.path("summary").path("overallReview").asString().contains("提前结束")) missing.add("earlyEndNotice");
        return missing;
    }

    private static int completenessCheckCount(ReportSource source) {
        return 9 + DIMENSIONS.size() + source.turns().size() + ("USER_ENDED".equals(source.completionReason()) ? 1 : 0);
    }

    private static int[] reportEvidence(JsonNode report) {
        JsonNode summary = report.path("summary");
        int claims = 1;
        int backed = hasTurnEvidence(summary.path("overallReviewEvidence")) ? 1 : 0;
        for (String dimension : DIMENSIONS) {
            JsonNode score = report.path("scores").path(dimension);
            if ("ASSESSED".equals(score.path("status").asString())) { claims++; if (hasEvidence(score)) backed++; }
        }
        for (JsonNode turn : report.path("turns")) { claims++; if (hasEvidence(turn)) backed++; }
        for (String section : List.of("strengths", "risks", "recommendations", "learningPath")) {
            for (JsonNode item : report.path(section)) { claims++; if (hasEvidence(item)) backed++; }
        }
        return new int[] {backed, claims};
    }

    private static boolean hasEvidence(JsonNode item) { return item.path("evidence").isArray() && !item.path("evidence").isEmpty(); }
    private static boolean hasTurnEvidence(JsonNode evidence) {
        if (!evidence.isArray() || evidence.isEmpty()) return false;
        for (JsonNode item : evidence) if (!"TURN".equals(item.path("sourceType").asString())) return false;
        return true;
    }
    private static boolean nonEmpty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isArray() || value.isObject()) return !value.isEmpty();
        return !value.asString("").isBlank();
    }
    private static double ratio(int numerator, int denominator) { return denominator == 0 ? 1d : (double) numerator / denominator; }

    @TestConfiguration(proxyBeanMethods = false)
    static class EvaluationInfrastructure {
        @Bean @Primary ObjectStorage evaluationStorage() {
            return new ObjectStorage() {
                @Override public void put(String key, InputStream content, long size, String contentType) {}
                @Override public InputStream get(String key) { return new ByteArrayInputStream(new byte[0]); }
                @Override public void delete(String key) {}
            };
        }
        @Bean @Primary InterviewGraphRuntime evaluationGraph() { return mock(InterviewGraphRuntime.class); }
    }

    private static Path datasetPath() {
        List<Path> candidates = List.of(Path.of("data", "phase4", DATASET), Path.of("..", "data", "phase4", DATASET));
        return candidates.stream().map(Path::toAbsolutePath).map(Path::normalize).filter(Files::isRegularFile)
                .findFirst().orElseThrow(() -> new IllegalStateException("Cannot find data/phase4/" + DATASET));
    }
}
