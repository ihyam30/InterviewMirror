package local.interviewmirror.backend.reports;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final String REPORT_SCHEMA_VERSION = "1.4.0";
    private static final String REPORT_PROMPT_VERSION = "phase4.report.v1.7+phase4.dimension-review.v1+phase4.summary-evidence.v1.2";
    private static final String SUMMARY_EVIDENCE_VERIFIER_VERSION = "phase4.summary-evidence.v1.2";
    private static final String NOT_EVALUATED_TEXT = "本场没有足够的直接面试证据，无法评估此项。";
    private static final String[] DIMENSIONS = {"TECHNICAL_DEPTH", "PROJECT_EXPERIENCE", "JOB_MATCH",
            "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING"};
    private static final JsonNode JSON_NULL = NullNode.getInstance();
    private final ReportRepository reports;
    private final ReportModel model;
    private final ObjectMapper json;

    public ReportService(ReportRepository reports, ReportModel model, ObjectMapper json) {
        this.reports = reports; this.model = model; this.json = json;
    }

    public ReportRepository.ReportTask request(UUID ownerId, UUID interviewId) {
        return reports.ensureReportTask(ownerId, interviewId);
    }
    public ReportStatus status(UUID ownerId, UUID interviewId) {
        ReportSource source = reports.loadSource(ownerId, interviewId);
        if (!"COMPLETE".equals(source.status())) throw new ApiException(HttpStatus.CONFLICT,
                "INTERVIEW_NOT_COMPLETE", "面试结束后才能查看报告状态。");
        ReportTask reportTask = reports.task(ownerId, interviewId, "REPORT").orElseGet(() -> reports.ensureReportTask(ownerId, interviewId));
        if ("FAILED".equals(reportTask.status())) {
            reportTask = withVisibleFailureMessage(reportTask);
        }
        ReportRow report = reports.reportForInterview(ownerId, interviewId).orElse(null);
        return new ReportStatus(interviewId, reportTask, report == null ? null : report.id(),
                "NOT_APPLICABLE", null, null);
    }
    public List<ReportSummary> list(UUID ownerId) {
        return reports.listReportHistory(ownerId).stream().map(this::summary).toList();
    }
    public ReportDetail get(UUID ownerId, UUID reportId) {
        ReportRow row = reports.findReport(ownerId, reportId).orElseThrow(ReportService::notFound);
        return detail(row);
    }
    public GapDetail getGap(UUID ownerId, UUID reportId) {
        reports.findReport(ownerId, reportId).orElseThrow(ReportService::notFound);
        throw new ApiException(HttpStatus.GONE, "GAP_ANALYSIS_DISABLED", "岗位差异分析功能已停用。");
    }
    public ReportTask retry(UUID ownerId, UUID interviewId) { return reports.retryReportForInterview(ownerId, interviewId); }
    public void retryGap(UUID ownerId, UUID reportId) {
        throw new ApiException(HttpStatus.GONE, "GAP_ANALYSIS_DISABLED", "岗位差异分析功能已停用。");
    }

    public boolean processOne(String workerId, Duration lease) {
        reports.recoverExpired(Instant.now());
        List<ReportTask> recover = reports.staleCompleteInterviewsWithoutTask();
        if (!recover.isEmpty()) reports.enqueueRecoveredInterviews(recover);
        var claimed = reports.claimNext(workerId, lease);
        if (claimed.isEmpty()) return false;
        ReportTask task = claimed.get();
        long started = System.nanoTime();
        try {
            ReportSource source = reports.loadSource(task.ownerId(), task.interviewId());
            if (!"COMPLETE".equals(source.status())) throw new IllegalArgumentException("report source is not complete");
            if (!"REPORT".equals(task.type())) {
                reports.fail(task, "GAP_ANALYSIS_DISABLED", "岗位差异分析功能已停用。", elapsed(started));
                return true;
            }
            generateReport(task, source, started);
        } catch (Exception failure) {
            long duration = elapsed(started);
            String code = failureCode(failure);
            String message = taskFailureMessage(task.type(), code);
            String validationReason = failure instanceof IllegalArgumentException
                    ? safeValidationReason(failure.getMessage()) : "not_applicable";
            log.warn("report_task={} interview={} owner={} type={} status=FAILED failure_type={} validation_reason={} duration_ms={} retry_count={}",
                    task.id(), task.interviewId(), task.ownerId(), task.type(), failure.getClass().getSimpleName(),
                    validationReason, duration, task.retryCount());
            try { reports.fail(task, code, message, duration); }
            catch (RuntimeException stale) { log.warn("report_task={} stale_failure_ignored", task.id()); }
        }
        return true;
    }

    private void generateReport(ReportTask task, ReportSource source, long started) throws Exception {
        UUID reportId = reports.reportForInterview(task.ownerId(), task.interviewId())
                .map(ReportRow::id).orElseGet(UUID::randomUUID);
        String title = reports.displayTitle(task.ownerId(), task.interviewId());
        if (source.turns().isEmpty()) {
            Map<String, Object> content = emptyReport(reportId, source, title);
            reports.saveReport(task, reportId, REPORT_SCHEMA_VERSION, json.writeValueAsString(content), json.writeValueAsString(source.sourceSnapshot()),
                    elapsed(started), "PARTIAL");
            return;
        }
        EvidenceCatalog catalog = new EvidenceCatalog(source);
        String context = reportPromptContext(source, catalog);
        ReportOutput output = model.generateReport(context);
        output = reviewUnassessedDimensions(source, catalog, output, context, reportId);
        Map<String, Object> content = assembleReport(reportId, source, catalog, output, elapsed(started), title);
        content.put("gapAnalysis", disabledGapState());
        reports.saveReport(task, reportId, REPORT_SCHEMA_VERSION, json.writeValueAsString(content), json.writeValueAsString(source.sourceSnapshot()),
                elapsed(started), "READY");
        log.info("report_task={} interview={} owner={} type=REPORT status=SUCCESS model={} duration_ms={} answers={}",
                task.id(), source.interviewId(), source.ownerId(), model.modelId(), elapsed(started), source.turns().size());
    }

    Map<String, Object> assembleReport(UUID reportId, ReportSource source, EvidenceCatalog catalog,
            ReportOutput output, long elapsed) {
        return assembleReport(reportId, source, catalog, output, elapsed, source.title());
    }

    private Map<String, Object> assembleReport(UUID reportId, ReportSource source, EvidenceCatalog catalog,
            ReportOutput output, long elapsed, String reportTitle) {
        if (output == null) throw invalid("report output missing");
        requireText(output == null ? null : output.overallReview(), "overall review");
        if (output.overallScore() != null && (output.overallScore() < 0 || output.overallScore() > 100)) throw invalid("overall score outside 0..100");
        Map<String, ReportModel.DimensionOutput> rawScores = new LinkedHashMap<>();
        if (output.scores() != null) for (DimensionOutput score : output.scores()) {
            if (score == null || score.key() == null || rawScores.put(score.key(), score) != null) throw invalid("duplicate or invalid dimension");
        }
        if (rawScores.keySet().stream().anyMatch(key -> !List.of(DIMENSIONS).contains(key))) throw invalid("unknown scoring dimension");
        Map<String, Object> scores = new LinkedHashMap<>();
        int assessed = 0, scoreTotal = 0;
        for (String key : DIMENSIONS) {
            DimensionOutput raw = rawScores.get(key);
            if (raw == null) {
                log.warn("report_dimension_unassessed report={} interview={} key={} reason=MISSING_MODEL_DIMENSION",
                        reportId, source.interviewId(), key);
                scores.put(key, Map.of("status", "UNASSESSED", "value", JSON_NULL,
                        "rationale", "模型未返回该维度评估，当前报告未完成此项评分。", "evidence", List.of()));
                continue;
            }
            requireText(raw.rationale(), "dimension rationale");
            boolean notApplicable = (key.equals("PROJECT_EXPERIENCE") && "QUESTION_BANK".equals(source.mode()))
                    || (key.equals("JOB_MATCH") && !eligibleForGap(source));
            boolean validValue = raw.value() == null || (raw.value() >= 1 && raw.value() <= 5);
            if (!validValue) log.warn("report_dimension_score_rejected key={} value={} reason=OUT_OF_RANGE", key, raw.value());
            boolean validStatus = raw.status() != null && Set.of("ASSESSED", "UNASSESSED", "NOT_APPLICABLE").contains(raw.status());
            if (!validStatus || ("NOT_APPLICABLE".equals(raw.status()) && !notApplicable))
                log.warn("report_dimension_status_rejected key={} status={} reason=SERVER_APPLICABILITY", key, raw.status());
            List<Map<String, Object>> evidence = refs(catalog, raw.evidenceIds(), null, true);
            boolean hasAnswerEvidence = evidence.stream().anyMatch(item -> "TURN".equals(item.get("sourceType")));
            String status;
            Integer value;
            String unassessedReason = null;
            if (notApplicable) { status = "NOT_APPLICABLE"; value = null; }
            else if (!validStatus) {
                status = "UNASSESSED"; value = null; unassessedReason = "INVALID_MODEL_STATUS";
            } else if ("NOT_APPLICABLE".equals(raw.status())) {
                status = "UNASSESSED"; value = null; unassessedReason = "APPLICABLE_DIMENSION_MARKED_NOT_APPLICABLE";
            } else if (!hasAnswerEvidence) {
                status = "UNASSESSED"; value = null; unassessedReason = "NO_TURN_EVIDENCE";
            } else if (!"ASSESSED".equals(raw.status())) {
                status = "UNASSESSED"; value = null; unassessedReason = "MODEL_MARKED_UNASSESSED";
            } else if (raw.value() == null) {
                status = "UNASSESSED"; value = null; unassessedReason = "MISSING_SCORE";
            } else if (!validValue) {
                status = "UNASSESSED"; value = null; unassessedReason = "SCORE_OUT_OF_RANGE";
            } else {
                status = "ASSESSED"; value = raw.value(); assessed++; scoreTotal += value;
            }
            if (unassessedReason != null) {
                log.warn("report_dimension_unassessed report={} interview={} key={} reason={} model_status={} has_turn_evidence={} has_score={}",
                        reportId, source.interviewId(), key, unassessedReason, raw.status(), hasAnswerEvidence, raw.value() != null);
            }
            String rationale = "UNASSESSED".equals(status)
                    ? unassessedRationale(unassessedReason) : raw.rationale().trim();
            scores.put(key, Map.of("status", status, "value", value == null ? JSON_NULL : value,
                    "rationale", rationale, "evidence", status.equals("ASSESSED") ? evidence : List.of()));
        }
        List<Map<String, Object>> turns = assembleTurnFeedback(source, catalog, output.turns());
        List<Map<String, Object>> strengths = insights(catalog, output.strengths(), "strength");
        List<Map<String, Object>> risks = insights(catalog, output.risks(), "risk");
        List<Map<String, Object>> recommendations = recommendations(catalog, output.recommendations());
        List<Map<String, Object>> learning = learning(catalog, output.learningPath());
        SummaryResolution verifiedSummary = verifySummaryEvidence(source, catalog, output);
        List<Map<String, Object>> overallReviewEvidence = verifiedSummary.overallReview().evidence();
        boolean earlyEnd = "USER_ENDED".equals(source.completionReason());
        int overall = assessed < 4 ? -1 : Math.round((float) scoreTotal / assessed * 20);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("overallScore", overall < 0 ? JSON_NULL : overall);
        summary.put("overallScoreStatus", overall < 0 ? "UNASSESSED" : earlyEnd || assessed < DIMENSIONS.length ? "PARTIAL" : "ASSESSED");
        String earlyNote = earlyEnd ? "本次面试提前结束，部分能力未充分覆盖。" : "";
        summary.put("overallReview", earlyNote.isBlank() ? verifiedSummary.overallReview().text() : earlyNote + verifiedSummary.overallReview().text());
        summary.put("overallReviewEvidenceStatus", verifiedSummary.overallReview().evidenceStatus());
        summary.put("overallReviewEvidence", overallReviewEvidence);
        summary.put("evidence", overallReviewEvidence);
        Map<String, Object> gapState = disabledGapState();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", model.provider()); meta.put("modelId", model.modelId()); meta.put("promptVersion", REPORT_PROMPT_VERSION);
        meta.put("summaryEvidenceVerifierVersion", SUMMARY_EVIDENCE_VERIFIER_VERSION);
        meta.put("schemaVersion", REPORT_SCHEMA_VERSION); meta.put("elapsedMs", elapsed + verifiedSummary.verificationElapsedMs()); meta.put("inputTokens", JSON_NULL);
        meta.put("outputTokens", JSON_NULL); meta.put("costCny", JSON_NULL); meta.put("usageReported", false);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", REPORT_SCHEMA_VERSION); report.put("reportId", reportId.toString());
        report.put("interviewId", source.interviewId().toString()); report.put("ownerId", source.ownerId().toString());
        report.put("title", reportTitle); report.put("mode", source.mode()); report.put("status", "READY");
        report.put("createdAt", Instant.now().toString()); report.put("completedAt", Instant.now().toString());
        report.put("completionReason", source.completionReason()); report.put("sourceSnapshot", sourceSnapshotIds(source));
        report.put("summary", summary); report.put("scores", scores); report.put("turns", turns);
        report.put("strengths", strengths); report.put("risks", risks); report.put("recommendations", recommendations);
        report.put("learningPath", learning); report.put("gapAnalysis", gapState);
        report.put("nextActions", List.of("RESTART", "EXPORT_PDF", "HOME")); report.put("generationMeta", meta);
        return report;
    }

    private ReportOutput reviewUnassessedDimensions(ReportSource source, EvidenceCatalog catalog,
            ReportOutput output, String context, UUID reportId) {
        if (output == null) return null;
        Map<String, DimensionOutput> initial = new LinkedHashMap<>();
        if (output.scores() != null) {
            for (DimensionOutput score : output.scores()) {
                if (score != null && score.key() != null) initial.putIfAbsent(score.key(), score);
            }
        }
        List<String> needsReview = new ArrayList<>();
        for (String key : DIMENSIONS) {
            if (isDimensionNotApplicable(key, source)) continue;
            if (!validDimensionAssessment(catalog, initial.get(key))) needsReview.add(key);
        }
        if (needsReview.isEmpty()) return output;

        ReportModel.DimensionReviewOutput review;
        try {
            review = model.reviewUnassessedDimensions(context, needsReview);
        } catch (RuntimeException failure) {
            log.warn("report_dimension_review report={} interview={} status=UNAVAILABLE failure_type={}",
                    reportId, source.interviewId(), failure.getClass().getSimpleName());
            return output;
        }
        if (review == null || review.scores() == null || review.scores().isEmpty()) {
            log.warn("report_dimension_review report={} interview={} status=EMPTY requested={}",
                    reportId, source.interviewId(), needsReview);
            return output;
        }

        Map<String, DimensionOutput> reviewed = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (DimensionOutput score : review.scores()) {
            if (score == null || score.key() == null || !needsReview.contains(score.key())) continue;
            if (reviewed.putIfAbsent(score.key(), score) != null) duplicates.add(score.key());
        }
        List<String> accepted = new ArrayList<>();
        Map<String, DimensionOutput> acceptedReviews = new LinkedHashMap<>();
        for (String key : needsReview) {
            DimensionOutput candidate = reviewed.get(key);
            if (duplicates.contains(key) || !validDimensionAssessment(catalog, candidate)) continue;
            acceptedReviews.put(key, candidate);
            accepted.add(key);
        }
        log.info("report_dimension_review report={} interview={} status=COMPLETE requested={} accepted={}",
                reportId, source.interviewId(), needsReview, accepted);
        List<DimensionOutput> mergedScores = new ArrayList<>();
        Set<String> insertedReviews = new LinkedHashSet<>();
        if (output.scores() != null) for (DimensionOutput score : output.scores()) {
            if (score != null && acceptedReviews.containsKey(score.key()) && insertedReviews.add(score.key()))
                mergedScores.add(acceptedReviews.get(score.key()));
            else mergedScores.add(score);
        }
        acceptedReviews.forEach((key, score) -> {
            if (insertedReviews.add(key)) mergedScores.add(score);
        });
        return new ReportOutput(output.overallReview(), output.overallScore(), mergedScores,
                output.turns(), output.strengths(), output.risks(), output.recommendations(),
                output.learningPath(), output.overallReviewEvidenceIds());
    }

    private static boolean validDimensionAssessment(EvidenceCatalog catalog, DimensionOutput score) {
        if (score == null || !"ASSESSED".equals(score.status()) || score.value() == null
                || score.value() < 1 || score.value() > 5 || score.rationale() == null
                || score.rationale().isBlank() || score.rationale().length() > 3000
                || score.evidenceIds() == null || score.evidenceIds().isEmpty()) return false;
        boolean hasTurn = false;
        for (String evidenceId : score.evidenceIds()) {
            try {
                if ("TURN".equals(catalog.require(evidenceId).sourceType())) hasTurn = true;
            } catch (IllegalArgumentException unknown) {
                return false;
            }
        }
        return hasTurn;
    }

    private static boolean isDimensionNotApplicable(String key, ReportSource source) {
        return ("PROJECT_EXPERIENCE".equals(key) && "QUESTION_BANK".equals(source.mode()))
                || ("JOB_MATCH".equals(key) && !eligibleForGap(source));
    }

    private List<Map<String, Object>> assembleTurnFeedback(ReportSource source, EvidenceCatalog catalog, List<TurnOutput> outputs) {
        Map<UUID, TurnOutput> map = new LinkedHashMap<>();
        if (outputs != null) for (TurnOutput output : outputs) {
            if (output == null) continue;
            UUID id;
            try { id = UUID.fromString(output.turnId()); } catch (Exception e) { throw invalid("invalid turn ID"); }
            if (map.put(id, output) != null) throw invalid("duplicate turn feedback");
        }
        Map<Integer, UUID> mainTurnIds = new LinkedHashMap<>();
        for (ReportSource.SourceTurn turn : source.turns()) {
            if ("MAIN".equals(turn.type())) mainTurnIds.putIfAbsent(turn.mainQuestionIndex(), turn.id());
        }
        List<Map<String,Object>> result = new ArrayList<>();
        for (ReportSource.SourceTurn turn : source.turns()) {
            TurnOutput output = map.get(turn.id());
            if (output == null || safeText(output.feedback(), "").isBlank()) throw invalid("answered turn feedback missing");
            List<Map<String,Object>> evidence = refs(catalog, output.evidenceIds(), turn.id().toString(), true);
            if (evidence.isEmpty()) throw invalid("turn feedback has no turn evidence");
            UUID parentTurnId = "FOLLOW_UP".equals(turn.type()) ? mainTurnIds.get(turn.mainQuestionIndex()) : null;
            if ("FOLLOW_UP".equals(turn.type()) && parentTurnId == null) throw invalid("follow-up turn has no main-question parent");
            String questionId = turn.sourceQuestionId() == null || turn.sourceQuestionId().isBlank()
                    ? turn.id().toString() : turn.sourceQuestionId();
            result.add(Map.of("turnId", turn.id().toString(), "questionId", questionId,
                    "parentTurnId", parentTurnId == null ? JSON_NULL : parentTurnId.toString(),
                    "kind", turn.type(), "question", turn.question(), "answer", turn.answer(),
                    "feedback", output.feedback().trim(), "strengths", cleanStrings(output.strengths()),
                    "improvements", cleanStrings(output.improvements()), "evidence", evidence));
        }
        if (map.size() != result.size()) throw invalid("model returned feedback for an unknown turn");
        return result;
    }

    private List<Map<String,Object>> insights(EvidenceCatalog catalog, List<InsightOutput> input, String label) {
        if (input != null && input.size() > 10) throw invalid(label + " section oversized");
        List<Map<String,Object>> result = new ArrayList<>();
        for (InsightOutput item : input == null ? List.<InsightOutput>of() : input) {
            requireText(item == null ? null : item.text(), label);
            List<Map<String,Object>> evidence = refs(catalog, item.evidenceIds(), null, true);
            if (!hasAnswerEvidence(evidence)) {
                log.warn("report_claim_dropped section={} reason=NO_ANSWER_EVIDENCE", label);
                continue;
            }
            result.add(Map.of("text", item.text().trim(), "evidence", evidence));
        }
        return result;
    }
    private List<Map<String,Object>> recommendations(EvidenceCatalog catalog, List<RecommendationOutput> input) {
        if (input != null && input.size() > 10) throw invalid("recommendations section oversized");
        List<Map<String,Object>> result = new ArrayList<>();
        for (RecommendationOutput item : input == null ? List.<RecommendationOutput>of() : input) {
            requireText(item == null ? null : item.action(), "recommendation"); requireText(item.why(), "recommendation rationale");
            List<Map<String,Object>> evidence = refs(catalog, item.evidenceIds(), null, true);
            if (!hasAnswerEvidence(evidence)) {
                log.warn("report_claim_dropped section=RECOMMENDATION reason=NO_ANSWER_EVIDENCE");
                continue;
            }
            result.add(Map.of("action", item.action().trim(), "why", item.why().trim(), "relatedGapId", JSON_NULL, "evidence", evidence));
        }
        return result;
    }
    private List<Map<String,Object>> learning(EvidenceCatalog catalog, List<LearningOutput> input) {
        if (input != null && input.size() > 10) throw invalid("learning path oversized");
        List<Map<String,Object>> result = new ArrayList<>();
        int order = 1;
        for (LearningOutput item : input == null ? List.<LearningOutput>of() : input) {
            requireText(item == null ? null : item.objective(), "learning objective");
            List<String> activities = cleanStrings(item.activities());
            if (activities.size() > 10 || activities.stream().anyMatch(value -> value.length() > 1000)) throw invalid("learning activities oversized");
            if (activities.isEmpty()) throw invalid("learning activities missing");
            List<Map<String,Object>> evidence = refs(catalog, item.evidenceIds(), null, true);
            if (!hasAnswerEvidence(evidence)) {
                log.warn("report_claim_dropped section=LEARNING_PATH reason=NO_ANSWER_EVIDENCE");
                continue;
            }
            result.add(Map.of("order", order++, "objective", item.objective().trim(), "activities", activities,
                    "relatedGapId", JSON_NULL, "evidence", evidence));
        }
        return result;
    }

    private static boolean hasAnswerEvidence(List<Map<String, Object>> evidence) {
        return evidence.stream().anyMatch(item -> "TURN".equals(item.get("sourceType")));
    }

    Map<String,Object> assembleGap(UUID gapId, UUID reportId, ReportSource source, EvidenceCatalog catalog, GapOutput output) {
        if (output == null || output.requirements() == null || output.requirements().isEmpty())
            throw invalid("gap requirements count invalid");
        List<RequirementOutput> modelRequirements = output.requirements();
        if (modelRequirements.size() > 20) {
            log.warn("gap_requirements_truncated reason=MAXIMUM_COUNT actual={} maximum=20", modelRequirements.size());
            modelRequirements = modelRequirements.subList(0, 20);
        }
        List<Map<String,Object>> requirements = new ArrayList<>();
        List<Map<String,Object>> gaps = new ArrayList<>();
        double weighted = 0, weights = 0;
        int evaluated = 0;
        for (int i = 0; i < modelRequirements.size(); i++) {
            RequirementOutput raw = modelRequirements.get(i);
            if (raw == null || !hasUsableGapText(raw.text())) {
                log.warn("gap_requirement_dropped reason=INVALID_TEXT");
                continue;
            }
            boolean hasSourceSpecificEvidence = hasIds(raw.jdEvidenceIds()) || hasIds(raw.resumeEvidenceIds())
                    || hasIds(raw.interviewEvidenceIds());
            List<Map<String,Object>> evidence;
            if (hasSourceSpecificEvidence) {
                List<Map<String,Object>> jdEvidence = gapRefs(catalog, raw.jdEvidenceIds(), "JD");
                List<Map<String,Object>> resumeEvidence = gapRefs(catalog, raw.resumeEvidenceIds(), "RESUME");
                List<Map<String,Object>> interviewEvidence = gapRefs(catalog, raw.interviewEvidenceIds(), "TURN");
                evidence = new ArrayList<>(jdEvidence.size() + resumeEvidence.size() + interviewEvidence.size());
                evidence.addAll(jdEvidence); evidence.addAll(resumeEvidence); evidence.addAll(interviewEvidence);
            } else {
                // Backward compatibility for already-configured structured-output providers.
                evidence = gapRefs(catalog, raw.evidenceIds(), null);
            }
            if (evidence.stream().noneMatch(item -> "JD".equals(item.get("sourceType")))) {
                log.warn("gap_requirement_dropped reason=NO_JD_EVIDENCE");
                continue;
            }
            String importance = raw.importance() != null && Set.of("CORE", "IMPORTANT", "PREFERRED").contains(raw.importance())
                    ? raw.importance() : "IMPORTANT";
            if (!importance.equals(raw.importance())) log.warn("gap_requirement_defaulted reason=INVALID_IMPORTANCE");
            double confidence = raw.confidence() != null && Double.isFinite(raw.confidence())
                    && raw.confidence() >= 0 && raw.confidence() <= 1 ? raw.confidence() : 0.5d;
            if (raw.confidence() == null || !Double.isFinite(raw.confidence()) || raw.confidence() < 0 || raw.confidence() > 1)
                log.warn("gap_requirement_defaulted reason=INVALID_CONFIDENCE");
            boolean hasTurn = evidence.stream().anyMatch(item -> "TURN".equals(item.get("sourceType")));
            boolean hasResume = evidence.stream().anyMatch(item -> "RESUME".equals(item.get("sourceType")));
            Double resumeScore = hasResume ? validGapScore(raw.resumeScore(), "RESUME") : null;
            Double interviewScore = hasTurn && hasUsableGapText(raw.rationale())
                    ? validGapScore(raw.interviewScore(), "INTERVIEW") : null;
            String status = !hasTurn || interviewScore == null ? "NOT_EVALUATED"
                    : interviewScore >= 4 ? "MATCH" : interviewScore >= 3 ? "PARTIAL_GAP" : "GAP";
            double weight = switch(importance) { case "CORE" -> 3d; case "IMPORTANT" -> 2d; default -> 1d; };
            Double combined = null;
            if (resumeScore != null || interviewScore != null) {
                double sum = 0; double div = 0;
                if (resumeScore != null) { sum += resumeScore * .4; div += .4; }
                if (interviewScore != null) { sum += interviewScore * .6; div += .6; }
                combined = sum / div; weighted += weight * combined; weights += weight; evaluated++;
            }
            String rid = "REQ-%02d".formatted(requirements.size() + 1);
            Map<String,Object> req = new LinkedHashMap<>(); req.put("requirementId", rid); req.put("text", raw.text().trim());
            req.put("importance", importance); req.put("importanceWeight", weight);
            req.put("resumeStatus", resumeScore == null ? "NO_EVIDENCE" : resumeScore >= 4 ? "MATCH" : resumeScore >= 3 ? "PARTIAL" : "NO_EVIDENCE");
            req.put("resumeScore", resumeScore == null ? JSON_NULL : resumeScore);
            req.put("interviewStatus", !hasTurn || interviewScore == null ? "UNASSESSED"
                    : interviewScore >= 4 ? "DEMONSTRATED" : interviewScore >= 3 ? "PARTIAL" : "NOT_DEMONSTRATED");
            req.put("interviewScore", interviewScore == null ? JSON_NULL : interviewScore);
            String rationale;
            if (status.equals("NOT_EVALUATED")) rationale = NOT_EVALUATED_TEXT;
            else rationale = raw.rationale().trim();
            req.put("evidence", evidence); req.put("rationale", rationale);
            requirements.add(req);
            if ("GAP".equals(status) || "PARTIAL_GAP".equals(status) || "NOT_EVALUATED".equals(status)) {
                double size = status.equals("NOT_EVALUATED") ? 0 : Math.min(4, 5 - interviewScore);
                double priorityScore = weight * size * confidence;
                String priority = priorityScore >= 7 ? "HIGH" : priorityScore >= 3 ? "MEDIUM" : "LOW";
                List<String> attribution = new ArrayList<>();
                if (!status.equals("NOT_EVALUATED")) attribution.add("EXPRESSION_GAP");
                String recommendation;
                if (status.equals("NOT_EVALUATED")) recommendation = "";
                else recommendation = hasUsableGapText(raw.recommendation())
                        ? raw.recommendation().trim() : "可在后续回答中补充具体做法、个人职责与结果。";
                Map<String,Object> gap = new LinkedHashMap<>(); gap.put("gapId", "GAP-%02d".formatted(gaps.size() + 1)); gap.put("requirementId", rid);
                gap.put("title", raw.text().trim()); gap.put("status", status); gap.put("reason", rationale);
                gap.put("attribution", attribution); gap.put("gapSize", size); gap.put("confidence", confidence);
                gap.put("priorityScore", priorityScore); gap.put("priority", status.equals("NOT_EVALUATED") ? "LOW" : priority); gap.put("recommendation", recommendation);
                gap.put("evidence", status.equals("NOT_EVALUATED") ? List.of() : evidence);
                gaps.add(gap);
            }
        }
        if (requirements.isEmpty()) throw invalid("gap output has no requirements grounded in the JD");
        Integer match = weights == 0 ? null : (int)Math.round(weighted / weights * 20);
        List<Map<String,Object>> radar = List.of(
                radar("SKILLS", requirements), radar("PROJECT_EXPERIENCE", requirements),
                radar("PROBLEM_SOLVING", requirements), radar("ROLE_RESPONSIBILITIES", requirements));
        Map<String,Object> result = new LinkedHashMap<>(); result.put("schemaVersion", "1.2.0");
        result.put("gapAnalysisId", gapId.toString()); result.put("reportId", reportId.toString());
        result.put("matchScore", match == null ? JSON_NULL : match); result.put("formulaVersion", "match-40-60.v1");
        result.put("coverage", Map.of("evaluated", evaluated, "total", requirements.size())); result.put("requirements", requirements);
        result.put("gaps", gaps); result.put("radar", radar); result.put("generatedAt", Instant.now().toString());
        result.put("summary", Map.of("matchScore", match == null ? JSON_NULL : match, "evaluatedRequirements", evaluated,
                "totalRequirements", requirements.size(), "topGaps", gaps.stream().filter(g ->
                        !"NOT_EVALUATED".equals(g.get("status")) && ((Number)g.get("confidence")).doubleValue() >= .5)
                .sorted((a,b) -> Double.compare((Double)b.get("priorityScore"), (Double)a.get("priorityScore"))).limit(3)
                .map(g -> Map.of("gapId", g.get("gapId"), "title", g.get("title"), "priority", g.get("priority"))).toList(),
                "radar", radar, "oneLineConclusion", match == null ? "本场证据不足以计算岗位匹配度。" : "已按本场可评估要求计算岗位匹配度；未评估项未计为不匹配。",
                "fullAnalysisPath", "/api/v1/reports/" + reportId + "/gap-analysis"));
        return result;
    }

    private Map<String,Object> radar(String axis, List<Map<String,Object>> reqs) {
        List<Map<String,Object>> matching = reqs.stream()
                .filter(row -> axisFor(String.valueOf(row.get("text"))).equals(axis)).toList();
        Double resume = meanNullable(matching, "resumeScore");
        Double interview = meanNullable(matching, "interviewScore");
        Map<String,Object> row = new LinkedHashMap<>(); row.put("axis", axis);
        row.put("requirementScore", matching.isEmpty() ? JSON_NULL : 5);
        row.put("resumeScore", resume == null ? JSON_NULL : resume); row.put("interviewScore", interview == null ? JSON_NULL : interview);
        return row;
    }
    private static String axisFor(String requirement) {
        String text = requirement.toLowerCase();
        if (List.of("项目", "project", "experience", "经历", "案例").stream().anyMatch(text::contains)) return "PROJECT_EXPERIENCE";
        if (List.of("问题解决", "排查", "故障", "诊断", "problem", "troubleshoot", "debug").stream().anyMatch(text::contains)) return "PROBLEM_SOLVING";
        if (List.of("职责", "岗位", "交付", "responsibilit", "role", "ownership").stream().anyMatch(text::contains)) return "ROLE_RESPONSIBILITIES";
        return "SKILLS";
    }
    private static Double meanNullable(List<Map<String,Object>> rows, String key) {
        double sum = 0; int count = 0;
        for (var row : rows) if (row.get(key) instanceof Number number) { sum += number.doubleValue(); count++; }
        return count == 0 ? null : sum / count;
    }
    private List<Map<String,Object>> refs(EvidenceCatalog catalog, List<String> ids, String expectedTurn, boolean nullable) {
        if (ids == null || ids.isEmpty()) return List.of();
        Set<String> unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size()) throw invalid("duplicate evidence id");
        List<Map<String,Object>> result = new ArrayList<>();
        for (String id : unique) {
            EvidenceCatalog.Evidence evidence = catalog.require(id);
            if (expectedTurn != null && (!"TURN".equals(evidence.sourceType()) || !expectedTurn.equals(evidence.sourceId())))
                throw invalid("turn feedback cited unrelated evidence");
            result.add(evidence.toMap());
        }
        return result;
    }
    private List<Map<String,Object>> gapRefs(EvidenceCatalog catalog, List<String> ids, String expectedSource) {
        if (ids == null || ids.isEmpty()) return List.of();
        Set<String> unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size()) log.warn("gap_evidence_dropped reason=DUPLICATE_ID source={}", expectedSource == null ? "MIXED" : expectedSource);
        List<Map<String,Object>> result = new ArrayList<>();
        for (String id : unique) {
            EvidenceCatalog.Evidence evidence;
            try { evidence = catalog.require(id); }
            catch (IllegalArgumentException unknown) {
                log.warn("gap_evidence_dropped reason=UNKNOWN_ID source={}", expectedSource == null ? "MIXED" : expectedSource);
                continue;
            }
            if (expectedSource != null && !expectedSource.equals(evidence.sourceType())) {
                log.warn("gap_evidence_dropped reason=SOURCE_MISMATCH expected={} actual={}", expectedSource, evidence.sourceType());
                continue;
            }
            result.add(evidence.toMap());
        }
        return result;
    }
    private static boolean hasIds(List<String> ids) { return ids != null && !ids.isEmpty(); }
    private List<Map<String,Object>> summaryEvidence(EvidenceCatalog catalog, List<String> ids, String claim) {
        List<Map<String,Object>> evidence = refs(catalog, ids, null, true);
        if (evidence.isEmpty() || evidence.stream().anyMatch(item -> !"TURN".equals(item.get("sourceType"))))
            throw invalid(claim + " must cite answer evidence");
        return evidence;
    }
    private SummaryResolution verifySummaryEvidence(ReportSource source, EvidenceCatalog catalog, ReportOutput output) {
        List<Map<String, Object>> turnEvidence = catalog.all().stream()
                .filter(evidence -> "TURN".equals(evidence.sourceType()))
                .map(EvidenceCatalog.Evidence::toMap).toList();
        if (turnEvidence.isEmpty()) throw invalid("summary requires answer evidence");

        String context;
        try {
            Map<String, Object> verification = new LinkedHashMap<>();
            verification.put("overallReview", output.overallReview());
            verification.put("overallReviewProposedEvidenceIds", output.overallReviewEvidenceIds());
            verification.put("fullAnswers", source.turns().stream().map(turn -> {
                List<String> ids = catalog.all().stream()
                        .filter(evidence -> "TURN".equals(evidence.sourceType())
                                && turn.id().toString().equals(evidence.sourceId()))
                        .map(EvidenceCatalog.Evidence::id).toList();
                return Map.of("turnId", turn.id().toString(), "sequence", turn.sequence(),
                        "question", turn.question(), "answer", turn.answer(), "evidenceIds", ids);
            }).toList());
            verification.put("turnEvidenceCandidates", turnEvidence);
            context = json.writeValueAsString(verification);
        } catch (Exception failure) {
            log.warn("report_summary_evidence_verification status=FALLBACK failure_type={}", failure.getClass().getSimpleName());
            return new SummaryResolution(unverifiedSummary(), 0);
        }

        ReportModel.SummaryEvidenceReview review;
        long verificationStarted = System.nanoTime();
        try {
            review = model.verifySummaryEvidence(context);
        } catch (RuntimeException failure) {
            log.warn("report_summary_evidence_verification status=FALLBACK failure_type={}", failure.getClass().getSimpleName());
            return new SummaryResolution(unverifiedSummary(), elapsed(verificationStarted));
        }
        SummaryClaim overall = resolveSummaryClaim(catalog, output.overallReview(),
                review == null ? null : new ReportModel.SummaryClaimReview(review.directlySupported(), review.evidenceIds()),
                "overall review");
        return new SummaryResolution(overall, elapsed(verificationStarted));
    }
    private SummaryClaim resolveSummaryClaim(EvidenceCatalog catalog, String generatedText,
            ReportModel.SummaryClaimReview review, String claim) {
        if (review != null && review.directlySupported()) {
            try {
                List<Map<String, Object>> evidence = summaryEvidence(catalog, review.evidenceIds(), claim);
                log.info("report_summary_evidence_verification claim={} status=MODEL_SUPPORTED citations={}", claim, evidence.size());
                return new SummaryClaim(generatedText.trim(), evidence, "MODEL_SUPPORTED");
            } catch (RuntimeException invalidReview) {
                log.warn("report_summary_evidence_verification claim={} status=FALLBACK reason=INVALID_CITATION", claim);
            }
        } else {
            log.info("report_summary_evidence_verification claim={} status=FALLBACK reason=UNSUPPORTED", claim);
        }
        return unverifiedSummary();
    }
    private SummaryClaim unverifiedSummary() {
        return new SummaryClaim(ReportSummaryEvidenceSanitizer.UNVERIFIED_TEXT, List.of(), "UNVERIFIED");
    }
    private record SummaryClaim(String text, List<Map<String, Object>> evidence, String evidenceStatus) {}
    private record SummaryResolution(SummaryClaim overallReview, long verificationElapsedMs) {}
    String reportPromptContext(ReportSource source, EvidenceCatalog catalog) throws Exception {
        Map<String,Object> context = new LinkedHashMap<>();
        context.put("mode", source.mode()); context.put("title", source.title()); context.put("completionReason", source.completionReason());
        context.put("hasJD", eligibleForGap(source));
        context.put("sourceSnapshotIds", sourceSnapshotIds(source));
        context.put("turns", source.turns().stream().map(t -> Map.of("turnId", t.id(), "questionId", safeText(t.sourceQuestionId(), t.id().toString()),
                "sequence", t.sequence(), "type", t.type(),
                "mainQuestionIndex", t.mainQuestionIndex(), "question", t.question(), "answer", t.answer())).toList());
        context.put("evidenceCandidates", json.readTree(catalog.promptJson(json)));
        return json.writeValueAsString(context);
    }
    String gapPromptContext(ReportSource source, EvidenceCatalog catalog) throws Exception {
        Map<String,Object> context = new LinkedHashMap<>(); context.put("jdText", source.jdText());
        context.put("sourceSnapshotIds", sourceSnapshotIds(source));
        context.put("turns", source.turns().stream().map(t -> Map.of("turnId", t.id(),
                "questionId", safeText(t.sourceQuestionId(), t.id().toString()), "mainQuestionIndex", t.mainQuestionIndex(),
                "type", t.type(), "question", t.question(), "answer", t.answer())).toList());
        context.put("evidenceCandidates", json.readTree(catalog.promptJson(json)));
        return json.writeValueAsString(context);
    }
    private static Map<String,Object> sourceSnapshotIds(ReportSource source) {
        JsonNode resumeId = source.sourceSnapshot().path("resume").path("id");
        JsonNode bankId = source.sourceSnapshot().path("questionBank").path("id");
        return Map.of("resumeSnapshotId", resumeId.isMissingNode() || resumeId.isNull() ? JSON_NULL : resumeId.asString(),
                "questionBankSnapshotId", bankId.isMissingNode() || bankId.isNull() ? JSON_NULL : bankId.asString(),
                "jdSnapshotId", eligibleForGap(source) ? "jd-" + source.interviewId() : JSON_NULL);
    }
    private static Map<String,Object> emptyReport(UUID reportId, ReportSource source, String reportTitle) {
        Map<String,Object> scores = new LinkedHashMap<>();
        for (String key : DIMENSIONS) {
            boolean notApplicable = key.equals("JOB_MATCH") && !eligibleForGap(source);
            String status = key.equals("JOB_MATCH") && notApplicable ? "NOT_APPLICABLE" : "UNASSESSED";
            scores.put(key, Map.of("status", status, "value", JSON_NULL, "rationale", "没有已回答的面试问题，因此未评估。", "evidence", List.of()));
        }
        return Map.ofEntries(Map.entry("schemaVersion",REPORT_SCHEMA_VERSION), Map.entry("reportId",reportId.toString()),
                Map.entry("interviewId",source.interviewId().toString()), Map.entry("ownerId",source.ownerId().toString()),
                Map.entry("title",reportTitle), Map.entry("mode",source.mode()), Map.entry("status","PARTIAL"),
                Map.entry("createdAt",Instant.now().toString()), Map.entry("completedAt",Instant.now().toString()),
                Map.entry("completionReason",safeText(source.completionReason(),"")), Map.entry("sourceSnapshot",sourceSnapshotIds(source)),
                Map.entry("summary",Map.of("overallScore",JSON_NULL,"overallScoreStatus","UNASSESSED",
                        "overallReview","本场没有已回答的面试问题，无法评估能力。",
                        "overallReviewEvidenceStatus","NOT_APPLICABLE","overallReviewEvidence",List.of(),"evidence",List.of())),
                Map.entry("scores",scores), Map.entry("turns",List.of()), Map.entry("strengths",List.of()), Map.entry("risks",List.of()),
                Map.entry("recommendations",List.of(Map.of("action","重新完成一轮面试并提交回答","why","当前没有作答证据可供复盘。","relatedGapId",JSON_NULL,"evidence",List.of()))),
                Map.entry("learningPath",List.of()), Map.entry("gapAnalysis",disabledGapState()),
                Map.entry("nextActions",List.of("RESTART","EXPORT_PDF","HOME")),
                Map.entry("generationMeta",Map.of("provider", "RULES", "modelId", "none", "promptVersion", "phase4.empty.v1",
                        "summaryEvidenceVerifierVersion", "not-run",
                        "schemaVersion",REPORT_SCHEMA_VERSION,"elapsedMs",0,"inputTokens",JSON_NULL,"outputTokens",JSON_NULL,"costCny",JSON_NULL,"usageReported",false)));
    }

    private ReportSummary summary(ReportRepository.ReportHistoryRow row) {
        JsonNode content = row.reportContent() == null ? null : read(row.reportContent());
        JsonNode summary = content == null ? JSON_NULL : content.path("summary");
        int score = summary.path("overallScore").canConvertToInt() ? summary.path("overallScore").asInt() : -1;
        String reportTaskStatus = row.reportTaskStatus() == null ? "PENDING" : row.reportTaskStatus();
        String overallReviewEvidenceStatus = content == null
                ? null : content.path("summary").path("overallReviewEvidenceStatus").asString(null);
        if (ReportSummaryEvidenceSanitizer.requiresRetry(content == null ? null : content.path("summary"))) {
            overallReviewEvidenceStatus = ReportSummaryEvidenceSanitizer.UNVERIFIED_STATUS;
        }
        String title = ReportTitleFormatter.format(row.username(), row.mode(), row.questionBankTitle(),
                row.completedAt(), row.interviewOrdinal());
        String errorMessage = "FAILED".equals(reportTaskStatus)
                ? reportFailureMessage(row.reportErrorCode(), row.reportDurationMs(), row.reportErrorMessage()) : null;
        boolean hasUnassessedDimensions = row.questionCount() > 0 && hasUnassessedDimensions(content, row.mode());
        return new ReportSummary(row.reportId(), row.interviewId(), title, row.mode(), row.completedAt(),
                score < 0 ? null : score, false, false,
                row.reportStatus(), row.questionCount(), reportTaskStatus, errorMessage, "NOT_APPLICABLE",
                overallReviewEvidenceStatus, hasUnassessedDimensions);
    }

    private static boolean hasUnassessedDimensions(JsonNode content, String mode) {
        if (content == null || content.isNull() || !content.path("scores").isObject()) return false;
        Set<String> applicable = "QUESTION_BANK".equals(mode)
                ? Set.of("TECHNICAL_DEPTH", "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING")
                : Set.of(DIMENSIONS);
        JsonNode scores = content.path("scores");
        return applicable.stream().anyMatch(key -> "UNASSESSED".equals(scores.path(key).path("status").asString()));
    }

    private static String unassessedRationale(String reason) {
        if (reason == null) return NOT_EVALUATED_TEXT;
        return switch (reason) {
            case "MODEL_MARKED_UNASSESSED" -> "模型判断本场回答不足以支持此项评分。";
            case "MISSING_MODEL_DIMENSION" -> "模型未返回该维度评估，当前报告未完成此项评分。";
            case "INVALID_MODEL_STATUS", "APPLICABLE_DIMENSION_MARKED_NOT_APPLICABLE", "MISSING_SCORE", "SCORE_OUT_OF_RANGE" -> "本次未能获得有效的评分结果，因此暂不评分。";
            default -> NOT_EVALUATED_TEXT;
        };
    }
    private ReportDetail detail(ReportRow row) {
        ObjectNode content = ((ObjectNode) read(row.content())).deepCopy();
        content.put("title", reports.displayTitle(row.ownerId(), row.interviewId()));
        ObjectNode summary = (ObjectNode) content.path("summary");
        ReportSummaryEvidenceSanitizer.sanitize(summary);
        summary.remove(List.of("oneLineConclusion", "oneLineConclusionEvidenceStatus", "oneLineConclusionEvidence"));
        ObjectNode scores = (ObjectNode) content.path("scores");
        scores.remove("CULTURE_MATCH");
        for (String key : DIMENSIONS) {
            if (scores.path(key) instanceof ObjectNode score
                    && "UNASSESSED".equals(score.path("status").asString())) {
                score.put("rationale", NOT_EVALUATED_TEXT);
            }
        }
        ObjectNode disabledGap = content.putObject("gapAnalysis");
        disabledGap.put("status", "NOT_APPLICABLE");
        disabledGap.put("notApplicableReason", "FEATURE_DISABLED");
        disabledGap.putNull("gapAnalysisId");
        disabledGap.putNull("summary");
        ObjectNodeView gap = new ObjectNodeView("NOT_APPLICABLE", null);
        return new ReportDetail(row.id(), row.interviewId(), content, read(row.sourceSnapshot()), gap, row.completedAt());
    }

    private static String failureCode(Exception failure) {
        if (failure instanceof ApiException api && api.code().startsWith("REPORT_MODEL_")) return api.code();
        return failure instanceof IllegalArgumentException ? "REPORT_OUTPUT_INVALID" : "REPORT_GENERATION_FAILED";
    }

    private static String taskFailureMessage(String taskType, String code) {
        if ("GAP_ANALYSIS".equals(taskType)) {
            if ("REPORT_MODEL_TIMEOUT".equals(code)) return "岗位差异分析等待模型响应超时，可重试。";
            if ("REPORT_MODEL_UNAVAILABLE".equals(code)) return "岗位差异分析模型暂时不可用，请检查模型服务配置后重试。";
            if ("REPORT_OUTPUT_INVALID".equals(code)) return "岗位差异分析结果未通过内容校验，可重试。";
            return "岗位差异分析生成失败，可单独重试。";
        }
        if ("REPORT_MODEL_TIMEOUT".equals(code)) return "报告等待模型响应超时，未能生成；可以重试，或检查模型服务响应。";
        if ("REPORT_MODEL_UNAVAILABLE".equals(code)) return "报告模型暂时不可用；请检查本地模型开关、服务地址和 API Key 后重试。";
        if ("REPORT_OUTPUT_INVALID".equals(code)) return "模型返回内容未通过报告校验，可重试生成。";
        return "报告生成失败，可重试；请检查模型服务和后端日志。";
    }

    private static ReportTask withVisibleFailureMessage(ReportTask task) {
        return new ReportTask(task.id(), task.interviewId(), task.ownerId(), task.type(), task.status(),
                task.retryCount(), task.attemptCount(), task.errorCode(),
                reportFailureMessage(task.errorCode(), task.durationMs(), task.errorMessage()), task.queuedAt(),
                task.startedAt(), task.completedAt(), task.durationMs(), task.workerId(), task.leaseUntil());
    }

    private static String reportFailureMessage(String code, Long durationMs, String existingMessage) {
        if ("REPORT_MODEL_TIMEOUT".equals(code)) return "报告等待模型响应超时，未能生成；可以重试，或检查模型服务响应。";
        if ("REPORT_MODEL_UNAVAILABLE".equals(code)) return "报告模型暂时不可用；请检查本地模型开关、服务地址和 API Key 后重试。";
        if ("REPORT_GENERATION_FAILED".equals(code) && durationMs != null && durationMs >= 80_000) {
            return "报告等待模型响应较久后失败，可能是模型接口超时或连接异常；可以重试。";
        }
        return existingMessage == null || existingMessage.isBlank() ? "报告生成失败，可重试。" : existingMessage;
    }
    private JsonNode read(String content) {
        try { return json.readTree(content); } catch (Exception e) { throw new IllegalStateException("Stored report is invalid", e); }
    }
    private static boolean eligibleForGap(ReportSource source) {
        return "COMPREHENSIVE".equals(source.mode()) && source.jdText() != null && !source.jdText().isBlank();
    }
    private static boolean hasWorkStyleRequirement(String jd) {
        if (jd == null) return false;
        String text = jd.toLowerCase();
        return List.of("协作", "沟通", "跨团队", "异步", "反馈", "collaboration", "communication", "teamwork").stream().anyMatch(text::contains);
    }
    private static Double validGapScore(Double score, String source) {
        if (score == null) return null;
        if (!Double.isFinite(score) || score < 0 || score > 5) {
            log.warn("gap_score_ignored reason=OUT_OF_RANGE source={}", source);
            return null;
        }
        return score;
    }
    private static boolean hasUsableGapText(String value) {
        return value != null && !value.isBlank() && value.length() <= 3000;
    }
    private static String safeValidationReason(String message) {
        if (message == null || message.length() > 120 || !message.matches("[A-Za-z0-9 ._-]+"))
            return "unclassified_validation_error";
        return message;
    }
    private static List<String> cleanStrings(List<String> values) {
        if (values == null) return List.of(); return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }
    private static Map<String, Object> disabledGapState() {
        return Map.of("status", "NOT_APPLICABLE", "notApplicableReason", "FEATURE_DISABLED",
                "gapAnalysisId", JSON_NULL, "summary", JSON_NULL);
    }
    private static void requireText(String value, String field) { if (value == null || value.isBlank() || value.length() > 3000) throw invalid(field + " missing or too long"); }
    private static String safeText(String value, String fallback) { return value == null ? fallback : value; }
    private static long elapsed(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "报告不存在。"); }

    public record ReportStatus(UUID interviewId, ReportTask reportTask, UUID reportId, String gapStatus, UUID gapAnalysisId, String gapError) {}
    public record ReportSummary(UUID id, UUID interviewId, String title, String mode, Instant completedAt,
            Integer overallScore, boolean hasGap, boolean gapApplicable, String status,
            int questionCount, String reportTaskStatus, String reportErrorMessage, String gapTaskStatus,
            String overallReviewEvidenceStatus, boolean hasUnassessedDimensions) {}
    public record ReportDetail(UUID id, UUID interviewId, JsonNode report, JsonNode sourceSnapshot, ObjectNodeView gapTask, Instant completedAt) {}
    public record GapDetail(UUID id, UUID reportId, UUID interviewId, JsonNode gap, Instant generatedAt) {}
    public record ObjectNodeView(String status, String errorMessage) {}
}
