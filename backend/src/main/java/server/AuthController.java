package server;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final TotpService totpService;
    private final server.repository.UserRepo userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public Mono<Map<String, String>> register(@RequestBody AuthRequest request) {
    	return authService.register(request.username(), request.password())
                .map(token -> Map.of("token", token));
    }

    @PostMapping("/login")
    public Mono<Map<String, String>> login(@RequestBody AuthRequest request) {
        return authService.login(request.username(), request.password(), request.totpCode())
                .map(token -> Map.of("token", token));
    }

    /* =========================================================
       FR#17 — TOTP 2FA enrolment.
       Flow:
         1) POST /auth/2fa/enroll  with {password}     → returns secret + otpauth URI
         2) User scans URI / enters secret in Authenticator
         3) POST /auth/2fa/verify-enroll  with {totpCode} → flips totp_enabled=true
         4) Subsequent /auth/login MUST include totpCode
         5) DELETE /auth/2fa  with {password}          → disables 2FA
       ========================================================= */
    @PostMapping("/2fa/enroll")
    public Mono<Map<String, String>> enroll2fa(@RequestBody EnrollRequest req) {
        return userRepository.findByUsername(req.username())
                .switchIfEmpty(Mono.error(unauth("Bad credentials")))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(req.password(), user.getPassword())) {
                        return Mono.error(unauth("Bad credentials"));
                    }
                    String secret = totpService.newSecret();
                    user.setTotpSecret(secret);
                    user.setTotpEnabled(false); // stays off until verify-enroll
                    return userRepository.save(user)
                            .map(saved -> Map.of(
                                    "secret", secret,
                                    "otpauth", totpService.otpAuthUri(saved.getUsername(), secret)));
                });
    }

    @PostMapping("/2fa/verify-enroll")
    public Mono<Map<String, String>> verifyEnroll(@RequestBody VerifyEnrollRequest req) {
        return userRepository.findByUsername(req.username())
                .switchIfEmpty(Mono.error(unauth("Bad credentials")))
                .flatMap(user -> {
                    if (user.getTotpSecret() == null) {
                        return Mono.error(badRequest("Not enrolled — call /auth/2fa/enroll first"));
                    }
                    if (!totpService.verify(user.getTotpSecret(), req.totpCode())) {
                        return Mono.error(unauth("TOTP invalid"));
                    }
                    user.setTotpEnabled(true);
                    return userRepository.save(user)
                            .map(saved -> Map.of("status", "2fa enabled"));
                });
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/2fa")
    public Mono<Map<String, String>> disable2fa(@RequestBody EnrollRequest req) {
        return userRepository.findByUsername(req.username())
                .switchIfEmpty(Mono.error(unauth("Bad credentials")))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(req.password(), user.getPassword())) {
                        return Mono.error(unauth("Bad credentials"));
                    }
                    user.setTotpEnabled(false);
                    user.setTotpSecret(null);
                    return userRepository.save(user)
                            .map(saved -> Map.of("status", "2fa disabled"));
                });
    }

    private static org.springframework.web.server.ResponseStatusException unauth(String msg) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, msg);
    }
    private static org.springframework.web.server.ResponseStatusException badRequest(String msg) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, msg);
    }

    public record EnrollRequest(String username, String password) {}
    public record VerifyEnrollRequest(String username, String totpCode) {}
    
    @GetMapping("/me")
    public Mono<String> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (ctx.getAuthentication().getName() + "Role: " + ctx.getAuthentication().getAuthorities()));
    }
    
    /**
     * Provision a new admin. Restricted at the security filter chain to
     * existing admins only — the previous implementation was on the
     * {@code /auth/**} permitAll path, so anyone could create themselves an
     * admin account (privilege-escalation vulnerability). The very first
     * admin must be bootstrapped via the {@code ADMIN_BOOTSTRAP_USERNAME} /
     * {@code ADMIN_BOOTSTRAP_PASSWORD} environment variables (see
     * {@link AdminBootstrap}).
     */
    @PostMapping("/register/admin")
    public Mono<Map<String, String>> registerAdmin(@RequestBody AuthRequest request) {
    	return authService.registerAdmin(request.username(), request.password())
                .map(token -> Map.of("token", token));
    }

    @PostMapping("/login/admin")
    public Mono<Map<String, String>> loginAdmin(@RequestBody AuthRequest request) {
        return authService.loginAdmin(request.username(), request.password())
                .map(token -> Map.of("token", token));
    }

    /** {@code totpCode} optional — only required when the user has 2FA enabled. */
    record AuthRequest(String username, String password, String totpCode) {
        public AuthRequest(String username, String password) { this(username, password, null); }
    }
}
