package local.interviewmirror.backend.documents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentRepository {
    private static final String DOCUMENT_SELECT = """
            SELECT d.*, t.id AS task_id, t.status AS task_status, t.retry_count AS task_retry_count,
                   t.attempt_count AS task_attempt_count, t.error_code AS task_error_code,
                   t.error_message AS task_error_message, t.queued_at AS task_queued_at,
                   t.started_at AS task_started_at, t.completed_at AS task_completed_at,
                   t.duration_ms AS task_duration_ms, t.worker_id AS task_worker_id,
                   t.lease_until AS task_lease_until
            FROM managed_documents d
            LEFT JOIN document_parse_tasks t ON t.document_id = d.id AND t.owner_id = d.owner_id
            """;
    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public ManagedDocumentRecord create(UUID id, UUID ownerId, UUID fileId, DocumentType type,
                                         String title, String status, String content,
                                         boolean enqueueParse) {
        jdbc.update("""
                INSERT INTO managed_documents(id, owner_id, file_id, document_type, title, status,
                                              parsed_content, content, content_version)
                VALUES (?, ?, ?, ?, ?, ?, '{}', ?, 1)
                """, id, ownerId, fileId, type.name(), title, status, content);
        if (enqueueParse) {
            jdbc.update("""
                    INSERT INTO document_parse_tasks(id, document_id, owner_id, status)
                    VALUES (?, ?, ?, 'PENDING')
                    """, UUID.randomUUID(), id, ownerId);
        }
        return findByIdAndOwner(id, ownerId).orElseThrow();
    }

    public List<ManagedDocumentRecord> findAll(UUID ownerId, DocumentType type) {
        return jdbc.query(DOCUMENT_SELECT + " WHERE d.owner_id = ? AND d.document_type = ? ORDER BY d.updated_at DESC, d.id",
                this::mapDocument, ownerId, type.name());
    }

    public List<ManagedDocumentRecord> findConfirmed(UUID ownerId, DocumentType type) {
        return jdbc.query(DOCUMENT_SELECT + " WHERE d.owner_id = ? AND d.document_type = ? AND d.status = 'CONFIRMED' ORDER BY d.updated_at DESC",
                this::mapDocument, ownerId, type.name());
    }

    public Optional<ManagedDocumentRecord> findByIdAndOwner(UUID id, UUID ownerId) {
        return jdbc.query(DOCUMENT_SELECT + " WHERE d.id = ? AND d.owner_id = ?",
                this::mapDocument, id, ownerId).stream().findFirst();
    }

    public Optional<ParseTaskRecord> findTaskByIdAndOwner(UUID id, UUID ownerId) {
        return jdbc.query("SELECT * FROM document_parse_tasks WHERE id = ? AND owner_id = ?",
                this::mapTask, id, ownerId).stream().findFirst();
    }

    public boolean existsForFile(UUID fileId, UUID ownerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM managed_documents WHERE file_id = ? AND owner_id = ?",
                Integer.class, fileId, ownerId);
        return count != null && count > 0;
    }

    @Transactional
    public Optional<ParseTaskRecord> claimNext(String workerId, Duration lease) {
        List<ParseTaskRecord> pending = jdbc.query("""
                SELECT t.* FROM document_parse_tasks t
                JOIN managed_documents d ON d.id = t.document_id AND d.owner_id = t.owner_id
                WHERE t.status = 'PENDING' AND d.status = 'PENDING'
                ORDER BY t.queued_at, t.id
                LIMIT 10
                """, this::mapTask);
        for (ParseTaskRecord task : pending) {
            Instant started = Instant.now();
            int taskUpdated = jdbc.update("""
                    UPDATE document_parse_tasks
                    SET status = 'PROCESSING', attempt_count = attempt_count + 1, started_at = ?,
                        completed_at = NULL, duration_ms = NULL, error_code = NULL, error_message = NULL,
                        worker_id = ?, lease_until = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND owner_id = ? AND status = 'PENDING'
                    """, Timestamp.from(started), workerId, Timestamp.from(started.plus(lease)), task.id(), task.ownerId());
            if (taskUpdated != 1) continue;
            int documentUpdated = jdbc.update("""
                    UPDATE managed_documents SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND owner_id = ? AND status = 'PENDING'
                    """, task.documentId(), task.ownerId());
            if (documentUpdated != 1) throw new IllegalStateException("Claimed parse task has no pending document");
            return findTaskByIdAndOwner(task.id(), task.ownerId());
        }
        return Optional.empty();
    }

    @Transactional
    public boolean complete(UUID taskId, UUID documentId, UUID ownerId, String parsedContent,
                            Instant startedAt, String workerId, int attemptCount) {
        if (!lockCurrentClaim(taskId, documentId, ownerId, workerId, attemptCount)) return false;
        int documentUpdated = jdbc.update("""
                UPDATE managed_documents
                SET status = 'PARSED', parsed_content = ?, content = ?, content_version = content_version + 1,
                    confirmed_version = NULL, confirmed_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ? AND status = 'PROCESSING'
                """, parsedContent, parsedContent, documentId, ownerId);
        Instant completedAt = Instant.now();
        long durationMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        int taskUpdated = jdbc.update("""
                UPDATE document_parse_tasks
                SET status = 'SUCCEEDED', completed_at = ?, duration_ms = ?, error_code = NULL,
                    error_message = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND document_id = ? AND owner_id = ? AND status = 'PROCESSING'
                    AND worker_id = ? AND attempt_count = ?
                """, Timestamp.from(completedAt), durationMs, taskId, documentId, ownerId, workerId, attemptCount);
        if (documentUpdated != 1 || taskUpdated != 1) {
            throw new IllegalStateException("Parse completion lost its processing state");
        }
        return true;
    }

    @Transactional
    public boolean fail(UUID taskId, UUID documentId, UUID ownerId, String code, String safeMessage,
                        Instant startedAt, String workerId, int attemptCount) {
        if (!lockCurrentClaim(taskId, documentId, ownerId, workerId, attemptCount)) return false;
        int documentUpdated = jdbc.update("""
                UPDATE managed_documents SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ? AND status = 'PROCESSING'
                """, documentId, ownerId);
        Instant completedAt = Instant.now();
        long durationMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        int taskUpdated = jdbc.update("""
                UPDATE document_parse_tasks
                SET status = 'FAILED', completed_at = ?, duration_ms = ?, error_code = ?, error_message = ?,
                    lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND document_id = ? AND owner_id = ? AND status = 'PROCESSING'
                    AND worker_id = ? AND attempt_count = ?
                """, Timestamp.from(completedAt), durationMs, code, safeMessage,
                taskId, documentId, ownerId, workerId, attemptCount);
        if (documentUpdated != 1 || taskUpdated != 1) {
            throw new IllegalStateException("Parse failure update lost its processing state");
        }
        return true;
    }

    private boolean lockCurrentClaim(UUID taskId, UUID documentId, UUID ownerId,
                                     String workerId, int attemptCount) {
        return !jdbc.query("""
                SELECT id FROM document_parse_tasks
                WHERE id = ? AND document_id = ? AND owner_id = ? AND status = 'PROCESSING'
                    AND worker_id = ? AND attempt_count = ?
                FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class),
                taskId, documentId, ownerId, workerId, attemptCount).isEmpty();
    }

    @Transactional
    public int recoverExpired(Instant now) {
        List<ParseTaskRecord> expired = jdbc.query("""
                SELECT * FROM document_parse_tasks
                WHERE status = 'PROCESSING' AND lease_until <= ?
                FOR UPDATE SKIP LOCKED
                """, this::mapTask, Timestamp.from(now));
        for (ParseTaskRecord task : expired) {
            int taskUpdated = jdbc.update("""
                    UPDATE document_parse_tasks
                    SET status = 'PENDING', queued_at = ?, started_at = NULL, completed_at = NULL,
                        duration_ms = NULL, worker_id = NULL, lease_until = NULL,
                        error_code = 'WORKER_INTERRUPTED', error_message = '解析进程中断，任务已恢复等待',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND owner_id = ? AND status = 'PROCESSING' AND lease_until <= ?
                    """, Timestamp.from(now), task.id(), task.ownerId(), Timestamp.from(now));
            if (taskUpdated == 1) {
                jdbc.update("""
                        UPDATE managed_documents SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND owner_id = ? AND status = 'PROCESSING'
                        """, task.documentId(), task.ownerId());
            }
        }
        return expired.size();
    }

    @Transactional
    public boolean retry(UUID id, UUID ownerId) {
        int docUpdated = jdbc.update("""
                UPDATE managed_documents SET status = 'PENDING', confirmed_at = NULL, confirmed_version = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ? AND status = 'FAILED' AND file_id IS NOT NULL
                """, id, ownerId);
        if (docUpdated != 1) return false;
        int taskUpdated = jdbc.update("""
                UPDATE document_parse_tasks SET status = 'PENDING', retry_count = retry_count + 1,
                    queued_at = CURRENT_TIMESTAMP, started_at = NULL, completed_at = NULL, duration_ms = NULL,
                    error_code = NULL, error_message = NULL, worker_id = NULL, lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND owner_id = ? AND status = 'FAILED'
                """, id, ownerId);
        if (taskUpdated != 1) throw new IllegalStateException("Failed document is missing its retryable parse task");
        return true;
    }

    @Transactional
    public boolean saveContent(UUID id, UUID ownerId, int expectedVersion, String title, String content) {
        return jdbc.update("""
                UPDATE managed_documents
                SET title = ?, content = ?, content_version = content_version + 1,
                    status = CASE WHEN status = 'CONFIRMED' THEN 'PARSED' ELSE status END,
                    confirmed_version = NULL, confirmed_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ? AND content_version = ? AND status IN ('PARSED', 'CONFIRMED')
                """, title, content, id, ownerId, expectedVersion) == 1;
    }

    @Transactional
    public Optional<ManagedDocumentRecord> confirm(UUID id, UUID ownerId) {
        int updated = jdbc.update("""
                UPDATE managed_documents
                SET status = 'CONFIRMED', confirmed_version = content_version, confirmed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ? AND status = 'PARSED'
                """, id, ownerId);
        if (updated == 0) {
            Optional<ManagedDocumentRecord> existing = findByIdAndOwner(id, ownerId);
            if (existing.isPresent() && existing.get().status() == DocumentStatus.CONFIRMED) return existing;
            return Optional.empty();
        }
        return findByIdAndOwner(id, ownerId);
    }

    @Transactional
    public Optional<ManagedDocumentRecord> beginDelete(UUID id, UUID ownerId) {
        Optional<ManagedDocumentRecord> existing = findByIdAndOwner(id, ownerId);
        if (existing.isEmpty() || existing.get().status() == DocumentStatus.PROCESSING) return existing;
        int updated = jdbc.update("""
                UPDATE managed_documents SET status = 'DELETING', confirmed_at = NULL,
                    confirmed_version = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ? AND status <> 'PROCESSING'
                """, id, ownerId);
        if (updated != 1) return findByIdAndOwner(id, ownerId);
        jdbc.update("""
                UPDATE document_parse_tasks SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                    error_code = NULL, error_message = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND owner_id = ? AND status = 'PENDING'
                """, id, ownerId);
        return findByIdAndOwner(id, ownerId);
    }

    public void markDeleteFailed(UUID id, UUID ownerId) {
        jdbc.update("""
                UPDATE managed_documents SET status = 'DELETE_FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ? AND status = 'DELETING'
                """, id, ownerId);
    }

    public boolean deleteManual(UUID id, UUID ownerId) {
        return jdbc.update("DELETE FROM managed_documents WHERE id = ? AND owner_id = ? AND file_id IS NULL AND status = 'DELETING'",
                id, ownerId) == 1;
    }

    public Optional<ManagedDocumentRecord> findByParseTask(UUID taskId, UUID ownerId) {
        return jdbc.query(DOCUMENT_SELECT + " WHERE t.id = ? AND t.owner_id = ?",
                this::mapDocument, taskId, ownerId).stream().findFirst();
    }

    private ManagedDocumentRecord mapDocument(ResultSet rs, int row) throws SQLException {
        UUID taskId = rs.getObject("task_id", UUID.class);
        Integer confirmedVersion = (Integer) rs.getObject("confirmed_version");
        ParseTaskRecord task = taskId == null ? null : new ParseTaskRecord(taskId,
                rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getString("task_status"), rs.getInt("task_retry_count"), rs.getInt("task_attempt_count"),
                rs.getString("task_error_code"), rs.getString("task_error_message"),
                instant(rs, "task_queued_at"), instant(rs, "task_started_at"), instant(rs, "task_completed_at"),
                nullableLong(rs, "task_duration_ms"), rs.getString("task_worker_id"), instant(rs, "task_lease_until"));
        return new ManagedDocumentRecord(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getObject("file_id", UUID.class), DocumentType.valueOf(rs.getString("document_type")),
                rs.getString("title"), DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("parsed_content"), rs.getString("content"), rs.getInt("content_version"),
                confirmedVersion, instant(rs, "confirmed_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"), task);
    }

    private ParseTaskRecord mapTask(ResultSet rs, int row) throws SQLException {
        return new ParseTaskRecord(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("owner_id", UUID.class), rs.getString("status"), rs.getInt("retry_count"),
                rs.getInt("attempt_count"), rs.getString("error_code"), rs.getString("error_message"),
                instant(rs, "queued_at"), instant(rs, "started_at"), instant(rs, "completed_at"),
                nullableLong(rs, "duration_ms"), rs.getString("worker_id"), instant(rs, "lease_until"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
