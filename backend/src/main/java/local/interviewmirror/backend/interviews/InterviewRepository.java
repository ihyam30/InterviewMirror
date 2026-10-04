package local.interviewmirror.backend.interviews;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class InterviewRepository {
    private static final String COLUMNS = "id, owner_id, mode, status, client_request_id, request_fingerprint, "
            + "schema_version, prompt_version, model_provider, model_id, resume_id, question_bank_id, title, jd_text, "
            + "source_snapshot, question_plan, main_question_target, current_main_index, current_followup_count, "
            + "active_turn_id, completion_reason, transition_state, transition_turn_id, graph_thread_id, "
            + "model_data_consent_at, state_version, created_at, started_at, completed_at, updated_at";
    private static final RowMapper<InterviewRecord> INTERVIEW_MAPPER = InterviewRepository::mapInterview;
    private static final RowMapper<InterviewTurnRecord> TURN_MAPPER = InterviewRepository::mapTurn;
    private static final ObjectMapper STATIC_JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InterviewRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public InterviewRecord create(UUID id, UUID ownerId, InterviewRequests.Create request,
            String requestFingerprint, String title, JsonNode sourceSnapshot,
            String modelProvider, String modelId, Instant consentAt) {
        try {
            jdbc.update("""
                    INSERT INTO interviews(id, owner_id, mode, status, client_request_id, request_fingerprint,
                      schema_version, prompt_version, model_provider, model_id, resume_id, question_bank_id,
                      title, jd_text, source_snapshot, question_plan, main_question_target, graph_thread_id,
                      model_data_consent_at)
                    VALUES (?, ?, ?, 'CREATED', ?, ?, ?, 'phase3.interview-runtime.v1', ?, ?, ?, ?, ?, ?, ?, '[]', 6, ?, ?)
                    """, id, ownerId, request.mode().name(), request.clientRequestId(), requestFingerprint,
                    request.schemaVersion(), modelProvider, modelId, request.resumeId(), request.questionBankId(),
                    title, cleanJd(request.jdText()), write(sourceSnapshot), id.toString(), Timestamp.from(consentAt));
            event(id, ownerId, "interview.created", Map.of("interviewId", id.toString(), "mode", request.mode().name()));
            return findOwned(ownerId, id);
        } catch (DuplicateKeyException duplicate) {
            InterviewRecord existing = findByRequest(ownerId, request.clientRequestId()).orElseThrow(() -> duplicate);
            if (!existing.requestFingerprint().equals(requestFingerprint)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "同一个 clientRequestId 不能用于不同的面试配置。");
            }
            return existing;
        }
    }

    public Optional<InterviewRecord> findByRequest(UUID ownerId, String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interviews WHERE owner_id=? AND client_request_id=?",
                INTERVIEW_MAPPER, ownerId, requestId).stream().findFirst();
    }

    public InterviewRecord findOwned(UUID ownerId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interviews WHERE id=? AND owner_id=?",
                INTERVIEW_MAPPER, id, ownerId).stream().findFirst().orElseThrow(InterviewRepository::notFound);
    }

    public List<InterviewRecord> listOwned(UUID ownerId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interviews WHERE owner_id=? ORDER BY created_at DESC",
                INTERVIEW_MAPPER, ownerId);
    }

    public List<InterviewTurnRecord> turnsOwned(UUID ownerId, UUID interviewId) {
        return jdbc.query("SELECT * FROM interview_turns WHERE interview_id=? AND owner_id=? ORDER BY sequence_no",
                TURN_MAPPER, interviewId, ownerId);
    }

    public List<InterviewTurnRecord> allTurns(UUID interviewId, UUID ownerId) {
        return jdbc.query("SELECT * FROM interview_turns WHERE interview_id=? AND owner_id=? ORDER BY sequence_no",
                TURN_MAPPER, interviewId, ownerId);
    }

    @Transactional
    public boolean claimPreparation(UUID ownerId, UUID id) {
        return jdbc.update("UPDATE interviews SET status='PREPARING', last_error_code=NULL, updated_at=CURRENT_TIMESTAMP, "
                        + "state_version=state_version+1 WHERE id=? AND owner_id=? AND status IN ('CREATED','START_FAILED')",
                id, ownerId) == 1;
    }

    @Transactional
    public void saveQuestionPlan(UUID ownerId, UUID id, JsonNode plan) {
        if (jdbc.update("UPDATE interviews SET question_plan=?, updated_at=CURRENT_TIMESTAMP, state_version=state_version+1 "
                + "WHERE id=? AND owner_id=? AND status='PREPARING'", write(plan), id, ownerId) != 1) {
            throw conflict("INTERVIEW_NOT_PREPARING", "当前面试状态不能保存问题计划。");
        }
    }

    @Transactional
    public InterviewRecord completePreparation(UUID ownerId, UUID id, InterviewGraphRuntime.GraphSnapshot graph,
            JsonNode plan) {
        InterviewRecord locked = lockOwned(ownerId, id);
        if (locked.status() == InterviewStatus.RUNNING) return locked;
        if (locked.status() != InterviewStatus.PREPARING || graph.currentQuestion() == null
                || graph.currentQuestion().isBlank() || graph.mainQuestionIndex() < 0 || graph.mainQuestionIndex() > 7) {
            throw conflict("INTERVIEW_PREPARATION_STATE_INVALID", "面试问题准备未能形成有效的首题。");
        }
        UUID turnId = UUID.randomUUID();
        int sequence = nextSequence(id);
        String type = safeTurnType(graph.currentTurnType());
        Integer followupIndex = type.equals("FOLLOW_UP") ? graph.followupCount() : null;
        jdbc.update("""
                INSERT INTO interview_turns(id, interview_id, owner_id, sequence_no, turn_type,
                  main_question_index, followup_index, source_question_id, question, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ASKED')
                """, turnId, id, ownerId, sequence, type, graph.mainQuestionIndex() + 1, followupIndex,
                emptyToNull(graph.sourceQuestionId()), graph.currentQuestion());
        jdbc.update("""
                UPDATE interviews SET status='RUNNING', question_plan=?, current_main_index=?, current_followup_count=?,
                  active_turn_id=?, started_at=COALESCE(started_at,CURRENT_TIMESTAMP), updated_at=CURRENT_TIMESTAMP,
                  state_version=state_version+1 WHERE id=? AND owner_id=? AND status='PREPARING'
                """, write(plan), graph.mainQuestionIndex(), graph.followupCount(), turnId, id, ownerId);
        event(id, ownerId, "interview.started", Map.of("interviewId", id.toString(), "mode", locked.mode().name()));
        event(id, ownerId, "interview.question", turnPayload(turnId, sequence, type,
                graph.mainQuestionIndex() + 1, followupIndex, graph.currentQuestion()));
        return findOwned(ownerId, id);
    }

    @Transactional
    public void markStartFailed(UUID ownerId, UUID id, String errorCode) {
        jdbc.update("UPDATE interviews SET status='START_FAILED', last_error_code=?, updated_at=CURRENT_TIMESTAMP, "
                + "state_version=state_version+1 WHERE id=? AND owner_id=? AND status='PREPARING'",
                errorCode, id, ownerId);
        event(id, ownerId, "interview.error", Map.of("code", errorCode, "retryable", true));
    }

    @Transactional
    public AnswerOutcome saveAnswer(UUID ownerId, UUID interviewId, UUID turnId,
            String requestId, String answer) {
        Optional<InterviewTurnRecord> replay = jdbc.query("SELECT * FROM interview_turns WHERE interview_id=? "
                        + "AND owner_id=? AND answer_request_id=?", TURN_MAPPER,
                interviewId, ownerId, requestId).stream().findFirst();
        if (replay.isPresent()) {
            InterviewTurnRecord prior = replay.get();
            if (!prior.id().equals(turnId) || !prior.answer().equals(answer)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "回答请求 ID 已用于另一条回答。");
            }
            return new AnswerOutcome(findOwned(ownerId, interviewId), prior, true);
        }
        InterviewRecord locked = lockOwned(ownerId, interviewId);
        if (locked.status() != InterviewStatus.RUNNING || !turnId.equals(locked.activeTurnId())) {
            throw conflict("INTERVIEW_TURN_NOT_ANSWERABLE", "当前题目已回答、已结束或不是本场正在等待的题目。");
        }
        int updated = jdbc.update("""
                UPDATE interview_turns SET answer=?, status='ANSWERED', answer_request_id=?, answered_at=CURRENT_TIMESTAMP
                WHERE id=? AND interview_id=? AND owner_id=? AND status='ASKED'
                  AND EXISTS (SELECT 1 FROM interviews i WHERE i.id=? AND i.owner_id=?
                    AND i.status='RUNNING' AND i.active_turn_id=interview_turns.id)
                """, answer, requestId, turnId, interviewId, ownerId, interviewId, ownerId);
        if (updated != 1) {
            Optional<InterviewTurnRecord> existing = jdbc.query("SELECT * FROM interview_turns WHERE id=? AND interview_id=? AND owner_id=?",
                    TURN_MAPPER, turnId, interviewId, ownerId).stream().findFirst();
            if (existing.isEmpty()) throw notFound();
            throw conflict("INTERVIEW_TURN_NOT_ANSWERABLE", "当前题目已回答、已结束或不是本场正在等待的题目。");
        }
        jdbc.update("UPDATE interviews SET transition_state='PENDING', transition_turn_id=?, updated_at=CURRENT_TIMESTAMP, "
                + "state_version=state_version+1 WHERE id=? AND owner_id=? AND status='RUNNING' AND active_turn_id=?",
                turnId, interviewId, ownerId, turnId);
        event(interviewId, ownerId, "interview.answer.saved", Map.of("turnId", turnId.toString()));
        InterviewTurnRecord saved = jdbc.query("SELECT * FROM interview_turns WHERE id=?", TURN_MAPPER, turnId).getFirst();
        return new AnswerOutcome(findOwned(ownerId, interviewId), saved, false);
    }

    @Transactional
    public boolean claimTransition(UUID ownerId, UUID interviewId, UUID turnId) {
        return jdbc.update("UPDATE interviews SET transition_state='PROCESSING', updated_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND owner_id=? AND status='RUNNING' AND transition_turn_id=? AND transition_state='PENDING'",
                interviewId, ownerId, turnId) == 1;
    }

    public InterviewTurnRecord transitionTurn(UUID ownerId, UUID interviewId, UUID turnId) {
        return jdbc.query("SELECT * FROM interview_turns WHERE id=? AND interview_id=? AND owner_id=? AND status='ANSWERED'",
                TURN_MAPPER, turnId, interviewId, ownerId).stream().findFirst().orElseThrow(InterviewRepository::notFound);
    }

    @Transactional
    public ReplaceClaim claimReplacement(UUID ownerId, UUID interviewId, UUID turnId,
            String requestId, String fingerprint) {
        int claimed = jdbc.update("""
                UPDATE interview_turns SET replace_request_id=?, replace_request_fingerprint=?,
                  replace_claimed_at=CURRENT_TIMESTAMP
                WHERE id=? AND interview_id=? AND owner_id=? AND status='ASKED' AND turn_type='MAIN'
                  AND replace_request_id IS NULL
                  AND EXISTS (SELECT 1 FROM interviews i WHERE i.id=? AND i.owner_id=?
                    AND i.status='RUNNING' AND i.active_turn_id=interview_turns.id
                    AND i.transition_state='IDLE')
                """, requestId, fingerprint, turnId, interviewId, ownerId, interviewId, ownerId);
        if (claimed == 1) return ReplaceClaim.CLAIMED;

        Optional<InterviewTurnRecord> existing = jdbc.query("SELECT * FROM interview_turns WHERE id=? AND interview_id=? AND owner_id=?",
                TURN_MAPPER, turnId, interviewId, ownerId).stream().findFirst();
        if (existing.isEmpty()) throw notFound();
        InterviewTurnRecord old = existing.get();
        if (requestId.equals(old.replaceRequestId())) {
            if (!fingerprint.equals(old.replaceRequestFingerprint())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "换题请求 ID 已用于不同的请求。");
            }
            if ("SKIPPED".equals(old.status())) return ReplaceClaim.COMPLETED;
            throw conflict("INTERVIEW_REPLACE_IN_PROGRESS", "换题请求正在处理，请稍后刷新状态。");
        }
        if (old.replaceRequestId() != null) {
            throw conflict("INTERVIEW_REPLACE_IN_PROGRESS", "当前问题已有换题请求正在处理，请稍后刷新状态。");
        }
        throw conflict("INTERVIEW_REPLACE_STATE_INVALID", "只能替换当前尚未作答的主问题。");
    }

    @Transactional
    public void releaseReplacementClaim(UUID ownerId, UUID interviewId, UUID turnId, String requestId) {
        jdbc.update("UPDATE interview_turns SET replace_request_id=NULL, replace_request_fingerprint=NULL, "
                        + "replace_claimed_at=NULL WHERE id=? AND interview_id=? AND owner_id=? "
                        + "AND replace_request_id=? AND status='ASKED'",
                turnId, interviewId, ownerId, requestId);
    }

    public List<InterviewTurnRecord> staleReplacementClaims(Instant before) {
        return jdbc.query("""
                SELECT t.* FROM interview_turns t JOIN interviews i
                  ON i.id=t.interview_id AND i.owner_id=t.owner_id
                WHERE t.status='ASKED' AND t.replace_request_id IS NOT NULL
                  AND t.replace_claimed_at < ? AND i.status='RUNNING' AND i.active_turn_id=t.id
                ORDER BY t.replace_claimed_at
                """, TURN_MAPPER, Timestamp.from(before));
    }

    @Transactional
    public InterviewRecord completeTransition(UUID ownerId, UUID interviewId,
            InterviewGraphRuntime.GraphSnapshot graph) {
        InterviewRecord locked = lockOwned(ownerId, interviewId);
        if (!"PROCESSING".equals(locked.transitionState()) && !"PENDING".equals(locked.transitionState())) {
            return locked;
        }
        if ("COMPLETE".equals(graph.phase())) {
            String reason = graph.completionReason() == null || graph.completionReason().isBlank()
                    ? "QUESTION_LIMIT" : graph.completionReason();
            jdbc.update("UPDATE interviews SET status='COMPLETING', updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND owner_id=? AND status='RUNNING'",
                    interviewId, ownerId);
            jdbc.update("""
                    UPDATE interviews SET status='COMPLETE', current_main_index=main_question_target,
                      active_turn_id=NULL, completion_reason=?, transition_state='IDLE', transition_turn_id=NULL,
                      completed_at=COALESCE(completed_at,CURRENT_TIMESTAMP), updated_at=CURRENT_TIMESTAMP,
                      state_version=state_version+1 WHERE id=? AND owner_id=? AND status='COMPLETING'
                    """, reason, interviewId, ownerId);
            event(interviewId, ownerId, "interview.completed", Map.of("reason", reason));
            return findOwned(ownerId, interviewId);
        }
        if (!"WAITING_FOR_ANSWER".equals(graph.phase()) || graph.currentQuestion() == null
                || graph.currentQuestion().isBlank() || graph.mainQuestionIndex() > 7
                || graph.followupCount() < 0 || graph.followupCount() > 2) {
            throw conflict("INTERVIEW_GRAPH_STATE_INVALID", "工作流没有生成有效的下一题。");
        }
        UUID turnId = UUID.randomUUID();
        int sequence = nextSequence(interviewId);
        String type = safeTurnType(graph.currentTurnType());
        Integer followupIndex = type.equals("FOLLOW_UP") ? graph.followupCount() : null;
        jdbc.update("""
                INSERT INTO interview_turns(id, interview_id, owner_id, sequence_no, turn_type,
                  main_question_index, followup_index, source_question_id, question, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ASKED')
                """, turnId, interviewId, ownerId, sequence, type, graph.mainQuestionIndex() + 1, followupIndex,
                emptyToNull(graph.sourceQuestionId()), graph.currentQuestion());
        jdbc.update("""
                UPDATE interviews SET current_main_index=?, current_followup_count=?, active_turn_id=?,
                  transition_state='IDLE', transition_turn_id=NULL, last_error_code=NULL,
                  updated_at=CURRENT_TIMESTAMP, state_version=state_version+1 WHERE id=? AND owner_id=?
                """, graph.mainQuestionIndex(), graph.followupCount(), turnId, interviewId, ownerId);
        event(interviewId, ownerId, type.equals("FOLLOW_UP") ? "interview.followup" : "interview.question",
                turnPayload(turnId, sequence, type,
                graph.mainQuestionIndex() + 1, followupIndex, graph.currentQuestion()));
        if (Boolean.parseBoolean(graph.fallbackUsed())) {
            event(interviewId, ownerId, "interview.error", Map.of("code", "FOLLOWUP_FALLBACK_NEXT_QUESTION", "retryable", false));
        }
        return findOwned(ownerId, interviewId);
    }

    @Transactional
    public InterviewRecord completeReplacement(UUID ownerId, UUID interviewId, UUID oldTurnId,
            String requestId, InterviewGraphRuntime.GraphSnapshot graph) {
        InterviewRecord locked = lockOwned(ownerId, interviewId);
        if (locked.status() != InterviewStatus.RUNNING || !oldTurnId.equals(locked.activeTurnId())
                || !"WAITING_FOR_ANSWER".equals(graph.phase()) || !"MAIN".equals(graph.currentTurnType())) {
            throw conflict("INTERVIEW_REPLACE_STATE_INVALID", "当前问题状态不允许换题。");
        }
        InterviewTurnRecord old = jdbc.query("SELECT * FROM interview_turns WHERE id=? AND interview_id=? AND owner_id=?",
                TURN_MAPPER, oldTurnId, interviewId, ownerId).stream().findFirst().orElseThrow(InterviewRepository::notFound);
        if (!"ASKED".equals(old.status()) || !"MAIN".equals(old.type().name())
                || !requestId.equals(old.replaceRequestId())) {
            throw conflict("INTERVIEW_REPLACE_STATE_INVALID", "只能替换尚未作答的主问题。");
        }
        jdbc.update("UPDATE interview_turns SET status='SKIPPED' WHERE id=? AND owner_id=? AND status='ASKED'", oldTurnId, ownerId);
        UUID newTurnId = UUID.randomUUID();
        int sequence = nextSequence(interviewId);
        jdbc.update("""
                INSERT INTO interview_turns(id, interview_id, owner_id, sequence_no, turn_type,
                  main_question_index, source_question_id, question, status)
                VALUES (?, ?, ?, ?, 'MAIN', ?, ?, ?, 'ASKED')
                """, newTurnId, interviewId, ownerId, sequence, graph.mainQuestionIndex() + 1,
                emptyToNull(graph.sourceQuestionId()), graph.currentQuestion());
        jdbc.update("UPDATE interviews SET active_turn_id=?, current_followup_count=0, question_plan=?, updated_at=CURRENT_TIMESTAMP, "
                + "state_version=state_version+1 WHERE id=? AND owner_id=?", newTurnId,
                write(json.valueToTree(graph.questionPlan())), interviewId, ownerId);
        event(interviewId, ownerId, "interview.question", turnPayload(newTurnId, sequence, "MAIN",
                graph.mainQuestionIndex() + 1, null, graph.currentQuestion()));
        return findOwned(ownerId, interviewId);
    }

    @Transactional
    public EndClaim beginEarlyEnd(UUID ownerId, UUID interviewId, String requestId) {
        InterviewRecord locked = lockOwned(ownerId, interviewId);
        if (locked.status() == InterviewStatus.COMPLETE) return new EndClaim(locked, false);
        if (locked.status() == InterviewStatus.COMPLETING) return new EndClaim(locked, false);
        if (locked.status() != InterviewStatus.RUNNING || !"IDLE".equals(locked.transitionState())) {
            throw conflict("INTERVIEW_NOT_RUNNING", "只有未处理回答的进行中面试可以提前结束。");
        }
        if (locked.activeTurnId() != null) {
            jdbc.update("UPDATE interview_turns SET status='SKIPPED' WHERE id=? AND interview_id=? AND owner_id=? AND status='ASKED'",
                    locked.activeTurnId(), interviewId, ownerId);
        }
        int claimed = jdbc.update("""
                UPDATE interviews SET status='COMPLETING', completion_request_id=?, updated_at=CURRENT_TIMESTAMP,
                  state_version=state_version+1 WHERE id=? AND owner_id=? AND status='RUNNING'
                  AND transition_state='IDLE'
                """, requestId, interviewId, ownerId);
        if (claimed != 1) throw conflict("INTERVIEW_NOT_RUNNING", "面试状态已变化，请刷新后重试。");
        return new EndClaim(findOwned(ownerId, interviewId), true);
    }

    @Transactional
    public InterviewRecord finalizeEarlyEnd(UUID ownerId, UUID interviewId) {
        InterviewRecord locked = lockOwned(ownerId, interviewId);
        if (locked.status() == InterviewStatus.COMPLETE) return locked;
        if (locked.status() != InterviewStatus.COMPLETING) {
            throw conflict("INTERVIEW_COMPLETION_STATE_INVALID", "面试没有处于结束处理中。");
        }
        jdbc.update("""
                UPDATE interviews SET status='COMPLETE', active_turn_id=NULL, completion_reason='USER_ENDED',
                  transition_state='IDLE', transition_turn_id=NULL, completed_at=CURRENT_TIMESTAMP,
                  updated_at=CURRENT_TIMESTAMP, state_version=state_version+1
                WHERE id=? AND owner_id=? AND status='COMPLETING'
                """, interviewId, ownerId);
        event(interviewId, ownerId, "interview.completed", Map.of("reason", "USER_ENDED"));
        return findOwned(ownerId, interviewId);
    }

    public List<InterviewRecord> staleCompletingInterviews(Instant before) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interviews WHERE status='COMPLETING' AND updated_at < ? ORDER BY updated_at",
                INTERVIEW_MAPPER, Timestamp.from(before));
    }

    public Optional<InterviewTurnRecord> findTurnByAnswerRequest(UUID ownerId, UUID interviewId, String requestId) {
        return jdbc.query("SELECT * FROM interview_turns WHERE owner_id=? AND interview_id=? AND answer_request_id=?",
                TURN_MAPPER, ownerId, interviewId, requestId).stream().findFirst();
    }

    public List<InterviewRecord> pendingTransitions() {
        return jdbc.query("SELECT " + COLUMNS + " FROM interviews WHERE transition_state='PENDING' ORDER BY updated_at",
                INTERVIEW_MAPPER);
    }

    @Transactional
    public int recoverStaleTransitions(Instant before) {
        return jdbc.update("UPDATE interviews SET transition_state='PENDING', updated_at=CURRENT_TIMESTAMP "
                + "WHERE transition_state='PROCESSING' AND updated_at < ?", Timestamp.from(before));
    }

    @Transactional
    public int recoverStalePreparations(Instant before) {
        return jdbc.update("UPDATE interviews SET status='CREATED', updated_at=CURRENT_TIMESTAMP, "
                + "last_error_code='START_INTERRUPTED' WHERE status='PREPARING' AND updated_at < ?",
                Timestamp.from(before));
    }

    @Transactional
    public void event(UUID interviewId, UUID ownerId, String type, Map<String, ?> payload) {
        jdbc.update("INSERT INTO interview_events(interview_id, owner_id, event_type, payload) VALUES (?, ?, ?, ?)",
                interviewId, ownerId, type, write(json.valueToTree(payload)));
    }

    public List<InterviewEventRecord> eventsAfter(UUID ownerId, UUID interviewId, long eventId, int limit) {
        return jdbc.query("SELECT event_id, event_type, payload, created_at FROM interview_events "
                        + "WHERE interview_id=? AND owner_id=? AND event_id>? ORDER BY event_id LIMIT ?",
                (rs, row) -> new InterviewEventRecord(rs.getLong("event_id"), rs.getString("event_type"),
                        parse(rs.getString("payload")), instant(rs, "created_at")), interviewId, ownerId, eventId, limit);
    }

    public long latestEventId(UUID ownerId, UUID interviewId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(event_id),0) FROM interview_events WHERE interview_id=? AND owner_id=?",
                Long.class, interviewId, ownerId);
        return value == null ? 0L : value;
    }

    private InterviewRecord lockOwned(UUID ownerId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interviews WHERE id=? AND owner_id=? FOR UPDATE",
                INTERVIEW_MAPPER, id, ownerId).stream().findFirst().orElseThrow(InterviewRepository::notFound);
    }

    private int nextSequence(UUID interviewId) {
        Integer sequence = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM interview_turns WHERE interview_id=?",
                Integer.class, interviewId);
        return sequence == null ? 1 : sequence;
    }

    private String write(JsonNode node) {
        try { return json.writeValueAsString(node); }
        catch (JacksonException e) { throw new IllegalStateException("interview JSON serialization failed", e); }
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JacksonException e) { throw new IllegalStateException("interview JSON data is corrupt", e); }
    }

    private static InterviewRecord mapInterview(ResultSet rs, int row) throws SQLException {
        return new InterviewRecord(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                InterviewMode.valueOf(rs.getString("mode")), InterviewStatus.valueOf(rs.getString("status")),
                rs.getString("client_request_id"), rs.getString("request_fingerprint"), rs.getString("schema_version"),
                rs.getString("prompt_version"), rs.getString("model_provider"), rs.getString("model_id"),
                rs.getObject("resume_id", UUID.class), rs.getObject("question_bank_id", UUID.class),
                rs.getString("title"), rs.getString("jd_text"), parseStatic(rs.getString("source_snapshot")),
                parseStatic(rs.getString("question_plan")), rs.getInt("main_question_target"),
                rs.getInt("current_main_index"), rs.getInt("current_followup_count"),
                rs.getObject("active_turn_id", UUID.class), rs.getString("completion_reason"),
                rs.getString("transition_state"), rs.getObject("transition_turn_id", UUID.class),
                rs.getString("graph_thread_id"), instant(rs, "model_data_consent_at"), rs.getLong("state_version"),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at"), instant(rs, "updated_at"));
    }

    private static InterviewTurnRecord mapTurn(ResultSet rs, int row) throws SQLException {
        Number followup = (Number) rs.getObject("followup_index");
        return new InterviewTurnRecord(rs.getObject("id", UUID.class), rs.getObject("interview_id", UUID.class),
                rs.getObject("owner_id", UUID.class), rs.getInt("sequence_no"),
                InterviewTurnType.valueOf(rs.getString("turn_type")), rs.getInt("main_question_index"),
                followup == null ? null : followup.intValue(), rs.getString("source_question_id"),
                rs.getString("question"), rs.getString("answer"), rs.getString("status"),
                rs.getString("answer_request_id"), instant(rs, "asked_at"), instant(rs, "answered_at"),
                rs.getString("replace_request_id"), rs.getString("replace_request_fingerprint"),
                instant(rs, "replace_claimed_at"));
    }

    private static JsonNode parseStatic(String value) {
        try { return STATIC_JSON.readTree(value); }
        catch (JacksonException e) { throw new IllegalStateException("interview JSON data is corrupt", e); }
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(field);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String safeTurnType(String value) {
        return "FOLLOW_UP".equals(value) ? "FOLLOW_UP" : "MAIN";
    }

    private static Map<String, Object> turnPayload(UUID id, int sequence, String type, int mainIndex,
            Integer followupIndex, String question) {
        return Map.of("turnId", id.toString(), "sequence", sequence, "type", type,
                "mainQuestionIndex", mainIndex, "followupIndex", followupIndex == null ? 0 : followupIndex,
                "question", question);
    }

    private static String cleanJd(String jd) { return jd == null || jd.isBlank() ? null : jd.trim(); }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "面试记录不存在。");
    }
    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public record AnswerOutcome(InterviewRecord interview, InterviewTurnRecord turn, boolean replay) {}
    public record EndClaim(InterviewRecord interview, boolean claimed) {}
    public record InterviewEventRecord(long eventId, String eventType, JsonNode payload, Instant createdAt) {}
    public enum ReplaceClaim { CLAIMED, COMPLETED }
}
