package local.interviewmirror.backend.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.reports.ReportModel.DimensionOutput;
import local.interviewmirror.backend.reports.ReportModel.GapOutput;
import local.interviewmirror.backend.reports.ReportModel.InsightOutput;
import local.interviewmirror.backend.reports.ReportModel.LearningOutput;
import local.interviewmirror.backend.reports.ReportModel.RecommendationOutput;
import local.interviewmirror.backend.reports.ReportModel.ReportOutput;
import local.interviewmirror.backend.reports.ReportModel.RequirementOutput;
import local.interviewmirror.backend.reports.ReportModel.TurnOutput;
import local.interviewmirror.backend.reports.ReportRepository.ReportRow;
import local.interviewmirror.backend.reports.ReportRepository.ReportTask;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReportServiceTest {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();

    @Test
    void reportUsesOnlyValidatedTurnEvidenceAndDoesNotCreateGapForSpecializedMode() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportTask task = task(interview, owner, "REPORT");
        ReportSource source = source(owner, interview, "QUESTION_BANK", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 1, null, "source-q-1", "Explain JVM.", "The JVM runs bytecode.", Instant.now())));
        ReportRepository repo = queued(task, source);
        ReportService service = new ReportService(repo, model(validReport(turnId, "E001"), null), json);

        assertTrue(service.processOne("test-worker", LEASE));

        var content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).saveReport(eq(task), any(UUID.class), eq("1.4.0"), content.capture(), anyString(), anyLong(), eq("READY"));
        JsonNode result = json.readTree(content.getValue());
        assertEquals("1.4.0", result.path("schemaVersion").asString());
        assertEquals("MODEL_SUPPORTED", result.path("summary").path("overallReviewEvidenceStatus").asString());
        assertEquals("NOT_APPLICABLE", result.path("scores").path("JOB_MATCH").path("status").asString());
        assertTrue(result.path("scores").path("CULTURE_MATCH").isMissingNode());
        assertTrue(result.path("summary").path("oneLineConclusion").isMissingNode());
        assertEquals("NOT_APPLICABLE", result.path("gapAnalysis").path("status").asString());
        assertEquals("The JVM runs bytecode.", result.path("turns").get(0).path("answer").asString());
        assertEquals("The JVM runs bytecode.", result.path("turns").get(0).path("evidence").get(0).path("quote").asString());
        assertEquals("E001", result.path("summary").path("overallReviewEvidence").get(0).path("evidenceId").asString());
        assertEquals(80, result.path("summary").path("overallScore").asInt());

    }

    @Test
    void reportWithoutAnswersMarksSummaryVerificationNotApplicable() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID();
        ReportTask task = task(interview, owner, "REPORT");
        ReportSource source = source(owner, interview, "QUESTION_BANK", null, List.of());
        ReportRepository repo = queued(task, source);
        ReportService service = new ReportService(repo, model(null, null), json);

        assertTrue(service.processOne("test-worker", LEASE));

        var content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).saveReport(eq(task), any(UUID.class), eq("1.4.0"), content.capture(), anyString(),
                anyLong(), eq("PARTIAL"));
        JsonNode report = json.readTree(content.getValue());
        assertEquals("NOT_APPLICABLE", report.path("summary").path("overallReviewEvidenceStatus").asString());
        assertTrue(report.path("summary").path("oneLineConclusion").isMissingNode());
        assertEquals("not-run", report.path("generationMeta").path("summaryEvidenceVerifierVersion").asString());
    }

    @Test
    void invalidEvidenceIdFailsTheDurableTaskWithoutPersistingAReport() {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportTask task = task(interview, owner, "REPORT");
        ReportSource source = source(owner, interview, "COMPREHENSIVE", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 1, null, "source-q-1", "Question?", "Answer.", Instant.now())));
        ReportRepository repo = queued(task, source);
        ReportService service = new ReportService(repo, model(validReport(turnId, "E999"), null), json);

        assertTrue(service.processOne("test-worker", LEASE));

        verify(repo, never()).saveReport(any(), any(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(repo).fail(eq(task), eq("REPORT_OUTPUT_INVALID"), anyString(), anyLong());
    }

    @Test
    void reportPreservesSourceQuestionAndFollowupParentIds() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), mainId = UUID.randomUUID(), followupId = UUID.randomUUID();
        ReportSource source = source(owner, interview, "QUESTION_BANK", null, List.of(
                new ReportSource.SourceTurn(mainId, 1, "MAIN", 0, null, "bank-question-17", "What is a JVM?", "It runs bytecode.", Instant.now()),
                new ReportSource.SourceTurn(followupId, 2, "FOLLOW_UP", 0, 1, null, "How does it run bytecode?", "Through the execution engine.", Instant.now())));
        EvidenceCatalog catalog = new EvidenceCatalog(source);
        ReportOutput template = validReport(mainId, "E001");
        ReportOutput output = new ReportOutput(template.overallReview(), template.overallScore(),
                template.scores(), List.of(
                        new TurnOutput(mainId.toString(), "说明了 JVM 的基本作用。", List.of(), List.of(), List.of("E001")),
                        new TurnOutput(followupId.toString(), "补充了执行过程。", List.of(), List.of(), List.of("E002"))),
                template.strengths(), template.risks(), template.recommendations(), template.learningPath(),
                List.of("E002"));
        ReportService service = new ReportService(mock(ReportRepository.class), model(output, null), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source, catalog, output, 5));

        assertEquals("bank-question-17", report.path("turns").get(0).path("questionId").asString());
        assertTrue(report.path("turns").get(0).path("parentTurnId").isNull());
        assertEquals(mainId.toString(), report.path("turns").get(1).path("parentTurnId").asString());
        assertEquals("E002", report.path("summary").path("overallReviewEvidence").get(0).path("evidenceId").asString());
        assertTrue(report.path("summary").path("oneLineConclusion").isMissingNode());
        assertEquals("E002", report.path("summary").path("evidence").get(0).path("evidenceId").asString());
    }

    @Test
    void unknownVerifierCitationHidesUnverifiedOverallReview() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportSource source = source(owner, interview, "QUESTION_BANK", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "Answer.", Instant.now())));
        ReportOutput template = validReport(turnId, "E001");
        ReportOutput invalidSummary = new ReportOutput(template.overallReview(), template.overallScore(),
                template.scores(), template.turns(), template.strengths(), template.risks(), template.recommendations(), template.learningPath(),
                List.of("E999"));
        ReportService service = new ReportService(mock(ReportRepository.class), model(invalidSummary, null), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source,
                new EvidenceCatalog(source), invalidSummary, 1));
        assertEquals("UNVERIFIED", report.path("summary").path("overallReviewEvidenceStatus").asString());
        assertTrue(report.path("summary").path("overallReview").asString().contains("系统未能确认总体评价"));
        assertFalse(report.path("summary").path("overallReview").asString().contains(invalidSummary.overallReview()));
        assertEquals(0, report.path("summary").path("overallReviewEvidence").size());
    }

    @Test
    void unsupportedSummaryClaimIsReplacedEvenWhenItsOriginalCitationWasValid() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportSource source = source(owner, interview, "QUESTION_BANK", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "I may try Redis someday.", Instant.now())));
        ReportOutput candidate = validReport(turnId, "E001");
        var review = new ReportModel.SummaryEvidenceReview(false, List.of());
        ReportService service = new ReportService(mock(ReportRepository.class), model(candidate, null, review), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source,
                new EvidenceCatalog(source), candidate, 1));

        assertEquals("UNVERIFIED", report.path("summary").path("overallReviewEvidenceStatus").asString());
        assertTrue(report.path("summary").path("overallReview").asString().contains("系统未能确认总体评价"));
        assertEquals(0, report.path("summary").path("overallReviewEvidence").size());
        assertFalse(report.path("summary").path("overallReview").asString().contains("基础实践能力"));
        assertTrue(report.path("summary").path("oneLineConclusion").isMissingNode());
    }

    @Test
    void verifierCanSelectARelevantCitationDifferentFromGeneratorSuggestion() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        String answer = "A".repeat(410) + "关键回答证据。" + "B".repeat(100);
        ReportSource source = source(owner, interview, "QUESTION_BANK", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", answer, Instant.now())));
        ReportOutput candidate = validReport(turnId, "E001");
        var review = new ReportModel.SummaryEvidenceReview(true, List.of("E002"));
        ReportService service = new ReportService(mock(ReportRepository.class), model(candidate, null, review), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source,
                new EvidenceCatalog(source), candidate, 1));

        assertEquals("E002", report.path("summary").path("overallReviewEvidence").get(0).path("evidenceId").asString());
        assertTrue(report.path("summary").path("overallReviewEvidence").get(0).path("quote").asString().contains("关键回答证据。"));
    }

    @Test
    void verifierFailureFailsClosedWithoutQuotingRawAnswer() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportSource source = source(owner, interview, "QUESTION_BANK", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "原始回答内容。", Instant.now())));
        ReportOutput candidate = validReport(turnId, "E001");
        ReportModel brokenVerifier = model(candidate, null, null, true);
        ReportService service = new ReportService(mock(ReportRepository.class), brokenVerifier, json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source,
                new EvidenceCatalog(source), candidate, 1));

        assertEquals("UNVERIFIED", report.path("summary").path("overallReviewEvidenceStatus").asString());
        assertTrue(report.path("summary").path("overallReview").asString().contains("系统未能确认总体评价"));
        assertFalse(report.path("summary").path("overallReview").asString().contains("原始回答内容。"));
    }

    @Test
    void missingModelDimensionIsSafelyCompletedAsUnassessed() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportSource source = source(owner, interview, "COMPREHENSIVE", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "Answer.", Instant.now())));
        EvidenceCatalog catalog = new EvidenceCatalog(source);
        ReportOutput template = validReport(turnId, "E001");
        List<DimensionOutput> incomplete = template.scores().stream().filter(score -> !"LOGICAL_STRUCTURE".equals(score.key())).toList();
        ReportOutput output = new ReportOutput(template.overallReview(), template.overallScore(),
                incomplete, template.turns(), template.strengths(), template.risks(), template.recommendations(), template.learningPath(),
                template.overallReviewEvidenceIds());
        ReportService service = new ReportService(mock(ReportRepository.class), model(output, null), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source, catalog, output, 5));

        assertEquals("UNASSESSED", report.path("scores").path("LOGICAL_STRUCTURE").path("status").asString());
        assertTrue(report.path("scores").path("LOGICAL_STRUCTURE").path("value").isNull());
        assertEquals("ASSESSED", report.path("scores").path("TECHNICAL_DEPTH").path("status").asString());
    }

    @Test
    void outOfRangeModelScoreIsDiscardedAndDimensionBecomesUnassessed() throws Exception {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportSource source = source(owner, interview, "COMPREHENSIVE", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "Answer.", Instant.now())));
        EvidenceCatalog catalog = new EvidenceCatalog(source);
        ReportOutput template = validReport(turnId, "E001");
        List<DimensionOutput> adjusted = template.scores().stream().map(score ->
                "TECHNICAL_DEPTH".equals(score.key())
                        ? new DimensionOutput(score.key(), score.status(), 6, score.rationale(), score.evidenceIds())
                        : score).toList();
        ReportOutput output = new ReportOutput(template.overallReview(), template.overallScore(),
                adjusted, template.turns(), template.strengths(), template.risks(), template.recommendations(), template.learningPath(),
                template.overallReviewEvidenceIds());
        ReportService service = new ReportService(mock(ReportRepository.class), model(output, null), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source, catalog, output, 5));

        assertEquals("UNASSESSED", report.path("scores").path("TECHNICAL_DEPTH").path("status").asString());
        assertTrue(report.path("scores").path("TECHNICAL_DEPTH").path("value").isNull());
        assertTrue(report.path("scores").path("TECHNICAL_DEPTH").path("evidence").isEmpty());
        assertEquals("ASSESSED", report.path("scores").path("PROJECT_EXPERIENCE").path("status").asString());
    }

    @Test
    void recommendationSupportedOnlyByResumeIsOmittedRatherThanPresentedAsAnswerBased() {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        var snapshot = json.createObjectNode();
        snapshot.set("resume", json.createObjectNode().put("id", "resume-1")
                .set("content", json.createObjectNode().put("targetRole", "Java 后端开发")));
        ReportSource source = new ReportSource(interview, owner, "COMPREHENSIVE", "COMPLETE", "Resume evidence test",
                "USER_ENDED", "TEST", "test-model", "需要 Java 项目经验", snapshot, Instant.now(),
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "Answer.", Instant.now())));
        EvidenceCatalog catalog = new EvidenceCatalog(source);
        ReportOutput template = validReport(turnId, "E001");
        ReportOutput output = new ReportOutput(template.overallReview(), null,
                template.scores(), template.turns(), template.strengths(), template.risks(),
                List.of(new RecommendationOutput("练习介绍目标岗位", "简历显示目标岗位为 Java 后端开发。", List.of("E003"))),
                template.learningPath(), template.overallReviewEvidenceIds());
        ReportService service = new ReportService(mock(ReportRepository.class), model(output, null), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source, catalog, output, 5));

        assertTrue(report.path("recommendations").isArray());
        assertTrue(report.path("recommendations").isEmpty());
    }

    @Test
    void modelCannotMarkAnApplicableDimensionNotApplicable() {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), turnId = UUID.randomUUID();
        ReportSource source = source(owner, interview, "COMPREHENSIVE", null,
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "Answer.", Instant.now())));
        EvidenceCatalog catalog = new EvidenceCatalog(source);
        ReportOutput template = validReport(turnId, "E001");
        List<DimensionOutput> adjusted = template.scores().stream().map(score ->
                "TECHNICAL_DEPTH".equals(score.key())
                        ? new DimensionOutput(score.key(), "NOT_APPLICABLE", 4, score.rationale(), score.evidenceIds())
                        : score).toList();
        ReportOutput output = new ReportOutput(template.overallReview(), null,
                adjusted, template.turns(), template.strengths(), template.risks(), template.recommendations(), template.learningPath(),
                template.overallReviewEvidenceIds());
        ReportService service = new ReportService(mock(ReportRepository.class), model(output, null), json);

        JsonNode report = json.valueToTree(service.assembleReport(UUID.randomUUID(), source, catalog, output, 5));

        assertEquals("UNASSESSED", report.path("scores").path("TECHNICAL_DEPTH").path("status").asString());
        assertTrue(report.path("scores").path("TECHNICAL_DEPTH").path("value").isNull());
    }

    @Test
    void workerRefusesLegacyGapTasksWithoutCallingTheModel() {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID();
        ReportTask task = task(interview, owner, "GAP_ANALYSIS");
        ReportSource source = source(owner, interview, "COMPREHENSIVE", "需要 Java 项目经验", List.of());
        ReportRepository repo = queued(task, source);
        ReportModel model = mock(ReportModel.class);
        ReportService service = new ReportService(repo, model, json);

        assertTrue(service.processOne("test-worker", LEASE));

        verify(repo).fail(eq(task), eq("GAP_ANALYSIS_DISABLED"), eq("岗位差异分析功能已停用。"), anyLong());
        verify(model, never()).generateGapAnalysis(anyString());
    }
    @Test
    void gapEndpointReturnsGoneForEveryInterviewMode() {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), reportId = UUID.randomUUID();
        ReportRepository repo = mock(ReportRepository.class);
        ReportRow row = new ReportRow(reportId, interview, owner, "1.1.0", "READY", "{}", "{}", Instant.now(), Instant.now(), 0);
        when(repo.findReport(owner, reportId)).thenReturn(Optional.of(row));
        ReportService service = new ReportService(repo, model(null, null), json);

        ApiException error = assertThrows(ApiException.class, () -> service.getGap(owner, reportId));
        assertEquals(HttpStatus.GONE, error.status());
    }

    @Test
    void legacyGapEndpointIsDisabled() {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), reportId = UUID.randomUUID();
        ReportRepository repo = mock(ReportRepository.class);
        ReportRow report = new ReportRow(reportId, interview, owner, "1.2.0", "READY", "{}", "{}",
                Instant.now(), Instant.now(), 0);
        when(repo.findReport(owner, reportId)).thenReturn(Optional.of(report));
        ReportService service = new ReportService(repo, model(null, null), json);

        ApiException error = assertThrows(ApiException.class, () -> service.getGap(owner, reportId));

        assertEquals(HttpStatus.GONE, error.status());
        verify(repo, never()).findGap(owner, reportId);
    }
    @Test
    void resumeStatusUsesOnlyResumeEvidenceBackedScore() {
        UUID owner = UUID.randomUUID(), interview = UUID.randomUUID(), reportId = UUID.randomUUID(), turnId = UUID.randomUUID();
        var snapshot = json.createObjectNode();
        snapshot.set("resume", json.createObjectNode().put("id", "resume-1")
                .set("content", json.createObjectNode().put("targetRole", "Java 后端开发")));
        ReportSource source = new ReportSource(interview, owner, "COMPREHENSIVE", "COMPLETE", "Resume evidence test",
                "TARGET_REACHED", "TEST", "test-model", "需要 Java 项目经验", snapshot, Instant.now(),
                List.of(new ReportSource.SourceTurn(turnId, 1, "MAIN", 0, null, "q1", "Question?", "Answer.", Instant.now())));
        EvidenceCatalog catalog = new EvidenceCatalog(source);
        JsonNode result = json.valueToTree(new ReportService(mock(ReportRepository.class), model(null, null), json)
                .assembleGap(UUID.randomUUID(), reportId, source, catalog, new GapOutput(List.of(
                        new RequirementOutput("Java 项目经验", "CORE", 4d, 4d, .9, "有相关回答。", "补充量化成果。", List.of("E001", "E002"))))));

        JsonNode requirement = result.path("requirements").get(0);
        assertTrue(requirement.path("resumeScore").isNull());
        assertEquals("NO_EVIDENCE", requirement.path("resumeStatus").asString());
        assertEquals("DEMONSTRATED", requirement.path("interviewStatus").asString());
    }

    private ReportRepository queued(ReportTask task, ReportSource source) {
        ReportRepository repo = mock(ReportRepository.class);
        when(repo.recoverExpired(any())).thenReturn(0);
        when(repo.staleCompleteInterviewsWithoutTask()).thenReturn(List.of());
        when(repo.claimNext("test-worker", LEASE)).thenReturn(Optional.of(task));
        when(repo.loadSource(task.ownerId(), task.interviewId())).thenReturn(source);
        when(repo.displayTitle(task.ownerId(), task.interviewId())).thenReturn(source.title());
        return repo;
    }

    private ReportSource source(UUID owner, UUID interview, String mode, String jd, List<ReportSource.SourceTurn> turns) {
        var snapshot = json.createObjectNode();
        return new ReportSource(interview, owner, mode, "COMPLETE", "Report test", "USER_ENDED", "TEST", "test-model",
                jd, snapshot, Instant.now(), turns);
    }

    private static ReportTask task(UUID interview, UUID owner, String type) {
        return new ReportTask(UUID.randomUUID(), interview, owner, type, "PROCESSING", 0, 1, null, null,
                Instant.now(), Instant.now(), null, null, "test-worker", Instant.now().plus(LEASE));
    }

    private static ReportOutput validReport(UUID turnId, String evidenceId) {
        var scores = List.of("TECHNICAL_DEPTH", "PROJECT_EXPERIENCE", "JOB_MATCH", "COMMUNICATION",
                "LOGICAL_STRUCTURE", "PROBLEM_SOLVING").stream()
                .map(key -> new DimensionOutput(key, "ASSESSED", 4, "回答提供了可追溯证据。", List.of(evidenceId))).toList();
        return new ReportOutput("回答体现了基础实践能力，但覆盖范围有限。", null,
                scores, List.of(new TurnOutput(turnId.toString(), "回答能说明基本概念。", List.of("提到了 JVM"),
                        List.of("补充运行时细节"), List.of(evidenceId))),
                List.of(new InsightOutput("能解释 JVM 的基本作用。", List.of(evidenceId))),
                List.of(new InsightOutput("运行时结构细节覆盖不足。", List.of(evidenceId))),
                List.of(new RecommendationOutput("练习说明 JVM 运行流程。", "当前回答未展开运行时组成。", List.of(evidenceId))),
                List.of(new LearningOutput(1, "理解 JVM 运行流程", List.of("画出类加载到执行的流程图。"), List.of(evidenceId))),
                List.of(evidenceId));
    }

    private static ReportModel model(ReportOutput report, GapOutput gap) { return model(report, gap, null, false); }

    private static ReportModel model(ReportOutput report, GapOutput gap, ReportModel.SummaryEvidenceReview review) {
        return model(report, gap, review, false);
    }

    private static ReportModel model(ReportOutput report, GapOutput gap, ReportModel.SummaryEvidenceReview review,
            boolean failVerification) {
        return new ReportModel() {
            @Override public ReportOutput generateReport(String context) { return report; }
            @Override public ReportModel.SummaryEvidenceReview verifySummaryEvidence(String context) {
                if (failVerification) throw new IllegalStateException("verification unavailable");
                if (review != null) return review;
                if (report == null) return null;
                return new ReportModel.SummaryEvidenceReview(true, report.overallReviewEvidenceIds());
            }
            @Override public GapOutput generateGapAnalysis(String context) { return gap; }
            @Override public boolean isConfigured() { return true; }
            @Override public String provider() { return "TEST"; }
            @Override public String modelId() { return "test-report-model"; }
        };
    }
}
