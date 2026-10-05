package local.interviewmirror.backend.reports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ReportRepository {
    private static final Set<String> QUESTION_BANK_RETRYABLE_DIMENSIONS = Set.of(
            "TECHNICAL_DEPTH", "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING");
    private static final Set<String> COMPREHENSIVE_RETRYABLE_DIMENSIONS = Set.of(
            "TECHNICAL_DEPTH", "PROJECT_EXPERIENCE", "JOB_MATCH", "COMMUNICATION", "LOGICAL_STRUCTURE", "PROBLEM_SOLVING");
    private static final String COMPLETED_HISTORY_CTE = """
            WITH completed_interviews AS (
              SELECT i.id,i.owner_id,i.mode,i.title,i.completed_at,i.created_at,u.username,
                COALESCE(NULLIF(i.title, ''),'自定义题库') AS question_bank_title,
                ROW_NUMBER() OVER (
                  PARTITION BY i.owner_id,i.mode,
                    CASE WHEN i.mode='QUESTION_BANK' THEN COALESCE(CAST(i.question_bank_id AS VARCHAR),'unknown') ELSE '' END
                  ORDER BY i.completed_at ASC NULLS LAST,i.created_at ASC,i.id ASC
                ) AS interview_ordinal
              FROM interviews i JOIN app_users u ON u.id=i.owner_id
              WHERE i.owner_id=? AND i.status='COMPLETE'
            )
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReportRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public ReportTask ensureReportTask(UUID ownerId, UUID interviewId) {
        ReportSource source = loadSource(ownerId, interviewId);
        if (!"COMPLETE".equals(source.status())) throw new ApiException(HttpStatus.CONFLICT,
                "INTERVIEW_NOT_COMPLETE", "只有已结束的面试可以生成报告。");
        ReportTask existing = task(ownerId, interviewId, "REPORT").orElse(null);
        if (existing != null) return existing;
        try {
            jdbc.update("INSERT INTO report_tasks(id,interview_id,owner_id,task_type,status) VALUES (?, ?, ?, 'REPORT', 'PENDING')",
                    UUID.randomUUID(), interviewId, ownerId);
        } catch (org.springframework.dao.DuplicateKeyException ignored) { /* a concurrent request inserted the unique task */ }
        return task(ownerId, interviewId, "REPORT").orElseThrow();
    }

    public ReportSource loadSource(UUID ownerId, UUID interviewId) {
        List<ReportSource> sources = jdbc.query("""
                SELECT id,owner_id,mode,status,title,completion_reason,model_provider,model_id,jd_text,source_snapshot,completed_at
                FROM interviews WHERE id=? AND owner_id=?
                """, (rs, row) -> new ReportSource(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getString("mode"), rs.getString("status"), rs.getString("title"), rs.getString("completion_reason"),
                rs.getString("model_provider"), rs.getString("model_id"), rs.getString("jd_text"),
                read(rs.getString("source_snapshot")), instant(rs, "completed_at"), List.of()), interviewId, ownerId);
        if (sources.isEmpty()) throw notFound();
        ReportSource base = sources.getFirst();
        List<ReportSource.SourceTurn> turns = jdbc.query("""
                SELECT id,sequence_no,turn_type,main_question_index,followup_index,source_question_id,question,answer,answered_at
                FROM interview_turns WHERE interview_id=? AND owner_id=? AND status='ANSWERED'
                  AND answer IS NOT NULL AND length(trim(answer))>0 ORDER BY sequence_no
                """, this::mapTurn, interviewId, ownerId);
        return new ReportSource(base.interviewId(), base.ownerId(), base.mode(), base.status(), base.title(),
                base.completionReason(), base.modelProvider(), base.modelId(), base.jdText(), base.sourceSnapshot(),
                base.completedAt(), turns);
    }

    @Transactional
    public Optional<ReportTask> claimNext(String workerId, Duration lease) {
        List<ReportTask> queued = jdbc.query("SELECT * FROM report_tasks WHERE task_type='REPORT' AND status='PENDING' ORDER BY queued_at,id LIMIT 8",
                this::mapTask);
        for (ReportTask task : queued) {
            Instant now = Instant.now();
            int claimed = jdbc.update("""
                    UPDATE report_tasks SET status='PROCESSING',attempt_count=attempt_count+1,started_at=?,completed_at=NULL,
                      duration_ms=NULL,error_code=NULL,error_message=NULL,worker_id=?,lease_until=?,updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND owner_id=? AND status='PENDING'
                    """, Timestamp.from(now), workerId, Timestamp.from(now.plus(lease)), task.id(), task.ownerId());
            if (claimed == 1) return findTask(task.id(), task.ownerId());
        }
        return Optional.empty();
    }

    @Transactional
    public int recoverExpired(Instant now) {
        return jdbc.update("""
                UPDATE report_tasks SET status='PENDING',queued_at=?,started_at=NULL,worker_id=NULL,lease_until=NULL,
                  updated_at=CURRENT_TIMESTAMP,error_code='WORKER_INTERRUPTED',error_message='报告任务中断，已自动恢复'
                WHERE status='PROCESSING' AND lease_until<=?
                """, Timestamp.from(now), Timestamp.from(now));
    }

    @Transactional
    public void saveReport(ReportTask task, UUID reportId, String schemaVersion, String content, String sourceSnapshot,
            long durationMs, String reportStatus) {
        verifyClaim(task);
        Instant completed = Instant.now();
        int updated = jdbc.update("""
                UPDATE interview_reports
                SET schema_version=?,status=?,content=?,source_snapshot=?,completed_at=?,generation_duration_ms=?
                WHERE interview_id=? AND owner_id=?
                """, schemaVersion, reportStatus, content, sourceSnapshot, Timestamp.from(completed),
                Math.max(0, durationMs), task.interviewId(), task.ownerId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO interview_reports(id,interview_id,owner_id,schema_version,status,content,source_snapshot,completed_at,generation_duration_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, reportId, task.interviewId(), task.ownerId(), schemaVersion, reportStatus, content,
                    sourceSnapshot, Timestamp.from(completed), Math.max(0, durationMs));
        }
        finishTask(task, "SUCCESS", null, null, durationMs);
    }

    @Transactional
    public void fail(ReportTask task, String code, String safeMessage, long durationMs) {
        finishTask(task, "FAILED", code, safeMessage, durationMs);
    }

    @Transactional
    public void retryReport(UUID ownerId, UUID reportId) {
        ReportRow report = findReport(ownerId, reportId).orElseThrow(ReportRepository::notFound);
        retryReportForInterview(ownerId, report.interviewId());
    }

    @Transactional
    public ReportTask retryReportForInterview(UUID ownerId, UUID interviewId) {
        List<InterviewRetryContext> interviews = jdbc.query("SELECT status,mode FROM interviews WHERE id=? AND owner_id=?",
                (rs, row) -> new InterviewRetryContext(rs.getString("status"), rs.getString("mode")), interviewId, ownerId);
        if (interviews.isEmpty()) throw notFound();
        InterviewRetryContext interview = interviews.getFirst();
        if (!"COMPLETE".equals(interview.status())) throw new ApiException(HttpStatus.CONFLICT, "INTERVIEW_NOT_COMPLETE", "只有已结束的面试可以重试报告生成。");
        ReportTask existing = task(ownerId, interviewId, "REPORT").orElse(null);
        if (existing == null || !isRetryableReport(existing, ownerId, interview)) {
            throw new ApiException(HttpStatus.CONFLICT, "REPORT_NOT_RETRYABLE", "当前报告任务不可重试。");
        }
        int changed = jdbc.update("""
                UPDATE report_tasks SET status='PENDING',retry_count=retry_count+1,queued_at=CURRENT_TIMESTAMP,
                  started_at=NULL,completed_at=NULL,worker_id=NULL,lease_until=NULL,error_code=NULL,error_message=NULL
                WHERE interview_id=? AND owner_id=? AND task_type='REPORT'
                  AND status=?
                """, interviewId, ownerId, existing.status());
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "REPORT_NOT_RETRYABLE", "当前报告任务不可重试。");
        return task(ownerId, interviewId, "REPORT").orElseThrow();
    }

    private boolean isRetryableReport(ReportTask task, UUID ownerId, InterviewRetryContext interview) {
        if ("FAILED".equals(task.status())) return true;
        if (!"SUCCESS".equals(task.status())) return false;
        ReportRow report = reportForInterview(ownerId, task.interviewId()).orElse(null);
        if (report == null) return false;
        JsonNode content = readReportContent(report.content());
        String evidenceStatus = content.path("summary").path("overallReviewEvidenceStatus").asText();
        if ("EXTRACTIVE_FALLBACK".equals(evidenceStatus) || "UNVERIFIED".equals(evidenceStatus)) return true;
        Integer answered = jdbc.queryForObject("""
                SELECT COUNT(*) FROM interview_turns
                WHERE interview_id=? AND owner_id=? AND status='ANSWERED'
                  AND answer IS NOT NULL AND length(trim(answer))>0
                """, Integer.class, task.interviewId(), ownerId);
        if (answered == null || answered == 0) return false;
        Set<String> eligibleDimensions = "QUESTION_BANK".equals(interview.mode())
                ? QUESTION_BANK_RETRYABLE_DIMENSIONS : COMPREHENSIVE_RETRYABLE_DIMENSIONS;
        JsonNode scores = content.path("scores");
        for (String dimension : eligibleDimensions) {
            if ("UNASSESSED".equals(scores.path(dimension).path("status").asText())) return true;
        }
        return false;
    }

    public Optional<ReportTask> task(UUID ownerId, UUID interviewId, String type) {
        return jdbc.query("SELECT * FROM report_tasks WHERE owner_id=? AND interview_id=? AND task_type=?",
                this::mapTask, ownerId, interviewId, type).stream().findFirst();
    }
    public Optional<ReportTask> findTask(UUID id, UUID ownerId) {
        return jdbc.query("SELECT * FROM report_tasks WHERE id=? AND owner_id=?", this::mapTask, id, ownerId).stream().findFirst();
    }
    public Optional<ReportRow> reportForInterview(UUID ownerId, UUID interviewId) {
        return jdbc.query("SELECT * FROM interview_reports WHERE interview_id=? AND owner_id=?",
                this::mapReport, interviewId, ownerId).stream().findFirst();
    }
    public Optional<ReportRow> findReport(UUID ownerId, UUID reportId) {
        return jdbc.query("SELECT * FROM interview_reports WHERE id=? AND owner_id=?",
                this::mapReport, reportId, ownerId).stream().findFirst();
    }
    public List<ReportRow> listReports(UUID ownerId) {
        return jdbc.query("SELECT * FROM interview_reports WHERE owner_id=? ORDER BY completed_at DESC,id", this::mapReport, ownerId);
    }
    public List<ReportHistoryRow> listReportHistory(UUID ownerId) {
        return jdbc.query(COMPLETED_HISTORY_CTE + """
                SELECT r.id AS report_id,i.id AS interview_id,i.title,i.username,i.question_bank_title,i.interview_ordinal,
                  i.mode,i.completed_at,
                  r.content AS report_content,r.status AS report_status,
                  t.status AS report_task_status,t.error_message AS report_error_message,
                  t.error_code AS report_error_code,t.duration_ms AS report_duration_ms,
                  gt.status AS gap_task_status,(ga.id IS NOT NULL) AS has_gap,
                  CASE WHEN i.mode='COMPREHENSIVE' AND interview.jd_text IS NOT NULL AND length(trim(interview.jd_text))>0
                    THEN TRUE ELSE FALSE END AS gap_applicable,
                  (SELECT COUNT(*) FROM interview_turns answered_turn
                    WHERE answered_turn.interview_id=i.id AND answered_turn.owner_id=i.owner_id AND answered_turn.status='ANSWERED'
                      AND answered_turn.answer IS NOT NULL AND length(trim(answered_turn.answer))>0) AS question_count
                FROM completed_interviews i
                JOIN interviews interview ON interview.id=i.id AND interview.owner_id=i.owner_id
                LEFT JOIN report_tasks t ON t.interview_id=i.id AND t.owner_id=i.owner_id AND t.task_type='REPORT'
                LEFT JOIN interview_reports r ON r.interview_id=i.id AND r.owner_id=i.owner_id
                LEFT JOIN report_tasks gt ON gt.interview_id=i.id AND gt.owner_id=i.owner_id AND gt.task_type='GAP_ANALYSIS'
                LEFT JOIN report_gap_analyses ga ON ga.report_id=r.id AND ga.owner_id=i.owner_id
                ORDER BY i.completed_at DESC,i.created_at DESC,i.id DESC
                """, (rs, row) -> {
                    Number duration = (Number) rs.getObject("report_duration_ms");
                    return new ReportHistoryRow(rs.getObject("report_id", UUID.class),
                            rs.getObject("interview_id", UUID.class), rs.getString("title"), rs.getString("username"),
                            rs.getString("question_bank_title"), rs.getLong("interview_ordinal"), rs.getString("mode"),
                            instant(rs, "completed_at"), rs.getString("report_content"), rs.getString("report_status"),
                            rs.getString("report_task_status"), rs.getString("report_error_message"), rs.getString("report_error_code"),
                            duration == null ? null : duration.longValue(), rs.getString("gap_task_status"),
                            rs.getBoolean("has_gap"), rs.getBoolean("gap_applicable"), rs.getInt("question_count"));
                }, ownerId);
    }
    public String displayTitle(UUID ownerId, UUID interviewId) {
        List<ReportTitleData> rows = jdbc.query(COMPLETED_HISTORY_CTE + """
                SELECT id,username,mode,question_bank_title,completed_at,interview_ordinal
                FROM completed_interviews WHERE id=? AND owner_id=?
                """, (rs, row) -> new ReportTitleData(rs.getString("username"), rs.getString("mode"),
                rs.getString("question_bank_title"), instant(rs, "completed_at"), rs.getLong("interview_ordinal")),
                ownerId, interviewId, ownerId);
        if (rows.isEmpty()) throw notFound();
        ReportTitleData title = rows.getFirst();
        return ReportTitleFormatter.format(title.username(), title.mode(), title.questionBankTitle(),
                title.completedAt(), title.ordinal());
    }
    public Optional<GapRow> findGap(UUID ownerId, UUID reportId) {
        return jdbc.query("SELECT * FROM report_gap_analyses WHERE report_id=? AND owner_id=?",
                this::mapGap, reportId, ownerId).stream().findFirst();
    }
    public Optional<PdfRow> findPdf(UUID ownerId, UUID reportId) {
        return jdbc.query("SELECT * FROM report_pdf_artifacts WHERE report_id=? AND owner_id=?",
                this::mapPdf, reportId, ownerId).stream().findFirst();
    }
    public Optional<PdfRow> findPdfForInterview(UUID ownerId, UUID interviewId) {
        return jdbc.query("""
                SELECT p.* FROM report_pdf_artifacts p
                JOIN interview_reports r ON r.id=p.report_id AND r.owner_id=p.owner_id
                WHERE r.interview_id=? AND r.owner_id=?
                """, this::mapPdf, interviewId, ownerId).stream().findFirst();
    }
    @Transactional
    public boolean deleteCompletedInterview(UUID ownerId, UUID interviewId) {
        return jdbc.update("DELETE FROM interviews WHERE id=? AND owner_id=? AND status='COMPLETE'",
                interviewId, ownerId) == 1;
    }
    @Transactional
    public void savePdf(UUID ownerId, UUID reportId, String key, String sha, long size, String sourceSha) {
        jdbc.update("DELETE FROM report_pdf_artifacts WHERE report_id=? AND owner_id=?", reportId, ownerId);
        jdbc.update("INSERT INTO report_pdf_artifacts(id,report_id,owner_id,object_key,sha256,size_bytes,source_sha256) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), reportId, ownerId, key, sha, size, sourceSha);
    }

    public List<ReportTask> staleCompleteInterviewsWithoutTask() {
        return jdbc.query("""
                SELECT i.id AS interview_id,i.owner_id,CAST(NULL AS UUID) AS id,'REPORT' AS task_type,'PENDING' AS status,
                  0 AS retry_count,0 AS attempt_count,NULL AS error_code,NULL AS error_message,i.completed_at AS queued_at,
                  NULL AS started_at,NULL AS completed_at,NULL AS duration_ms,NULL AS worker_id,NULL AS lease_until
                FROM interviews i LEFT JOIN report_tasks t ON t.interview_id=i.id AND t.owner_id=i.owner_id AND t.task_type='REPORT'
                WHERE i.status='COMPLETE' AND t.id IS NULL ORDER BY i.completed_at LIMIT 50
                """, this::mapSyntheticTask);
    }
    public void enqueueRecoveredInterviews(List<ReportTask> rows) {
        for (ReportTask row : rows) {
            try { jdbc.update("INSERT INTO report_tasks(id,interview_id,owner_id,task_type,status) VALUES (?, ?, ?, 'REPORT', 'PENDING')",
                    UUID.randomUUID(), row.interviewId(), row.ownerId()); }
            catch (org.springframework.dao.DuplicateKeyException ignored) { /* concurrent recovery won the unique-key race */ }
        }
    }

    private void verifyClaim(ReportTask task) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM report_tasks WHERE id=? AND owner_id=? AND status='PROCESSING' AND worker_id=? AND attempt_count=?",
                Integer.class, task.id(), task.ownerId(), task.workerId(), task.attemptCount());
        if (count == null || count != 1) throw new IllegalStateException("Report task claim is stale");
    }
    private void finishTask(ReportTask task, String status, String code, String message, long durationMs) {
        int changed = jdbc.update("""
                UPDATE report_tasks SET status=?,completed_at=?,duration_ms=?,error_code=?,error_message=?,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND owner_id=? AND status='PROCESSING' AND worker_id=? AND attempt_count=?
                """, status, Timestamp.from(Instant.now()), Math.max(0, durationMs), code, message,
                task.id(), task.ownerId(), task.workerId(), task.attemptCount());
        if (changed != 1) throw new IllegalStateException("Report task completion lost its claim");
    }
    private ReportTask mapTask(ResultSet rs, int row) throws SQLException {
        Number duration = (Number) rs.getObject("duration_ms");
        return new ReportTask(rs.getObject("id", UUID.class), rs.getObject("interview_id", UUID.class),
                rs.getObject("owner_id", UUID.class), rs.getString("task_type"), rs.getString("status"),
                rs.getInt("retry_count"), rs.getInt("attempt_count"), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs, "queued_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), duration == null ? null : duration.longValue(), rs.getString("worker_id"), instant(rs, "lease_until"));
    }
    private ReportTask mapSyntheticTask(ResultSet rs, int row) throws SQLException {
        return new ReportTask(UUID.randomUUID(), rs.getObject("interview_id", UUID.class), rs.getObject("owner_id", UUID.class),
                "REPORT", "PENDING", 0, 0, null, null, instant(rs,"queued_at"),null,null,null,null,null);
    }
    private ReportRow mapReport(ResultSet rs, int row) throws SQLException {
        return new ReportRow(rs.getObject("id", UUID.class), rs.getObject("interview_id", UUID.class),
                rs.getObject("owner_id", UUID.class), rs.getString("schema_version"), rs.getString("status"),
                rs.getString("content"), rs.getString("source_snapshot"), instant(rs,"created_at"),
                instant(rs,"completed_at"), rs.getLong("generation_duration_ms"));
    }
    private GapRow mapGap(ResultSet rs, int row) throws SQLException {
        return new GapRow(rs.getObject("id", UUID.class), rs.getObject("report_id", UUID.class),
                rs.getObject("interview_id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getString("schema_version"), rs.getString("content"), instant(rs,"created_at"));
    }
    private PdfRow mapPdf(ResultSet rs, int row) throws SQLException {
        return new PdfRow(rs.getObject("id", UUID.class), rs.getObject("report_id", UUID.class),
                rs.getObject("owner_id", UUID.class), rs.getString("object_key"), rs.getString("sha256"),
                rs.getLong("size_bytes"), rs.getString("source_sha256"), instant(rs,"created_at"));
    }
    private ReportSource.SourceTurn mapTurn(ResultSet rs, int row) throws SQLException {
        Number followup = (Number) rs.getObject("followup_index");
        return new ReportSource.SourceTurn(rs.getObject("id", UUID.class), rs.getInt("sequence_no"),
                rs.getString("turn_type"), rs.getInt("main_question_index"), followup == null ? null : followup.intValue(),
                rs.getString("source_question_id"), rs.getString("question"), rs.getString("answer"), instant(rs,"answered_at"));
    }
    private JsonNode read(String value) {
        try { return json.readTree(value); } catch (Exception e) { throw new IllegalStateException("Interview snapshot is invalid", e); }
    }
    private JsonNode readReportContent(String value) {
        try { return json.readTree(value); } catch (Exception e) { throw new IllegalStateException("Report content is invalid", e); }
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "报告不存在。");
    }
    public record ReportTask(UUID id, UUID interviewId, UUID ownerId, String type, String status,
            int retryCount, int attemptCount, String errorCode, String errorMessage, Instant queuedAt,
            Instant startedAt, Instant completedAt, Long durationMs, String workerId, Instant leaseUntil) {}
    public record ReportRow(UUID id, UUID interviewId, UUID ownerId, String schemaVersion, String status,
            String content, String sourceSnapshot, Instant createdAt, Instant completedAt, long durationMs) {}
    public record ReportHistoryRow(UUID reportId, UUID interviewId, String title, String username,
            String questionBankTitle, long interviewOrdinal, String mode, Instant completedAt,
            String reportContent, String reportStatus, String reportTaskStatus, String reportErrorMessage,
            String reportErrorCode, Long reportDurationMs,
            String gapTaskStatus, boolean hasGap, boolean gapApplicable, int questionCount) {}
    private record ReportTitleData(String username, String mode, String questionBankTitle, Instant completedAt, long ordinal) {}
    public record GapRow(UUID id, UUID reportId, UUID interviewId, UUID ownerId, String schemaVersion,
            String content, Instant createdAt) {}
    public record PdfRow(UUID id, UUID reportId, UUID ownerId, String objectKey, String sha256,
            long sizeBytes, String sourceSha256, Instant createdAt) {}
    private record InterviewRetryContext(String status, String mode) {}
}
