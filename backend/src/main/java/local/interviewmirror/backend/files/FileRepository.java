package local.interviewmirror.backend.files;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FileRepository {
    private final JdbcTemplate jdbc;

    public FileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public StoredFile insert(UUID id, UUID ownerId, String objectKey, String filename,
                             String contentType, long sizeBytes) {
        jdbc.update("INSERT INTO stored_files(id, owner_id, object_key, original_filename, content_type, size_bytes) VALUES (?, ?, ?, ?, ?, ?)",
                id, ownerId, objectKey, filename, contentType, sizeBytes);
        return findByIdAndOwner(id, ownerId).orElseThrow();
    }

    public List<StoredFile> findAll(UUID ownerId) {
        return jdbc.query("SELECT * FROM stored_files WHERE owner_id = ? ORDER BY created_at DESC",
                this::map, ownerId);
    }

    public Optional<StoredFile> findByIdAndOwner(UUID id, UUID ownerId) {
        return jdbc.query("SELECT * FROM stored_files WHERE id = ? AND owner_id = ?",
                this::map, id, ownerId).stream().findFirst();
    }

    public boolean delete(UUID id, UUID ownerId) {
        return jdbc.update("DELETE FROM stored_files WHERE id = ? AND owner_id = ?", id, ownerId) == 1;
    }

    private StoredFile map(ResultSet rs, int row) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new StoredFile(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getString("object_key"), rs.getString("original_filename"), rs.getString("content_type"),
                rs.getLong("size_bytes"), created.toInstant());
    }
}
