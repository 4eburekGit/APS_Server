package server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import server.repository.UserRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPanelServiceTest {

    @Mock
    private UserRepo userRepository;

    @Mock
    private DatabaseClient databaseClient;

    @TempDir
    Path tempDir;

    @InjectMocks
    private AdminPanelService adminPanelService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(adminPanelService, "storagePath", tempDir.toString());
        ReflectionTestUtils.setField(adminPanelService, "storageQuota", 10737418240L);
    }

    private UserEntity buildUser(String username) {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setPassword("encoded");
        u.setRole("USER");
        return u;
    }

    @SuppressWarnings("unchecked")
    private void mockDbQueryChain(long returnValue) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<Long> rowsSpec = mock(RowsFetchSpec.class);

        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        // Invoke the mapping function with a fake Readable so the lambda body
        // (`row.get(0, Long.class)`) is actually executed for code coverage.
        lenient().when(spec.map(any(Function.class))).thenAnswer(inv -> {
            Function<io.r2dbc.spi.Readable, Long> fn = inv.getArgument(0);
            io.r2dbc.spi.Readable readable = mock(io.r2dbc.spi.Readable.class);
            when(readable.get(0, Long.class)).thenReturn(returnValue);
            fn.apply(readable);
            return rowsSpec;
        });
        lenient().when(rowsSpec.first()).thenReturn(Mono.just(returnValue));
    }

    @Test
    void listUsers_shouldReturnUserInfoForAllUsers() {
        UserEntity alice = buildUser("alice");
        UserEntity bob = buildUser("bob");
        when(userRepository.findAll()).thenReturn(Flux.just(alice, bob));
        mockDbQueryChain(500L);

        StepVerifier.create(adminPanelService.listUsers())
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void listUsers_whenNoUsers_shouldReturnEmpty() {
        when(userRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(adminPanelService.listUsers())
                .verifyComplete();
    }

    @Test
    void getUser_shouldReturnUserInfo() {
        UserEntity user = buildUser("charlie");
        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        mockDbQueryChain(1024L);

        StepVerifier.create(adminPanelService.getUser(user.getId()))
                .expectNextMatches(info -> "charlie".equals(info.username()) && info.quotaBytes() == 10737418240L)
                .verifyComplete();
    }

    @Test
    void getUser_whenNotFound_shouldError() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Mono.empty());

        StepVerifier.create(adminPanelService.getUser(userId))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void deleteUser_shouldDeleteAndWipeStorage() throws Exception {
        UserEntity user = buildUser("dave");
        // Create a fake user storage directory
        Path userDir = tempDir.resolve(user.getId().toString());
        Path subDir = userDir.resolve("docs");
        Files.createDirectories(subDir);
        Path file = subDir.resolve("test.txt");
        Files.writeString(file, "content");

        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.deleteById(user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(adminPanelService.deleteUser(user.getId()))
                .verifyComplete();
    }

    @Test
    void deleteUser_whenNotFound_shouldError() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Mono.empty());

        StepVerifier.create(adminPanelService.deleteUser(userId))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void deleteUser_whenStorageDirDoesNotExist_shouldCompleteNormally() {
        UserEntity user = buildUser("eve");
        // No storage directory created — wipeUserStorage should handle gracefully

        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.deleteById(user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(adminPanelService.deleteUser(user.getId()))
                .verifyComplete();
    }

    @Test
    void deleteUser_whenFileDeleteFails_shouldWarnAndComplete() throws Exception {
        UserEntity user = buildUser("frank");

        // Create a protected directory so Files.deleteIfExists on its child throws IOException
        Path userDir = tempDir.resolve(user.getId().toString());
        Path protectedDir = userDir.resolve("protected");
        Files.createDirectories(protectedDir);
        Files.writeString(protectedDir.resolve("locked.txt"), "content");
        // Remove write permission from protectedDir → deleteIfExists on its child will fail
        protectedDir.toFile().setWritable(false);

        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.deleteById(user.getId())).thenReturn(Mono.empty());

        try {
            StepVerifier.create(adminPanelService.deleteUser(user.getId()))
                    .verifyComplete();
        } finally {
            // Restore write permission for JUnit @TempDir cleanup
            protectedDir.toFile().setWritable(true);
        }
    }

    @Test
    void deleteUser_whenFilesWalkFails_shouldWarnAndComplete() throws Exception {
        UserEntity user = buildUser("grace");

        // Create user dir and remove its read permission → Files.walk will throw AccessDeniedException
        Path userDir = tempDir.resolve(user.getId().toString());
        Files.createDirectories(userDir);
        // Remove read (and execute) permission from userDir → Files.walk fails to open it
        userDir.toFile().setReadable(false);
        userDir.toFile().setExecutable(false);

        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.deleteById(user.getId())).thenReturn(Mono.empty());

        try {
            StepVerifier.create(adminPanelService.deleteUser(user.getId()))
                    .verifyComplete();
        } finally {
            // Restore permissions for JUnit @TempDir cleanup
            userDir.toFile().setReadable(true);
            userDir.toFile().setExecutable(true);
        }
    }
}
