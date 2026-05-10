package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void register_shouldWrapTokenInMap() {
        when(authService.register(anyString(), anyString())).thenReturn(Mono.just("jwt-token"));

        StepVerifier.create(authController.register(new AuthController.AuthRequest("alice", "secret")))
                .expectNext(Map.of("token", "jwt-token"))
                .verifyComplete();
    }

    @Test
    void register_shouldPropagateError() {
        when(authService.register(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Username taken")));

        StepVerifier.create(authController.register(new AuthController.AuthRequest("alice", "secret")))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_shouldWrapTokenInMap() {
        // Login now takes 3 args (totpCode is null when 2FA not enabled).
        when(authService.login(anyString(), anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Mono.just("jwt-token"));

        StepVerifier.create(authController.login(new AuthController.AuthRequest("alice", "secret")))
                .expectNext(Map.of("token", "jwt-token"))
                .verifyComplete();
    }

    @Test
    void login_shouldPropagateError() {
        when(authService.login(anyString(), anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Mono.error(new RuntimeException("Bad credentials")));

        StepVerifier.create(authController.login(new AuthController.AuthRequest("alice", "wrong")))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── currentUser ───────────────────────────────────────────────────────────

    @Test
    void me_shouldReturnCurrentUsernameWithRole() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPassword("encoded");
        user.setRole("USER");

        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        var ctx = new SecurityContextImpl(auth);

        // currentUser() returns: username + "Role: " + authorities
        StepVerifier.create(
                authController.currentUser()
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx)))
        )
                .expectNextMatches(s -> s.contains("alice") && s.contains("ROLE_USER"))
                .verifyComplete();
    }

    // ── registerAdmin ─────────────────────────────────────────────────────────

    @Test
    void registerAdmin_shouldWrapTokenInMap() {
        when(authService.registerAdmin(anyString(), anyString())).thenReturn(Mono.just("admin-token"));

        StepVerifier.create(authController.registerAdmin(new AuthController.AuthRequest("adminUser", "pass")))
                .expectNext(Map.of("token", "admin-token"))
                .verifyComplete();
    }

    @Test
    void registerAdmin_shouldPropagateError() {
        when(authService.registerAdmin(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Admin exists")));

        StepVerifier.create(authController.registerAdmin(new AuthController.AuthRequest("adminUser", "pass")))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── loginAdmin ────────────────────────────────────────────────────────────

    @Test
    void loginAdmin_shouldWrapTokenInMap() {
        when(authService.loginAdmin(anyString(), anyString())).thenReturn(Mono.just("admin-token"));

        StepVerifier.create(authController.loginAdmin(new AuthController.AuthRequest("adminUser", "pass")))
                .expectNext(Map.of("token", "admin-token"))
                .verifyComplete();
    }

    @Test
    void loginAdmin_shouldPropagateError() {
        when(authService.loginAdmin(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Bad credentials")));

        StepVerifier.create(authController.loginAdmin(new AuthController.AuthRequest("adminUser", "wrong")))
                .expectError(RuntimeException.class)
                .verify();
    }
}
