package local.interviewmirror.backend.resources;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceRepository {
    private static final RowMapper<DemoResource> MAPPER = (rs, row) -> new DemoResource(
            rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("resource_type"),
            rs.getString("title"), rs.getString("content"), rs.getObject("file_id", UUID.class),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DemoResource> findAll(UUID ownerId, String resourceType) {
        String sql = "SELECT * FROM demo_resources WHERE owner_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (resourceType != null && !resourceType.isBlank()) {
            sql += " AND resource_type = ?";
            args.add(resourceType);
        }
        sql += " ORDER BY updated_at DESC, id";
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    public Optional<DemoResource> findByIdAndOwner(UUID id, UUID ownerId) {
        return jdbc.query("SELECT * FROM demo_resources WHERE id = ? AND owner_id = ?", MAPPER, id, ownerId)
                .stream().findFirst();
    }

    public DemoResource insert(UUID id, UUID ownerId, ResourceRequests.Create body) {
        jdbc.update("INSERT INTO demo_resources (id, owner_id, resource_type, title, content, file_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, ownerId, body.resourceType(), body.title(), body.content() == null ? "" : body.content(), body.fileId());
        return findByIdAndOwner(id, ownerId).orElseThrow();
    }

    public Optional<DemoResource> update(UUID id, UUID ownerId, ResourceRequests.Update body) {
        int changed = jdbc.update("UPDATE demo_resources SET title = ?, content = ?, file_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND owner_id = ?",
                body.title(), body.content() == null ? "" : body.content(), body.fileId(), id, ownerId);
        return changed == 0 ? Optional.empty() : findByIdAndOwner(id, ownerId);
    }

    public boolean delete(UUID id, UUID ownerId) {
        return jdbc.update("DELETE FROM demo_resources WHERE id = ? AND owner_id = ?", id, ownerId) > 0;
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
