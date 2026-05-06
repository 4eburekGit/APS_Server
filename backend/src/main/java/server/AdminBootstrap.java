package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import server.repository.AdminRepo;

/**
 * Provisions the very first administrator from environment variables on
 * application start.
 *
 * <p>The HTTP endpoint {@code POST /auth/register/admin} requires an existing
 * admin to authenticate, which leaves a chicken-and-egg problem: how does the
 * first admin come into existence? Answer: this runner. Operators set
 * {@code ADMIN_BOOTSTRAP_USERNAME} and {@code ADMIN_BOOTSTRAP_PASSWORD} in
 * the deployment environment (or {@code .env} → docker-compose), and on each
 * boot we ensure such an admin exists.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Both env vars unset / blank → no-op (silent on dev boxes).</li>
 *   <li>One set, the other not → log warning, no-op.</li>
 *   <li>Admin with that username already exists → no-op (idempotent restart).</li>
 *   <li>Admin missing → create with the supplied password (BCrypt-hashed).</li>
 * </ul>
 *
 * The env-var values are read once at boot and never logged in plaintext.
 * Rotating the bootstrap password requires either (a) deleting the row and
 * restarting, or (b) using {@code POST /auth/register/admin} from another
 * admin session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    @Value("${admin.bootstrap.username:}")
    private String bootstrapUsername;

    @Value("${admin.bootstrap.password:}")
    private String bootstrapPassword;

    private final AdminRepo adminRepo;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        boolean hasUser = bootstrapUsername != null && !bootstrapUsername.isBlank();
        boolean hasPass = bootstrapPassword != null && !bootstrapPassword.isBlank();

        if (!hasUser && !hasPass) {
            log.debug("Admin bootstrap not configured (ADMIN_BOOTSTRAP_USERNAME/PASSWORD unset)");
            return;
        }
        if (hasUser != hasPass) {
            log.warn("Admin bootstrap is half-configured: both ADMIN_BOOTSTRAP_USERNAME and " +
                    "ADMIN_BOOTSTRAP_PASSWORD must be set. Skipping.");
            return;
        }

        final String username = bootstrapUsername.trim();
        adminRepo.findByUsername(username)
                .doOnNext(existing -> log.info("Admin '{}' already exists; bootstrap is a no-op", username))
                .switchIfEmpty(reactor.core.publisher.Mono.defer(() -> {
                    AdminEntity admin = new AdminEntity();
                    admin.setUsername(username);
                    admin.setPassword(passwordEncoder.encode(bootstrapPassword));
                    admin.setRole("ADMIN");
                    return adminRepo.save(admin)
                            .doOnNext(saved -> log.info("Bootstrapped admin '{}'", saved.getUsername()));
                }))
                .doOnError(e -> log.error("Failed to bootstrap admin '{}': {}", username, e.toString()))
                // Block here: ApplicationRunner runs synchronously on the boot
                // thread. We want startup to wait until provisioning is done so
                // the app can't accept traffic without the seeded admin.
                .block();
    }
}
