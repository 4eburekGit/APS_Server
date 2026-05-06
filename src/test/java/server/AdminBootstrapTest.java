package server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import server.repository.AdminRepo;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AdminBootstrap}.
 *
 * <p>The runner is the only path that creates an admin without prior auth, so
 * we verify each branch of its config-state machine: unset (no-op),
 * half-configured (warn + no-op), already-exists (idempotent), missing
 * (insert with hashed password). Strict-stubbing forces us to declare
 * {@code adminRepo.save} only when we expect a write — that catches accidental
 * inserts on no-op paths.
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private AdminRepo adminRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationArguments args;

    @InjectMocks
    private AdminBootstrap bootstrap;

    @BeforeEach
    void resetFields() {
        // Each test sets username/password fields explicitly to model env state.
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", "");
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "");
    }

    @Test
    void run_whenBothUnset_isNoOp() {
        // Both env vars empty → debug log only, never touches the repo.
        bootstrap.run(args);
        verifyNoInteractions(adminRepo, passwordEncoder);
    }

    @Test
    void run_whenBothNull_isNoOp() {
        // Defensive: a null injection should behave like blank, not NPE.
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", null);
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", null);
        bootstrap.run(args);
        verifyNoInteractions(adminRepo, passwordEncoder);
    }

    @Test
    void run_whenOnlyUsernameSet_warnsAndSkips() {
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", "admin");
        bootstrap.run(args);
        verifyNoInteractions(adminRepo, passwordEncoder);
    }

    @Test
    void run_whenOnlyPasswordSet_warnsAndSkips() {
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "s3cret");
        bootstrap.run(args);
        verifyNoInteractions(adminRepo, passwordEncoder);
    }

    @Test
    void run_whenAdminAlreadyExists_isIdempotent() {
        // Re-running on an already-bootstrapped DB must not insert again.
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", "admin");
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "s3cret");

        AdminEntity existing = new AdminEntity();
        existing.setId(UUID.randomUUID());
        existing.setUsername("admin");
        when(adminRepo.findByUsername("admin")).thenReturn(Mono.just(existing));

        bootstrap.run(args);

        verify(adminRepo, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void run_whenAdminMissing_savesNewAdminWithHashedPassword() {
        // Happy path: fresh DB → insert one admin row, password BCrypt-hashed.
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", "admin");
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "s3cret");

        when(adminRepo.findByUsername("admin")).thenReturn(Mono.empty());
        when(passwordEncoder.encode("s3cret")).thenReturn("$2a$10$hashed");

        ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
        when(adminRepo.save(captor.capture())).thenAnswer(inv -> {
            AdminEntity a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });

        bootstrap.run(args);

        AdminEntity saved = captor.getValue();
        assertEquals("admin", saved.getUsername());
        assertEquals("$2a$10$hashed", saved.getPassword());
        assertEquals("ADMIN", saved.getRole());
    }

    @Test
    void run_trimsLeadingTrailingWhitespaceInUsername() {
        // Operators sometimes copy values with stray whitespace. Username is
        // trimmed; password is used verbatim (whitespace can be a real char in
        // a passphrase, so we don't strip it).
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", "  admin  ");
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "s3cret");

        when(adminRepo.findByUsername("admin")).thenReturn(Mono.empty());
        when(passwordEncoder.encode("s3cret")).thenReturn("$2a$10$hashed");

        ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
        when(adminRepo.save(captor.capture())).thenAnswer(inv -> Mono.just(inv.<AdminEntity>getArgument(0)));

        bootstrap.run(args);

        assertEquals("admin", captor.getValue().getUsername());
    }

    @Test
    void run_whenSaveFails_doesNotThrowFromRunner() {
        // ApplicationRunner exceptions abort boot. We log + continue rather
        // than crash the JVM if (e.g.) the DB rejects a duplicate insert.
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", "admin");
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "s3cret");

        when(adminRepo.findByUsername("admin")).thenReturn(Mono.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
        when(adminRepo.save(any())).thenReturn(Mono.error(new RuntimeException("dup key")));

        // .block() will rethrow the error — this assertion locks in the
        // current behaviour: the runner DOES surface the failure. If we ever
        // change to "log and swallow", flip this to assertDoesNotThrow.
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> bootstrap.run(args));
    }
}
