package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import server.repository.AdminRepo;
import server.repository.UserRepo;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepo userRepo;

    @Mock
    private AdminRepo adminRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JWTHandler jwtHandler;

    @Mock
    private ReactiveAuthenticationManager authenticationManager;

    @Mock
    private FolderController folderController;

    @InjectMocks
    private AuthService authService;

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void register_shouldReturnJwtToken() {
        UserEntity savedUser = new UserEntity();
        savedUser.setId(UUID.randomUUID());
        savedUser.setUsername("alice");
        savedUser.setPassword("encoded");
        savedUser.setRole("USER");

        when(passwordEncoder.encode("secret")).thenReturn("encoded");
        when(userRepo.save(any(UserEntity.class))).thenReturn(Mono.just(savedUser));
        when(folderController.createRootFolder(any(UUID.class))).thenReturn(Mono.empty());
        when(folderController.createBinFolder(any(UUID.class))).thenReturn(Mono.empty());
        when(jwtHandler.generateToken(any(UserEntity.class))).thenReturn("jwt-token");

        StepVerifier.create(authService.register("alice", "secret"))
                .expectNext("jwt-token")
                .verifyComplete();
    }

    @Test
    void register_shouldPropagateErrorWhenRepoFails() {
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(userRepo.save(any(UserEntity.class)))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(authService.register("alice", "secret"))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_shouldReturnJwtToken() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPassword("encoded");
        user.setRole("USER");

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(authToken));
        when(jwtHandler.generateToken(any(UserEntity.class))).thenReturn("jwt-token");

        StepVerifier.create(authService.login("alice", "secret"))
                .expectNext("jwt-token")
                .verifyComplete();
    }

    @Test
    void login_shouldPropagateErrorOnBadCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenReturn(Mono.error(new RuntimeException("Bad credentials")));

        StepVerifier.create(authService.login("alice", "wrong"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void login_shouldAcceptAdminPrincipal() {
        // Unified login: admin credentials are also valid here. The SPA
        // routes based on the role claim in the returned JWT, so the service
        // no longer rejects admins at this layer.
        AdminEntity admin = new AdminEntity();
        admin.setId(UUID.randomUUID());
        admin.setUsername("adminUser");
        admin.setPassword("encoded");
        admin.setRole("ADMIN");

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(authToken));
        when(jwtHandler.generateToken(any(AdminEntity.class))).thenReturn("admin-jwt-token");

        StepVerifier.create(authService.login("adminUser", "secret"))
                .expectNext("admin-jwt-token")
                .verifyComplete();
    }

    // ── registerAdmin ─────────────────────────────────────────────────────────

    @Test
    void registerAdmin_shouldReturnJwtToken() {
        AdminEntity savedAdmin = new AdminEntity();
        savedAdmin.setId(UUID.randomUUID());
        savedAdmin.setUsername("adminUser");
        savedAdmin.setPassword("encoded");
        savedAdmin.setRole("ADMIN");

        when(passwordEncoder.encode("adminPass")).thenReturn("encoded");
        when(adminRepo.save(any(AdminEntity.class))).thenReturn(Mono.just(savedAdmin));
        when(jwtHandler.generateToken(any(AdminEntity.class))).thenReturn("admin-jwt-token");

        StepVerifier.create(authService.registerAdmin("adminUser", "adminPass"))
                .expectNext("admin-jwt-token")
                .verifyComplete();
    }

    @Test
    void registerAdmin_shouldPropagateErrorWhenRepoFails() {
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(adminRepo.save(any(AdminEntity.class)))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        StepVerifier.create(authService.registerAdmin("adminUser", "adminPass"))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── loginAdmin ────────────────────────────────────────────────────────────

    @Test
    void loginAdmin_shouldReturnJwtToken() {
        AdminEntity admin = new AdminEntity();
        admin.setId(UUID.randomUUID());
        admin.setUsername("adminUser");
        admin.setPassword("encoded");
        admin.setRole("ADMIN");

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(authToken));
        when(jwtHandler.generateToken(any(AdminEntity.class))).thenReturn("admin-jwt-token");

        StepVerifier.create(authService.loginAdmin("adminUser", "adminPass"))
                .expectNext("admin-jwt-token")
                .verifyComplete();
    }

    @Test
    void loginAdmin_shouldPropagateErrorOnBadCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenReturn(Mono.error(new RuntimeException("Bad credentials")));

        StepVerifier.create(authService.loginAdmin("adminUser", "wrong"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void loginAdmin_shouldAcceptUserPrincipal() {
        // The /auth/login/admin endpoint is now a deprecated alias for /auth/login
        // and accepts either kind of principal — both authentication paths route
        // through the same unified service method.
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPassword("encoded");
        user.setRole("USER");

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(authToken));
        when(jwtHandler.generateToken(any(UserEntity.class))).thenReturn("user-jwt-token");

        StepVerifier.create(authService.loginAdmin("alice", "secret"))
                .expectNext("user-jwt-token")
                .verifyComplete();
    }
}
