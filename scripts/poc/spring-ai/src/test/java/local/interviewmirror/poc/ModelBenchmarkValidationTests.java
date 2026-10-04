package local.interviewmirror.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ModelBenchmarkValidationTests {

    private static final ModelBenchmarkApplication.BenchmarkCase CASE =
            new ModelBenchmarkApplication.BenchmarkCase("M01", "COMPREHENSIVE", "PROJECT_EXPERIENCE",
                    "How did you improve latency?", "I reduced latency by 30% using a cache.", List.of(), true,
                    null, null, null);

    @Test
    void assessedScoreIsNormalizedToIntegerAndEvidenceMustBeQuoted() {
        var wire = new ModelBenchmarkApplication.EvaluationWire("ASSESSED", "4", "Clear measured result.",
                List.of("reduced latency by 30%"), false, "");

        var normalized = ModelBenchmarkApplication.normalizeEvaluation(wire);

        assertEquals(4, normalized.score());
        assertTrue(ModelBenchmarkApplication.evaluationValidationErrors(CASE, wire).isEmpty());
    }

    @Test
    void unassessedSentinelNormalizesToNullWithoutJsonSchemaNullType() {
        var wire = new ModelBenchmarkApplication.EvaluationWire("UNASSESSED", "UNASSESSED",
                "The answer does not contain evidence for this dimension.", List.of(), false, "");

        var normalized = ModelBenchmarkApplication.normalizeEvaluation(wire);

        assertNull(normalized.score());
        assertTrue(ModelBenchmarkApplication.evaluationValidationErrors(CASE, wire).isEmpty());
    }

    @Test
    void scoreStatusMismatchIsReportedWithStableCode() {
        var wire = new ModelBenchmarkApplication.EvaluationWire("ASSESSED", "UNASSESSED", "Reason.",
                List.of("reduced latency by 30%"), false, "");

        assertTrue(ModelBenchmarkApplication.evaluationValidationErrors(CASE, wire)
                .contains("EVALUATION_SCORE_STATUS_MISMATCH"));
    }

    @Test
    void unsupportedEvidenceIsReportedWithStableCode() {
        var wire = new ModelBenchmarkApplication.EvaluationWire("ASSESSED", "4", "Reason.",
                List.of("I led a team of 20"), false, "");

        assertTrue(ModelBenchmarkApplication.evaluationValidationErrors(CASE, wire)
                .contains("EVALUATION_EVIDENCE_NOT_IN_ANSWER"));
    }

    @Test
    void requiredFollowUpMustIncludeQuestion() {
        var wire = new ModelBenchmarkApplication.EvaluationWire("ASSESSED", "3", "Reason.",
                List.of("reduced latency by 30%"), true, "");

        assertTrue(ModelBenchmarkApplication.evaluationValidationErrors(CASE, wire)
                .contains("EVALUATION_FOLLOW_UP_MISSING"));
    }

    @Test
    void phase3FollowupRejectsMissingRationaleQuestionAndRepeatedPrompt() {
        assertTrue(ModelBenchmarkApplication.phase3FollowupValidationErrors("原问题", "原问题",
                new ModelBenchmarkApplication.RuntimeFollowupWire(true, "原因", "  "))
                .contains("FOLLOWUP_QUESTION_MISSING"));
        assertTrue(ModelBenchmarkApplication.phase3FollowupValidationErrors("原问题", "原问题",
                new ModelBenchmarkApplication.RuntimeFollowupWire(true, "", "新追问"))
                .contains("FOLLOWUP_RATIONALE_MISSING"));
        assertTrue(ModelBenchmarkApplication.phase3FollowupValidationErrors("你如何验证？", "你如何验证？",
                new ModelBenchmarkApplication.RuntimeFollowupWire(true, "原因", "你如何验证？"))
                .contains("FOLLOWUP_QUESTION_REPEATS_CONTEXT"));
        assertTrue(ModelBenchmarkApplication.phase3FollowupValidationErrors("你如何验证？", "验证效果用什么指标？",
                new ModelBenchmarkApplication.RuntimeFollowupWire(true, "原因", "你如何验证？"))
                .contains("FOLLOWUP_QUESTION_REPEATS_CONTEXT"));
    }

    @Test
    void phase3FollowupAllowsMoveOnAndBoundsQuestionLength() {
        assertTrue(ModelBenchmarkApplication.phase3FollowupValidationErrors("原问题", "原问题",
                new ModelBenchmarkApplication.RuntimeFollowupWire(false, "回答充分", "" )).isEmpty());
        assertTrue(ModelBenchmarkApplication.phase3FollowupValidationErrors("原问题", "原问题",
                new ModelBenchmarkApplication.RuntimeFollowupWire(true, "原因", "追问".repeat(301)))
                .contains("FOLLOWUP_QUESTION_TOO_LONG"));
    }

    @Test
    void phase3FollowupPromptCarriesMainQuestionLatestQuestionAndAnswer() {
        String prompt = ModelBenchmarkApplication.phase3FollowupPrompt("主问题", "追问", "候选回答");
        assertTrue(prompt.contains("Prompt-Version=phase3.followup.v1"));
        assertTrue(prompt.contains("当前主问题：\n主问题"));
        assertTrue(prompt.contains("最近一条面试官提问：\n追问"));
        assertTrue(prompt.contains("候选人回答：\n候选回答"));
    }

    @Test
    void reportEvidenceMustComeFromCandidateAnswer() {
        var report = new ModelBenchmarkApplication.ReportDraft("Some review.", List.of("A strength."),
                List.of("A risk."), List.of("Improve measurement."), List.of("Study profiling."),
                List.of("I led a team of 20"));

        assertFalse(ModelBenchmarkApplication.reportValidationErrors(CASE, report).isEmpty());
        assertTrue(ModelBenchmarkApplication.reportValidationErrors(CASE, report)
                .contains("REPORT_EVIDENCE_NOT_IN_ANSWER"));
    }

    @Test
    void failureSummaryIncludesSafeIoCauseForTimeoutDiagnosis() {
        var failure = new RuntimeException("Request failed", new SocketTimeoutException("Read timed out"));

        String summary = ModelBenchmarkApplication.safeMessage(failure);

        assertTrue(summary.contains("RuntimeException: Request failed"));
        assertTrue(summary.contains("SocketTimeoutException: Read timed out"));
    }

    @Test
    void failureSummaryRedactsProviderApiCredentials() {
        var failure = new IOException("Authorization: Bearer sk-test-placeholder");

        String summary = ModelBenchmarkApplication.safeMessage(failure);

        assertFalse(summary.contains("sk-test-placeholder"));
        assertTrue(summary.contains("[REDACTED]"));
    }

    @Test
    void chatClientRequestTimeoutCanOverrideSpringAiOptionsDefault() {
        var options = ModelBenchmarkApplication.requestTimeoutOptions(Duration.ofSeconds(90)).build();

        assertEquals(Duration.ofSeconds(90), options.getTimeout());
    }

    @Test
    void reasoningEffortIsExplicitlyAppliedToChatOptions() {
        var options = ModelBenchmarkApplication.requestTimeoutOptions(Duration.ofSeconds(60), "low").build();

        assertEquals(Duration.ofSeconds(60), options.getTimeout());
        assertEquals("low", options.getReasoningEffort());
    }

    @Test
    void providerDefaultLeavesReasoningEffortUnset() {
        var options = ModelBenchmarkApplication.requestTimeoutOptions(Duration.ofSeconds(60), "default").build();

        assertNull(options.getReasoningEffort());
    }

    @Test
    void unsupportedReasoningEffortIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ModelBenchmarkApplication.requestTimeoutOptions(Duration.ofSeconds(60), "medium"));
    }

    @Test
    void benchmarkCaseCanOmitReportOnlyAssessmentFlag() throws Exception {
        String json = """
                {"schemaVersion":"interviewmirror.followup-benchmark.v1.0.0",
                 "promptVersion":"interview-followup.v1.0.0",
                 "cases":[{"id":"FU01","mode":"COMPREHENSIVE","dimension":"TECHNICAL_DEPTH",
                   "question":"How did you validate it?","answer":"We used a fixed test set.",
                   "expectedSignals":[],"context":"JD requires evaluation discipline.",
                   "expectedAction":"ASK","probeObjective":"Ask for dataset details."}]}
                """;

        var dataset = JsonMapper.builder().build().readValue(json, ModelBenchmarkApplication.BenchmarkDataset.class);

        assertEquals(1, dataset.cases().size());
        assertNull(dataset.cases().get(0).shouldAssess());
    }
}
