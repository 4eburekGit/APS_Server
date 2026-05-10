package server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserStatsControllerTest {

    @Mock
    private DatabaseClient databaseClient;

    @InjectMocks
    private UserStatsController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "storageQuota", 10_000_000L);
    }

    private UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("alice");
        u.setRole("USER");
        u.setPassword("x");
        return u;
    }

    private AdminEntity admin() {
        AdminEntity a = new AdminEntity();
        a.setId(UUID.randomUUID());
        a.setUsername("root");
        a.setRole("ADMIN");
        a.setPassword("x");
        return a;
    }

    private static <T> Mono<T> withUser(Mono<T> m, IdentifiedPrincipal p) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                p, null, List.of(new SimpleGrantedAuthority("ROLE_" + ((p instanceof UserEntity)
                        ? "USER" : "ADMIN"))));
        return m.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    @SuppressWarnings("unchecked")
    private void mockSqlReturnsLong(long val) {
        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        RowsFetchSpec<Long> rows = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(Function.class))).thenAnswer(inv -> {
            // execute the lambda for coverage
            io.r2dbc.spi.Readable r = mock(io.r2dbc.spi.Readable.class);
            lenient().when(r.get(0, Long.class)).thenReturn(val);
            lenient().when(r.get("mime", String.class)).thenReturn("image/png");
            lenient().when(r.get("bytes", Long.class)).thenReturn(val);
            lenient().when(r.get("files", Long.class)).thenReturn(val);
            ((Function<io.r2dbc.spi.Readable, ?>) inv.getArgument(0)).apply(r);
            return rows;
        });
        lenient().when(rows.first()).thenReturn(Mono.just(val));
        // For bucket query: .all() returns a one-row Flux
        lenient().when(rows.all()).thenReturn(Flux.empty());
    }

    @Test
    void myStats_forUserReturnsAggregates() {
        mockSqlReturnsLong(42L);

        StepVerifier.create(withUser(controller.myStats(), user()))
                .assertNext(s -> {
                    assertEquals(42L, s.usedBytes());
                    assertEquals(10_000_000L, s.quotaBytes());
                    assertEquals(42L, s.fileCount());
                })
                .verifyComplete();
    }

    @Test
    void myStats_forAdminRejectedWith403() {
        // No DB stubs: admin path errors before any query runs.

        StepVerifier.create(withUser(controller.myStats(), admin()))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }
}
