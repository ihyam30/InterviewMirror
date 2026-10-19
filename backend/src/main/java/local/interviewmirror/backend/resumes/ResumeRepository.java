package local.interviewmirror.backend.resumes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ResumeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ResumeRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public Optional<ResumeRecord> find(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM resumes WHERE user_id=? AND id=?", this::map, userId, id).stream().findFirst();
    }
    public Optional<ResumeRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM resumes WHERE id=?", this::map, id).stream().findFirst();
    }
    public Optional<ResumeRecord> duplicate(UUID userId, String hash) {
        return jdbc.query("SELECT * FROM resumes WHERE user_id=? AND file_hash=?", this::map, userId, hash).stream().findFirst();
    }
    @Transactional
    public ResumeRecord access(UUID userId, UUID id) {
        int changed = jdbc.update("UPDATE resumes SET access_count=access_count+1,last_accessed_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND delete_status='ACTIVE'", id, userId);
        if (changed != 1) throw conflict("简历正在删除，暂不能重复上传。");
        return find(userId, id).orElseThrow();
    }
    @Transactional
    public ResumeRecord create(UUID id, UUID userId, String hash, String filename, String type,
            long size, String key, String text, String structured) {
        jdbc.update("""
                INSERT INTO resumes(id,user_id,file_hash,title,original_filename,file_size,content_type,
                  storage_key,storage_url,resume_text,structured_content)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id, userId, hash, titleFromFilename(filename), filename, size, type,
                key, "/api/v1/resumes/" + id + "/file", text, structured);
        enqueue(id, 0, Instant.now());
        jdbc.update("DELETE FROM resume_object_cleanup WHERE resume_id IS NULL AND object_key=?", key);
        return find(userId, id).orElseThrow();
    }
    static String titleFromFilename(String filename) {
        String title = filename.replaceFirst("\\.[^.]+$", "").strip();
        return title.isBlank() ? filename : title;
    }
    public List<ResumeRecord> list(UUID userId, int page, int size, boolean usableOnly) {
        return jdbc.query("SELECT * FROM resumes WHERE user_id=?" + (usableOnly ? " AND delete_status='ACTIVE' AND length(trim(resume_text))>0" : "")
                + " ORDER BY uploaded_at DESC,id DESC LIMIT ? OFFSET ?", this::map, userId, size, (long) page * size);
    }
    public long count(UUID userId, boolean usableOnly) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM resumes WHERE user_id=?" + (usableOnly ? " AND delete_status='ACTIVE' AND length(trim(resume_text))>0" : ""), Long.class, userId);
    }
    public Map<UUID, ResumeViews.Analysis> latest(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        List<ResumeViews.Analysis> rows = new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT * FROM (
                  SELECT a.*,ROW_NUMBER() OVER(PARTITION BY resume_id ORDER BY analyzed_at DESC,id DESC) AS rn
                  FROM resume_analyses a WHERE user_id=:user AND resume_id IN (:ids)
                ) ranked WHERE rn=1
                """, Map.of("user", userId, "ids", ids), this::mapAnalysis);
        return rows.stream().collect(java.util.stream.Collectors.toMap(ResumeViews.Analysis::resumeId, a -> a));
    }
    public Map<UUID, Long> interviewCounts(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Long> result = new java.util.HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query("SELECT resume_id,COUNT(*) AS n FROM interviews WHERE owner_id=:user AND resume_id IN (:ids) AND delete_requested=FALSE GROUP BY resume_id",
                Map.of("user", userId, "ids", ids), rs -> { result.put(rs.getObject("resume_id", UUID.class), rs.getLong("n")); });
        return result;
    }
    public ResumeViews.Page<ResumeViews.Analysis> analyses(UUID userId, UUID id, int page, int size) {
        return new ResumeViews.Page<>(jdbc.query("SELECT * FROM resume_analyses WHERE resume_id=? AND user_id=? ORDER BY analyzed_at DESC,id DESC LIMIT ? OFFSET ?",
                this::mapAnalysis, id, userId, size, (long) page * size),
                jdbc.queryForObject("SELECT COUNT(*) FROM resume_analyses WHERE resume_id=? AND user_id=?", Long.class, id, userId), page, size);
    }
    public ResumeViews.Page<ResumeViews.Interview> interviews(UUID userId, UUID id, int page, int size) {
        return new ResumeViews.Page<>(jdbc.query("SELECT id,title,status,created_at FROM interviews WHERE resume_id=? AND owner_id=? AND delete_requested=FALSE ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new ResumeViews.Interview(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("status"), time(rs, "created_at")),
                id, userId, size, (long) page * size), interviewCounts(userId, List.of(id)).getOrDefault(id, 0L), page, size);
    }
    @Transactional
    public Optional<ResumeRecord> claim(UUID id) {
        return find(id).flatMap(row -> claim(id, row.analyzeRetryCount()));
    }
    @Transactional
    public Optional<ResumeRecord> claim(UUID id, int retry) {
        UUID attempt = UUID.randomUUID();
        if (jdbc.update("UPDATE resumes SET analyze_status='PROCESSING',analyze_attempt_id=?,analyze_updated_at=CURRENT_TIMESTAMP WHERE id=? AND delete_status='ACTIVE' AND analyze_status='PENDING' AND analyze_retry_count=?", attempt, id, retry) != 1) return Optional.empty();
        return find(id);
    }
    public boolean heartbeat(UUID id, UUID attempt) {
        return jdbc.update("UPDATE resumes SET analyze_updated_at=CURRENT_TIMESTAMP WHERE id=? AND analyze_attempt_id=? AND analyze_status='PROCESSING' AND delete_status='ACTIVE'", id, attempt) == 1;
    }
    public boolean backfill(ResumeRecord claim, String text, String structured) {
        return jdbc.update("UPDATE resumes SET resume_text=?,structured_content=?,analyze_updated_at=CURRENT_TIMESTAMP WHERE id=? AND analyze_attempt_id=? AND analyze_status='PROCESSING' AND delete_status='ACTIVE'",
                text, structured, claim.id(), claim.analyzeAttemptId()) == 1;
    }
    @Transactional
    public boolean complete(ResumeRecord claim, ResumeGradingService.Result result) {
        var current = lock(claim.id());
        if (current.isEmpty() || !valid(current.get(), claim)) return false;
        var score = result.scores();
        jdbc.update("""
                INSERT INTO resume_analyses(id,resume_id,user_id,attempt_id,rubric_version,overall_score,
                  content_score,structure_score,skill_match_score,expression_score,project_score,strengths_json,suggestions_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), claim.id(), claim.userId(), claim.analyzeAttemptId(), ResumeGradingService.RUBRIC_VERSION,
                score.total(), score.contentScore(), score.structureScore(), score.skillMatchScore(), score.expressionScore(), score.projectScore(),
                serialize(result.strengths()), serialize(result.suggestions()));
        jdbc.update("UPDATE resumes SET analyze_status='COMPLETED',analyze_error=NULL,analyze_updated_at=CURRENT_TIMESTAMP WHERE id=?", claim.id());
        jdbc.update("DELETE FROM resume_analysis_outbox WHERE resume_id=?", claim.id());
        return true;
    }
    @Transactional
    public void fail(ResumeRecord claim) {
        var current = lock(claim.id());
        if (current.isEmpty() || !valid(current.get(), claim)) return;
        int retry = current.get().analyzeRetryCount();
        boolean again = retry < 3;
        jdbc.update("UPDATE resumes SET analyze_status=?,analyze_attempt_id=NULL,analyze_error=?,analyze_retry_count=?,analyze_updated_at=CURRENT_TIMESTAMP WHERE id=?",
                again ? "PENDING" : "FAILED", again ? null : "简历分析失败，请检查模型配置或稍后重新分析。", again ? retry + 1 : retry, claim.id());
        jdbc.update("DELETE FROM resume_analysis_outbox WHERE resume_id=?", claim.id());
        if (again) enqueue(claim.id(), retry + 1, Instant.now().plusSeconds(5L << retry));
    }
    @Transactional
    public void reanalyze(UUID userId, UUID id) {
        reanalyze(userId, id, null);
    }
    @Transactional
    public void reanalyze(UUID userId, UUID id, String structuredContent) {
        ResumeRecord current = lock(id).filter(r -> r.userId().equals(userId)).orElseThrow(ResumeRepository::notFound);
        if (!"ACTIVE".equals(current.deleteStatus()) || List.of("PENDING", "PROCESSING").contains(current.analyzeStatus())) throw conflict("简历正在处理，请稍后重试。");
        if (structuredContent == null) {
            jdbc.update("UPDATE resumes SET analyze_status='PENDING',analyze_error=NULL,analyze_attempt_id=NULL,analyze_recovery_count=0,analyze_retry_count=0,analyze_updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        } else {
            jdbc.update("UPDATE resumes SET structured_content=?,analyze_status='PENDING',analyze_error=NULL,analyze_attempt_id=NULL,analyze_recovery_count=0,analyze_retry_count=0,analyze_updated_at=CURRENT_TIMESTAMP WHERE id=?", structuredContent, id);
        }
        jdbc.update("DELETE FROM resume_analysis_outbox WHERE resume_id=?", id);
        enqueue(id, 0, Instant.now());
    }
    @Transactional
    public int recover(Instant pendingBefore, Instant processingBefore) {
        var rows = jdbc.query("SELECT * FROM resumes WHERE delete_status='ACTIVE' AND ((analyze_status='PENDING' AND analyze_updated_at<?) OR (analyze_status='PROCESSING' AND analyze_updated_at<?)) ORDER BY analyze_updated_at LIMIT 50 FOR UPDATE",
                this::map, Timestamp.from(pendingBefore), Timestamp.from(processingBefore));
        for (var row : rows) {
            boolean terminal = row.analyzeRecoveryCount() >= 3;
            jdbc.update("UPDATE resumes SET analyze_status=?,analyze_attempt_id=NULL,analyze_error=?,analyze_retry_count=0,analyze_recovery_count=analyze_recovery_count+?,analyze_updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    terminal ? "FAILED" : "PENDING", terminal ? "简历分析多次恢复仍未完成，请手动重新分析。" : null, terminal ? 0 : 1, row.id());
            jdbc.update("DELETE FROM resume_analysis_outbox WHERE resume_id=?", row.id());
            if (!terminal) enqueue(row.id(), 0, Instant.now());
        }
        return rows.size();
    }
    public List<Outbox> outbox() {
        return jdbc.query("SELECT o.* FROM resume_analysis_outbox o JOIN resumes r ON r.id=o.resume_id WHERE o.delivered_at IS NULL AND o.available_at<=CURRENT_TIMESTAMP AND r.delete_status='ACTIVE' AND r.analyze_status='PENDING' ORDER BY o.available_at LIMIT 20",
                (rs, row) -> new Outbox(rs.getObject("id", UUID.class), rs.getObject("resume_id", UUID.class), rs.getInt("retry_count")));
    }
    public void delivered(UUID id) { jdbc.update("UPDATE resume_analysis_outbox SET delivered_at=CURRENT_TIMESTAMP WHERE id=?", id); }
    public void enqueue(UUID id, int retry, Instant available) {
        jdbc.update("INSERT INTO resume_analysis_outbox(id,resume_id,retry_count,available_at) VALUES (?,?,?,?)", UUID.randomUUID(), id, retry, Timestamp.from(available));
    }
    @Transactional
    public void cleanup(UUID resumeId, String key) {
        // Portable upsert by serialized parent lock (or unique constraint for detached compensation).
        if (jdbc.queryForObject("SELECT COUNT(*) FROM resume_object_cleanup WHERE object_key=?", Long.class, key) == 0) {
            jdbc.update("INSERT INTO resume_object_cleanup(id,resume_id,object_key,cleanup_kind,available_at) VALUES (?,?,?,?,?)", UUID.randomUUID(), resumeId, key,
                    resumeId == null ? "COMPENSATION" : "DELETE",
                    Timestamp.from(resumeId == null ? Instant.now().plusSeconds(600) : Instant.now()));
        }
    }
    @Transactional
    public void beginDelete(UUID userId, UUID id) {
        ResumeRecord row = lock(id).filter(r -> r.userId().equals(userId)).orElseThrow(ResumeRepository::notFound);
        jdbc.update("UPDATE resumes SET delete_status='DELETING',analyze_attempt_id=NULL WHERE id=?", id);
        // Lock all child interview rows: report saves and new interview creation use the same locks.
        jdbc.query("SELECT id FROM interviews WHERE resume_id=? AND owner_id=? FOR UPDATE", (rs, n) -> rs.getObject(1, UUID.class), id, userId);
        jdbc.update("UPDATE interviews SET delete_requested=TRUE WHERE resume_id=? AND owner_id=?", id, userId);
        jdbc.update("UPDATE report_tasks SET status='FAILED',worker_id=NULL,lease_until=NULL,attempt_count=attempt_count+1 WHERE interview_id IN (SELECT id FROM interviews WHERE resume_id=? AND owner_id=?)", id, userId);
        jdbc.update("DELETE FROM resume_analysis_outbox WHERE resume_id=?", id);
        cleanup(id, row.storageKey());
        var keys = jdbc.query("SELECT p.object_key FROM report_pdf_artifacts p JOIN interview_reports r ON r.id=p.report_id JOIN interviews i ON i.id=r.interview_id WHERE i.resume_id=? AND i.owner_id=?",
                (rs, n) -> rs.getString(1), id, userId);
        for (String key : keys) cleanup(id, key);
        jdbc.update("UPDATE resume_object_cleanup SET available_at=CURRENT_TIMESTAMP,last_error=NULL WHERE resume_id=? AND cleanup_kind='DELETE' AND completed_at IS NULL", id);
    }
    public List<Cleanup> pendingCleanup() {
        return jdbc.query("""
                SELECT * FROM resume_object_cleanup c WHERE completed_at IS NULL AND available_at<=CURRENT_TIMESTAMP
                  AND ((resume_id IS NOT NULL AND EXISTS (SELECT 1 FROM resumes r WHERE r.id=c.resume_id AND r.delete_status<>'ACTIVE')) OR (
                    NOT EXISTS (SELECT 1 FROM resumes r WHERE r.storage_key=c.object_key)
                    AND NOT EXISTS (SELECT 1 FROM report_pdf_artifacts p WHERE p.object_key=c.object_key)))
                ORDER BY available_at LIMIT 20
                """,
                (rs, n) -> new Cleanup(rs.getObject("id", UUID.class), rs.getObject("resume_id", UUID.class), rs.getString("object_key")));
    }
    public void cleaned(UUID id) { jdbc.update("UPDATE resume_object_cleanup SET completed_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=?", id); }
    @Transactional
    public void cleanupFailed(Cleanup task) {
        jdbc.update("UPDATE resume_object_cleanup SET attempts=attempts+1,last_error='对象存储清理失败',available_at=? WHERE id=?", Timestamp.from(Instant.now().plusSeconds(60)), task.id());
        if (task.resumeId() != null) jdbc.update("UPDATE resumes SET delete_status='DELETE_FAILED' WHERE id=? AND delete_status<>'ACTIVE'", task.resumeId());
    }
    public List<ResumeRecord> deletable() {
        return jdbc.query("SELECT * FROM resumes r WHERE delete_status<>'ACTIVE' AND NOT EXISTS (SELECT 1 FROM resume_object_cleanup c WHERE c.resume_id=r.id AND c.completed_at IS NULL) LIMIT 20", this::map);
    }
    public List<UUID> deletingInterviews(UUID id) {
        return jdbc.query("SELECT id FROM interviews WHERE resume_id=? AND delete_requested=TRUE", (rs, n) -> rs.getObject(1, UUID.class), id);
    }
    @Transactional
    public void finishDelete(UUID id) {
        var row = lock(id);
        if (row.isEmpty() || "ACTIVE".equals(row.get().deleteStatus())) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM resume_object_cleanup WHERE resume_id=? AND completed_at IS NULL", Long.class, id) != 0) return;
        jdbc.update("DELETE FROM interviews WHERE resume_id=? AND owner_id=? AND delete_requested=TRUE", id, row.get().userId());
        jdbc.update("DELETE FROM resumes WHERE id=?", id);
    }
    private Optional<ResumeRecord> lock(UUID id) {
        return jdbc.query("SELECT * FROM resumes WHERE id=? FOR UPDATE", this::map, id).stream().findFirst();
    }
    private static boolean valid(ResumeRecord current, ResumeRecord claim) {
        return "ACTIVE".equals(current.deleteStatus()) && "PROCESSING".equals(current.analyzeStatus())
                && claim.analyzeAttemptId().equals(current.analyzeAttemptId());
    }
    private String serialize(Object value) { return json.writeValueAsString(value); }
    private ResumeRecord map(ResultSet rs, int row) throws SQLException {
        return new ResumeRecord(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("file_hash"),
                rs.getString("title"), rs.getString("original_filename"), rs.getLong("file_size"), rs.getString("content_type"),
                rs.getString("storage_key"), rs.getString("resume_text"), rs.getString("structured_content"),
                time(rs, "uploaded_at"), rs.getLong("access_count"), rs.getString("analyze_status"), rs.getString("analyze_error"),
                time(rs, "analyze_updated_at"), rs.getInt("analyze_recovery_count"), rs.getInt("analyze_retry_count"), rs.getObject("analyze_attempt_id", UUID.class), rs.getString("delete_status"));
    }
    private ResumeViews.Analysis mapAnalysis(ResultSet rs, int row) throws SQLException {
        return new ResumeViews.Analysis(rs.getObject("id", UUID.class), rs.getObject("resume_id", UUID.class), rs.getString("rubric_version"),
                rs.getInt("overall_score"), rs.getInt("content_score"), rs.getInt("structure_score"), rs.getInt("skill_match_score"), rs.getInt("expression_score"), rs.getInt("project_score"),
                json.readTree(rs.getString("strengths_json")), json.readTree(rs.getString("suggestions_json")), time(rs, "analyzed_at"));
    }
    private static Instant time(ResultSet rs, String name) throws SQLException { return rs.getTimestamp(name).toInstant(); }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "简历不存在。"); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "RESUME_STATE_CONFLICT", message); }
    public record Outbox(UUID id, UUID resumeId, int retryCount) {}
    public record Cleanup(UUID id, UUID resumeId, String objectKey) {}
}
