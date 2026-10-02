package local.interviewmirror.backend.users;

import java.util.UUID;

public record UserAccount(UUID id, String username, String displayName, String passwordHash, boolean enabled) {}
