package local.interviewmirror.backend.security;

import local.interviewmirror.backend.users.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountDetailsService implements UserDetailsService {
    private final UserRepository users;

    public AccountDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        return users.findByLoginIdentifier(identifier).map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
    }
}
