package local.interviewmirror.backend.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT id, username, display_name, password_hash, enabled FROM app_users WHERE username = ?",
                (rs, row) -> new UserAccount(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("password_hash"), rs.getBoolean("enabled")), username)
                .stream().findFirst();
    }

    public Optional<UserAccount> findByLoginIdentifier(String identifier) {
        return jdbc.query("SELECT id, username, display_name, password_hash, enabled FROM app_users WHERE lower(username) = lower(?) OR lower(email) = lower(?)",
                (rs, row) -> new UserAccount(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("password_hash"), rs.getBoolean("enabled")),
                identifier, identifier).stream().findFirst();
    }

    @Transactional
    public UserAccount ensureDemoAccount(String username, String email, String displayName, String rawPassword, PasswordEncoder encoder) {
        Optional<UserAccount> existing = findByUsername(username);
        if (existing.isPresent()) {
            jdbc.update("UPDATE app_users SET email = ?, display_name = ?, password_hash = ?, enabled = TRUE WHERE id = ?",
                    email.toLowerCase(java.util.Locale.ROOT), displayName, encoder.encode(rawPassword), existing.get().id());
            return findByUsername(username).orElseThrow();
        }

        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, username, email, display_name, password_hash) VALUES (?, ?, ?, ?, ?)",
                id, username, email.toLowerCase(java.util.Locale.ROOT), displayName, encoder.encode(rawPassword));
        return findByUsername(username).orElseThrow();
    }

}
