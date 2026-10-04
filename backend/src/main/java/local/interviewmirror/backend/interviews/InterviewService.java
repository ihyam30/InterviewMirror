package local.interviewmirror.backend.interviews;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import local.interviewmirror.backend.common.ApiException;
import local.interviewmirror.backend.documents.DocumentContentRules;
import local.interviewmirror.backend.documents.DocumentService;
import local.interviewmirror.backend.documents.DocumentType;
import local.interviewmirror.backend.documents.ManagedDocumentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class InterviewService {
    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);
    private static final int MAX_REPLACEMENTS_PER_MAIN = 3;
    private final InterviewRepository repository;
    private final DocumentService documents;
    private final InterviewModeValidator validator;
    private final InterviewModel model;
    private final InterviewGraphRuntime graph;
    private final ObjectMapper json;

    public InterviewService(InterviewRepository repository, DocumentService documents,
            InterviewModeValidator validator, InterviewModel model, InterviewGraphRuntime graph,
            ObjectMapper json) {
        this.repository = repository;
        this.documents = documents;
        this.validator = validator;
        this.model = model;
        this.graph = graph;
        this.json = json;
    }

    public InterviewViews.Interview create(UUID ownerId, InterviewRequests.Create request) {
        validator.validate(request);
        String fingerprint = fingerprint(request);
        var prior = repository.findByRequest(ownerId, request.clientRequestId());
        if (prior.isPresent()) {
            if (!prior.get().requestFingerprint().equals(fingerprint)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "同一个 clientRequestId 不能用于不同的面试配置。");
            }
            return toView(prior.get(), false);
        }
        if (!model.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_MODEL_NOT_CONFIGURED",
                    "尚未启用面试模型。请在本地配置模型服务后重试。未创建面试记录。");
        }
        ObjectNode snapshot = json.createObjectNode();
        String title;
        if (request.mode() == InterviewMode.COMPREHENSIVE) {
            ManagedDocumentView resume = documents.requireUsableForInterview(ownerId, DocumentType.RESUME, request.resumeId());
            ObjectNode resumeSnapshot = snapshot.putObject("resume");
            resumeSnapshot.put("id", resume.id().toString());
            resumeSnapshot.put("title", resume.title());
            resumeSnapshot.set("content", resume.content());
            snapshot.put("jdText", request.jdText() == null ? "" : request.jdText().trim());
            title = request.jdText() == null || request.jdText().isBlank() ? resume.title() : request.jdText().trim().substring(0, Math.min(80, request.jdText().trim().length()));
        } else {
            ManagedDocumentView bank = documents.requireUsableForInterview(ownerId, DocumentType.QUESTION_BANK, request.questionBankId());
            JsonNode questions = bank.content().path("questions");
            long usableCount = DocumentContentRules.countDistinctQuestionStems(questions);
            if (usableCount < InterviewModeValidator.MAIN_QUESTION_TARGET) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUESTION_BANK_NOT_ENOUGH_QUESTIONS",
                        "专项面试至少需要 6 道已确认且有效的题目。");
            }
            ObjectNode bankSnapshot = snapshot.putObject("questionBank");
            bankSnapshot.put("id", bank.id().toString());
            bankSnapshot.put("title", bank.title());
            bankSnapshot.set("content", bank.content());
            title = bank.title();
        }
        InterviewRecord record = repository.create(UUID.randomUUID(), ownerId, request, fingerprint,
                title, snapshot, model.provider(), model.modelId(), Instant.now());
        return toView(record, false);
    }

    public InterviewViews.Interview start(UUID ownerId, UUID id) {
        InterviewRecord existing = repository.findOwned(ownerId, id);
        if (existing.status() == InterviewStatus.RUNNING || existing.status() == InterviewStatus.COMPLETE) {
            return toView(existing, true);
        }
        if (!repository.claimPreparation(ownerId, id)) {
            InterviewRecord current = repository.findOwned(ownerId, id);
            if (current.status() == InterviewStatus.RUNNING || current.status() == InterviewStatus.COMPLETE) return toView(current, true);
            throw conflict("INTERVIEW_PREPARATION_IN_PROGRESS", "面试准备正在进行，请稍后刷新状态。");
        }
        try {
            InterviewRecord preparing = repository.findOwned(ownerId, id);
            List<InterviewModel.PlannedQuestion> plan = readPlan(preparing.questionPlan());
            if (plan.size() < InterviewModeValidator.MAIN_QUESTION_TARGET) {
                plan = preparePlan(preparing);
                repository.saveQuestionPlan(ownerId, id, json.valueToTree(plan));
            }
            InterviewGraphRuntime.GraphSnapshot current = checkpointOrNull(id);
            if (current == null || current.currentQuestion() == null || current.currentQuestion().isBlank()) {
                current = graph.begin(id, ownerId, preparing.mode(), preparing.resumeId(), preparing.questionBankId(), plan);
            }
            InterviewRecord result = repository.completePreparation(ownerId, id, current, json.valueToTree(plan));
            return toView(result, true);
        } catch (ApiException error) {
            repository.markStartFailed(ownerId, id, error.code());
            throw error;
        } catch (Exception error) {
            log.error("interview preparation failed interviewId={} userId={} mode={} failureType={}",
                    id, ownerId, existing.mode(), error.getClass().getSimpleName());
            repository.markStartFailed(ownerId, id, "INTERVIEW_PREPARATION_FAILED");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_PREPARATION_FAILED",
                    "面试准备失败。原配置已保留，可以重试开始。");
        }
    }

    private List<InterviewModel.PlannedQuestion> preparePlan(InterviewRecord interview) {
        if (interview.mode() == InterviewMode.COMPREHENSIVE) {
            String resumeJson = write(interview.sourceSnapshot().path("resume").path("content"));
            return validatePlan(model.generateComprehensivePlan(resumeJson,
                    interview.sourceSnapshot().path("jdText").asString(""), InterviewModeValidator.MAIN_QUESTION_TARGET));
        }
        JsonNode questions = interview.sourceSnapshot().path("questionBank").path("content").path("questions");
        List<InterviewModel.PlannedQuestion> candidates = new ArrayList<>();
        Set<String> seenStems = new HashSet<>();
        if (questions.isArray()) {
            int position = 0;
            for (JsonNode question : questions) {
                position++;
                String stem = question.path("stem").asString("").trim();
                if (stem.isBlank()) continue;
                if (!seenStems.add(DocumentContentRules.normalizeQuestionStem(stem))) continue;
                String sourceId = question.path("id").asString("bank-question-" + question.path("position").asInt(position));
                candidates.add(new InterviewModel.PlannedQuestion(sourceId, stem,
                        question.path("category").asString(""), "来自已确认题库", sourceId));
            }
        }
        candidates.sort(Comparator.comparing(InterviewModel.PlannedQuestion::sourceQuestionId));
        if (candidates.size() < InterviewModeValidator.MAIN_QUESTION_TARGET) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUESTION_BANK_NOT_ENOUGH_QUESTIONS",
                    "专项面试至少需要 6 道有效题目。");
        }
        java.util.Collections.shuffle(candidates, new Random(interview.id().getMostSignificantBits()));
        return List.copyOf(candidates.subList(0, Math.min(8, candidates.size())));
    }

    private static List<InterviewModel.PlannedQuestion> validatePlan(List<InterviewModel.PlannedQuestion> plan) {
        if (plan == null || plan.size() != InterviewModeValidator.MAIN_QUESTION_TARGET) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INTERVIEW_QUESTION_PLAN_INVALID", "模型未能准备 6 道有效主问题，请重试。");
        }
        Set<String> seen = new HashSet<>();
        for (InterviewModel.PlannedQuestion item : plan) {
            String stem = item == null || item.stem() == null ? "" : item.stem().trim();
            if (stem.isBlank() || stem.length() > 1000 || !seen.add(DocumentContentRules.normalizeQuestionStem(stem))) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "INTERVIEW_QUESTION_PLAN_INVALID", "模型问题计划包含空题或重复题，请重试。");
            }
        }
        return List.copyOf(plan);
    }

    public InterviewViews.Interview get(UUID ownerId, UUID id) {
        return toView(repository.findOwned(ownerId, id), true);
    }

    public List<InterviewViews.Interview> list(UUID ownerId) {
        return repository.listOwned(ownerId).stream().map(item -> toView(item, false)).toList();
    }

    public InterviewViews.TurnList turns(UUID ownerId, UUID id) {
        repository.findOwned(ownerId, id);
        return new InterviewViews.TurnList("interviewmirror.interview-turn-list.v1.0.0", id,
                repository.turnsOwned(ownerId, id).stream().map(this::toView).toList());
    }

    public InterviewViews.Interview answer(UUID ownerId, UUID id, InterviewRequests.Answer request) {
        // Commit the user's answer and its durable transition marker before any model call.
        // The scheduled recovery worker advances the graph so this request remains fast and
        // disconnects/timeouts cannot discard the answer.
        repository.saveAnswer(ownerId, id, request.turnId(), request.clientRequestId(), request.answer().trim());
        return get(ownerId, id);
    }

    private void resumePending(InterviewRecord interview, InterviewTurnRecord answeredTurn) {
        if (!"PENDING".equals(interview.transitionState()) && !"PROCESSING".equals(interview.transitionState())) return;
        try {
            InterviewGraphRuntime.GraphSnapshot checkpoint = checkpointOrNull(interview.id());
            if (checkpoint == null || !answeredTurn.id().toString().equals(checkpoint.lastAnsweredTurnId())) {
                checkpoint = graph.answer(interview.id(), answeredTurn.id(), answeredTurn.answer());
            }
            repository.completeTransition(interview.ownerId(), interview.id(), checkpoint);
        } catch (Exception failure) {
            log.error("interview transition deferred interviewId={} turnId={} userId={} failureType={}",
                    interview.id(), answeredTurn.id(), interview.ownerId(), failure.getClass().getSimpleName());
            // The answer remains persisted. The recovery worker retries from the database checkpoint.
        }
    }

    public InterviewViews.Interview replaceQuestion(UUID ownerId, UUID id, InterviewRequests.Replace request) {
        InterviewRecord interview = repository.findOwned(ownerId, id);
        String replaceFingerprint = replaceFingerprint(id, request.turnId());
        InterviewRepository.ReplaceClaim claim = repository.claimReplacement(ownerId, id, request.turnId(),
                request.clientRequestId(), replaceFingerprint);
        if (claim == InterviewRepository.ReplaceClaim.COMPLETED) return toView(repository.findOwned(ownerId, id), true);
        if (interview.status() != InterviewStatus.RUNNING || !request.turnId().equals(interview.activeTurnId())) {
            repository.releaseReplacementClaim(ownerId, id, request.turnId(), request.clientRequestId());
            throw conflict("INTERVIEW_REPLACE_STATE_INVALID", "当前状态不允许换题。");
        }
        InterviewTurnRecord active = repository.turnsOwned(ownerId, id).stream()
                .filter(turn -> turn.id().equals(request.turnId())).findFirst().orElseThrow(InterviewService::notFound);
        if (!"ASKED".equals(active.status()) || active.type() != InterviewTurnType.MAIN) {
            repository.releaseReplacementClaim(ownerId, id, request.turnId(), request.clientRequestId());
            throw conflict("INTERVIEW_REPLACE_STATE_INVALID", "只能替换尚未作答的主问题。");
        }
        InterviewGraphRuntime.GraphSnapshot state;
        try { state = graph.snapshot(id); }
        catch (RuntimeException failure) {
            // Keep the claim: checkpoint recovery must determine whether Graph had already advanced.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_REPLACE_FAILED", "工作流状态暂时不可用，请稍后重试。");
        }
        if (state.replaceCount() >= MAX_REPLACEMENTS_PER_MAIN) {
            repository.releaseReplacementClaim(ownerId, id, request.turnId(), request.clientRequestId());
            throw conflict("INTERVIEW_REPLACE_LIMIT", "同一道主问题最多更换 3 次。");
        }
        boolean graphCallStarted = false;
        try {
            List<InterviewModel.PlannedQuestion> plan = readPlan(interview.questionPlan());
            InterviewModel.PlannedQuestion replacement;
            List<InterviewModel.PlannedQuestion> updatedPlan = new ArrayList<>(plan);
            if (interview.mode() == InterviewMode.COMPREHENSIVE) {
                List<String> alreadyUsed = new ArrayList<>();
                repository.turnsOwned(ownerId, id).forEach(turn -> alreadyUsed.add(turn.question()));
                plan.forEach(item -> alreadyUsed.add(item.stem()));
                JsonNode resumeContent = interview.sourceSnapshot().path("resume").path("content");
                replacement = model.generateReplacement(write(resumeContent),
                        interview.sourceSnapshot().path("jdText").asString(""), active.question(), alreadyUsed);
            } else {
                Set<String> usedIds = new HashSet<>();
                repository.turnsOwned(ownerId, id).stream().map(InterviewTurnRecord::sourceQuestionId)
                        .filter(value -> value != null && !value.isBlank()).forEach(usedIds::add);
                int reserveIndex = -1;
                for (int index = InterviewModeValidator.MAIN_QUESTION_TARGET; index < plan.size(); index++) {
                    if (!usedIds.contains(plan.get(index).sourceQuestionId())) { reserveIndex = index; break; }
                }
                if (reserveIndex < 0) throw conflict("NO_REPLACEMENT_QUESTION_AVAILABLE", "本题库没有未使用的备用题目。");
                replacement = plan.get(reserveIndex);
                int mainIndex = state.mainQuestionIndex();
                InterviewModel.PlannedQuestion replaced = updatedPlan.get(mainIndex);
                updatedPlan.set(mainIndex, replacement);
                updatedPlan.set(reserveIndex, replaced);
            }
            if (interview.mode() == InterviewMode.COMPREHENSIVE) {
                updatedPlan.set(state.mainQuestionIndex(), replacement);
            }
            if (interview.mode() == InterviewMode.COMPREHENSIVE
                    && DocumentContentRules.normalizeQuestionStem(replacement.stem())
                    .equals(DocumentContentRules.normalizeQuestionStem(active.question()))) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "INTERVIEW_REPLACEMENT_DUPLICATE",
                        "模型返回了与原问题重复的问题，请重试换题。");
            }
            graphCallStarted = true;
            InterviewGraphRuntime.GraphSnapshot next = graph.replace(id, request.turnId(), replacement, updatedPlan);
            InterviewRecord result = repository.completeReplacement(ownerId, id, request.turnId(),
                    request.clientRequestId(), next);
            return toView(result, true);
        } catch (ApiException e) {
            if (!graphCallStarted) repository.releaseReplacementClaim(ownerId, id, request.turnId(), request.clientRequestId());
            throw e;
        } catch (Exception e) {
            if (!graphCallStarted) repository.releaseReplacementClaim(ownerId, id, request.turnId(), request.clientRequestId());
            log.error("interview question replacement failed interviewId={} turnId={} failureType={}",
                    id, request.turnId(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_REPLACE_FAILED",
                    "换题失败，当前面试进度已保留，请刷新后重试。");
        }
    }

    public InterviewViews.Interview end(UUID ownerId, UUID id, InterviewRequests.End request) {
        InterviewRecord interview = repository.findOwned(ownerId, id);
        if (interview.status() == InterviewStatus.COMPLETE) return toView(interview, true);
        if (interview.status() == InterviewStatus.RUNNING) {
            InterviewRepository.EndClaim claim = repository.beginEarlyEnd(ownerId, id, request.clientRequestId());
            interview = claim.interview();
            if (interview.status() == InterviewStatus.COMPLETE || !claim.claimed()) return toView(interview, true);
        } else if (interview.status() != InterviewStatus.COMPLETING) {
            throw conflict("INTERVIEW_NOT_ENDABLE", "当前状态不允许结束面试。");
        } else {
            return toView(interview, true);
        }
        try {
            InterviewGraphRuntime.GraphSnapshot checkpoint = checkpointOrNull(id);
            if (checkpoint == null || !"COMPLETE".equals(checkpoint.phase())) graph.end(id);
        }
        catch (Exception e) {
            log.error("interview graph end failed interviewId={} failureType={}", id, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_END_FAILED", "结束状态已安全保留，系统会继续恢复；请稍后刷新。");
        }
        return toView(repository.finalizeEarlyEnd(ownerId, id), true);
    }

    public void recover() {
        repository.recoverStalePreparations(Instant.now().minusSeconds(120));
        repository.recoverStaleTransitions(Instant.now().minusSeconds(120));
        for (InterviewTurnRecord turn : repository.staleReplacementClaims(Instant.now().minusSeconds(120))) {
            try {
                InterviewRecord interview = repository.findOwned(turn.ownerId(), turn.interviewId());
                InterviewGraphRuntime.GraphSnapshot checkpoint = graph.snapshot(interview.id());
                boolean graphAdvanced = !java.util.Objects.equals(checkpoint.currentQuestion(), turn.question())
                        || !java.util.Objects.equals(checkpoint.sourceQuestionId(), turn.sourceQuestionId());
                if (graphAdvanced) {
                    repository.completeReplacement(turn.ownerId(), interview.id(), turn.id(),
                            turn.replaceRequestId(), checkpoint);
                } else {
                    repository.releaseReplacementClaim(turn.ownerId(), interview.id(), turn.id(), turn.replaceRequestId());
                }
            } catch (Exception failure) {
                log.error("interview replacement recovery deferred interviewId={} turnId={} failureType={}",
                        turn.interviewId(), turn.id(), failure.getClass().getSimpleName());
            }
        }
        for (InterviewRecord completing : repository.staleCompletingInterviews(Instant.now().minusSeconds(120))) {
            try {
                InterviewGraphRuntime.GraphSnapshot checkpoint = checkpointOrNull(completing.id());
                if (checkpoint == null || !"COMPLETE".equals(checkpoint.phase())) graph.end(completing.id());
                repository.finalizeEarlyEnd(completing.ownerId(), completing.id());
            } catch (Exception failure) {
                log.error("interview completion recovery deferred interviewId={} failureType={}",
                        completing.id(), failure.getClass().getSimpleName());
            }
        }
        for (InterviewRecord interview : repository.pendingTransitions()) {
            UUID turnId = interview.transitionTurnId();
            if (turnId == null || !repository.claimTransition(interview.ownerId(), interview.id(), turnId)) continue;
            try {
                InterviewTurnRecord turn = repository.transitionTurn(interview.ownerId(), interview.id(), turnId);
                resumePending(repository.findOwned(interview.ownerId(), interview.id()), turn);
            } catch (RuntimeException e) {
                log.error("interview recovery deferred interviewId={} turnId={} failureType={}",
                        interview.id(), turnId, e.getClass().getSimpleName());
            }
        }
    }

    private InterviewGraphRuntime.GraphSnapshot checkpointOrNull(UUID id) {
        try { return graph.snapshot(id); }
        catch (RuntimeException missing) { return null; }
    }

    private InterviewViews.Interview toView(InterviewRecord record, boolean includeActiveTurn) {
        InterviewViews.Turn active = null;
        List<InterviewTurnRecord> turns = List.of();
        if (includeActiveTurn && record.activeTurnId() != null) {
            turns = repository.turnsOwned(record.ownerId(), record.id());
            active = turns.stream()
                    .filter(turn -> turn.id().equals(record.activeTurnId())).findFirst().map(this::toView).orElse(null);
        }
        boolean replacementAvailable = canReplace(record, active, turns);
        return new InterviewViews.Interview("interviewmirror.interview-session.v1.0.0", record.id(), record.mode(), record.status(), record.title(),
                record.resumeId(), record.questionBankId(), record.mainQuestionTarget(),
                record.status() == InterviewStatus.COMPLETE ? record.mainQuestionTarget() : record.currentMainIndex() + 1,
                record.currentFollowupCount(), record.activeTurnId(), record.completionReason(),
                !"IDLE".equals(record.transitionState()), replacementAvailable, record.createdAt(), record.startedAt(), record.completedAt(),
                record.version(), active);
    }

    private boolean canReplace(InterviewRecord record, InterviewViews.Turn active, List<InterviewTurnRecord> turns) {
        if (record.status() != InterviewStatus.RUNNING || active == null || !"ASKED".equals(active.status())
                || active.type() != InterviewTurnType.MAIN || !"IDLE".equals(record.transitionState())) return false;
        InterviewGraphRuntime.GraphSnapshot state = checkpointOrNull(record.id());
        if (state == null || state.replaceCount() >= MAX_REPLACEMENTS_PER_MAIN) return false;
        if (record.mode() == InterviewMode.COMPREHENSIVE) return true;

        List<InterviewModel.PlannedQuestion> plan = readPlan(record.questionPlan());
        if (plan.size() <= InterviewModeValidator.MAIN_QUESTION_TARGET) return false;
        Set<String> usedSourceIds = new HashSet<>();
        turns.stream().map(InterviewTurnRecord::sourceQuestionId)
                .filter(value -> value != null && !value.isBlank()).forEach(usedSourceIds::add);
        for (int index = InterviewModeValidator.MAIN_QUESTION_TARGET; index < plan.size(); index++) {
            String reserveId = plan.get(index).sourceQuestionId();
            if (reserveId != null && !reserveId.isBlank() && !usedSourceIds.contains(reserveId)) return true;
        }
        return false;
    }

    private InterviewViews.Turn toView(InterviewTurnRecord turn) {
        return new InterviewViews.Turn("interviewmirror.interview-turn.v1.0.0", turn.id(), turn.sequence(), turn.type(), turn.mainQuestionIndex(),
                turn.followupIndex(), turn.sourceQuestionId(), turn.question(), turn.answer(), turn.status(),
                turn.askedAt(), turn.answeredAt());
    }

    private List<InterviewModel.PlannedQuestion> readPlan(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<InterviewModel.PlannedQuestion> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(new InterviewModel.PlannedQuestion(item.path("id").asString(""),
                    item.path("stem").asString(""), item.path("category").asString(""),
                    item.path("rationale").asString(""), item.path("sourceQuestionId").asString("")));
        }
        return List.copyOf(result);
    }

    private String fingerprint(InterviewRequests.Create request) {
        Map<String, Object> stable = Map.of("schemaVersion", request.schemaVersion(), "mode", request.mode().name(),
                "resumeId", request.resumeId() == null ? "" : request.resumeId().toString(),
                "questionBankId", request.questionBankId() == null ? "" : request.questionBankId().toString(),
                "jdText", request.jdText() == null ? "" : request.jdText().trim(), "locale", request.locale(),
                "modelDataConsent", Boolean.TRUE.equals(request.modelDataConsent()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(stable))); }
        catch (Exception e) { throw new IllegalStateException("cannot fingerprint interview request", e); }
    }

    private static String replaceFingerprint(UUID interviewId, UUID turnId) {
        try {
            String value = interviewId + ":replace:" + turnId;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("cannot fingerprint replace request", e); }
    }

    private String write(JsonNode node) {
        try { return json.writeValueAsString(node); }
        catch (JacksonException e) { throw new IllegalStateException("cannot serialize interview snapshot", e); }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "面试记录不存在。");
    }
    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
