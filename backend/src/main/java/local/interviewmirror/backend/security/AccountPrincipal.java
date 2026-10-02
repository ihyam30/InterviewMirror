package local.interviewmirror.backend.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.users.UserAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AccountPrincipal implements UserDetails {
    private final UUID id;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final boolean enabled;

    public AccountPrincipal(UserAccount account) {
        this.id = account.id();
        this.username = account.username();
        this.displayName = account.displayName();
        this.passwordHash = account.passwordHash();
        this.enabled = account.enabled();
    }

    public UUID id() { return id; }
    public String displayName() { return displayName; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
