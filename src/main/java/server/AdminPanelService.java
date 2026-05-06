package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import server.repository.UserRepo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminPanelService {

    private final UserRepo userRepository;
    private final DatabaseClient databaseClient;

    @Value("${storage.path:./uploads}")
    private String storagePath;

    @Value("${storage.quota:10737418240}")
    private Long storageQuota;

    /** Public DTO returned by /admin/users — includes storage usage stats. */
    public record UserInfo(
            UUID id,
            String username,
            String role,
            long usedBytes,
            long quotaBytes,
            long fileCount
    ) {}

    /** List all users with their current storage usage and file count. */
    public Flux<UserInfo> listUsers() {
        return userRepository.findAll()
                .flatMap(user -> Mono.zip(
                                usedSpaceFor(user.getId()),
                                fileCountFor(user.getId())
                        ).map(t -> new UserInfo(
                                user.getId(),
                                user.getUsername(),
                                user.getRole(),
                                t.getT1(),
                                storageQuota,
                                t.getT2()
                        )),
                        // keep moderate concurrency to avoid issuing too many parallel queries
                        4);
    }

    /** Get info about a single user. */
    public Mono<UserInfo> getUser(UUID userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> Mono.zip(
                        usedSpaceFor(user.getId()),
                        fileCountFor(user.getId())
                ).map(t -> new UserInfo(
                        user.getId(),
                        user.getUsername(),
                        user.getRole(),
                        t.getT1(),
                        storageQuota,
                        t.getT2()
                )));
    }

    /**
     * Hard-delete a user.
     * <p>
     * The DB schema declares ON DELETE CASCADE for folders/metadata, so removing the
     * users row wipes those rows automatically. We then walk the user's storage
     * directory on disk and remove it recursively (best-effort — failures are logged
     * but do not roll back the DB delete since it has already happened).
     */
    public Mono<Void> deleteUser(UUID userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> userRepository.deleteById(user.getId())
                        .then(Mono.fromRunnable(() -> wipeUserStorage(user.getId()))
                                .subscribeOn(Schedulers.boundedElastic()))
                        .then())
                .doOnSuccess(v -> log.info("User {} deleted by admin", userId))
                .doOnError(e -> log.error("Failed to delete user {}", userId, e));
    }

    private Mono<Long> usedSpaceFor(UUID ownerId) {
        return databaseClient.sql(
                        "SELECT COALESCE(sum(size), 0) FROM metadata WHERE owner_id = :ownerId AND deleted_at IS NULL")
                .bind("ownerId", ownerId)
                .map(row -> row.get(0, Long.class))
                .first()
                .defaultIfEmpty(0L);
    }

    private Mono<Long> fileCountFor(UUID ownerId) {
        return databaseClient.sql(
                        "SELECT count(*) FROM metadata WHERE owner_id = :ownerId AND deleted_at IS NULL")
                .bind("ownerId", ownerId)
                .map(row -> row.get(0, Long.class))
                .first()
                .defaultIfEmpty(0L);
    }

    private void wipeUserStorage(UUID userId) {
        Path userDir = Paths.get(storagePath, userId.toString());
        if (!Files.exists(userDir)) {
            return;
        }
        try (var stream = Files.walk(userDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to wipe storage for user {}: {}", userId, e.getMessage());
        }
    }
}
