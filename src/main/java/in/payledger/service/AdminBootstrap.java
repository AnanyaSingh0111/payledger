package in.payledger.service;

import in.payledger.audit.AuditAction;
import in.payledger.audit.AuditService;
import in.payledger.entity.Role;
import in.payledger.entity.User;
import in.payledger.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-registration always creates a USER, so the first ADMIN comes from configuration:
 * set ADMIN_EMAIL and ADMIN_PASSWORD and the account is created on startup if it does not exist.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final AuditService auditService;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository userRepository,
                          UserService userService,
                          AuditService auditService,
                          @Value("${app.admin.email:}") String email,
                          @Value("${app.admin.password:}") String password) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.auditService = auditService;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        if (password.length() < 12) {
            log.warn("ADMIN_PASSWORD is shorter than 12 characters; admin bootstrap skipped");
            return;
        }
        if (userRepository.existsByEmail(UserService.normalizeEmail(email))) {
            return;
        }
        User admin = userService.create("Administrator", email, password, Role.ADMIN);
        auditService.record(null, AuditAction.ADMIN_BOOTSTRAPPED, "USER", admin.getId(), admin.getEmail());
        log.info("Bootstrapped admin user {}", admin.getEmail());
    }
}
