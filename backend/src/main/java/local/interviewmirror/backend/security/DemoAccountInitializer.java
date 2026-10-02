package local.interviewmirror.backend.security;

import local.interviewmirror.backend.users.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class DemoAccountInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String demo1Password;
    private final String demo2Password;

    public DemoAccountInitializer(UserRepository users, PasswordEncoder passwordEncoder,
            @Value("${DEMO1_PASSWORD:MirrorDemo1!}") String demo1Password,
            @Value("${DEMO2_PASSWORD:MirrorDemo2!}") String demo2Password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demo1Password = demo1Password;
        this.demo2Password = demo2Password;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.ensureDemoAccount("demo1", "demo1@local.interviewmirror", "演示用户一", demo1Password, passwordEncoder);
        users.ensureDemoAccount("demo2", "demo2@local.interviewmirror", "演示用户二", demo2Password, passwordEncoder);
    }
}
