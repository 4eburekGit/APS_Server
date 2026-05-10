package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import server.repository.UserRepo;

import java.util.List;
import java.util.UUID;

/**
 * FR#18 (disk usage stats) + NFT#7 (≤2 s response) + NFT#21 (visualised
 * by mime type). Self-serve user endpoint — admin-only stats live in
 * {@link AdminPanelService}.
 */
@RestController
@Slf4j
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserStatsController {

    private final DatabaseClient databaseClient;
    private final UserRepo userRepository;

    @Value("${storage.quota:10737418240}")
    private Long storageQuota;

    /**
     * Lightweight self-info endpoint. Used by the Profile page to know
     * whether 2FA is already enrolled (so it doesn't repeatedly ask the
     * user to re-enrol). Returns 403 for admins (no personal storage).
     */
    @GetMapping("/me")
    public Mono<UserMe> me() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getPrincipal())
                .flatMap(p -> {
                    if (!(p instanceof UserEntity user)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "Admins have no personal profile"));
                    }
                    // Refetch to get fresh totp_enabled (the principal is loaded
                    // once at JWT validation; stale snapshot otherwise).
                    return userRepository.findById(user.getId())
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "User missing")))
                            .map(u -> new UserMe(
                                    u.getId(),
                                    u.getUsername(),
                                    u.getRole(),
                                    Boolean.TRUE.equals(u.getTotpEnabled())));
                });
    }

    public record UserMe(UUID id, String username, String role, boolean totpEnabled) {}

    @GetMapping("/stats")
    public Mono<UserStats> myStats() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getPrincipal())
                .flatMap(p -> {
                    if (!(p instanceof UserEntity user)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "Administrator accounts have no personal storage"));
                    }
                    UUID uid = user.getId();
                    Mono<Long> used = totalUsedFor(uid);
                    Mono<Long> count = totalCountFor(uid);
                    Mono<List<MimeBucket>> buckets = bucketsFor(uid);
                    return Mono.zip(used, count, buckets)
                            .map(t -> new UserStats(
                                    t.getT1(),
                                    storageQuota,
                                    t.getT2(),
                                    t.getT3()));
                });
    }

    private Mono<Long> totalUsedFor(UUID uid) {
        return databaseClient.sql(
                        "SELECT COALESCE(sum(size), 0) FROM metadata " +
                        "WHERE owner_id = :uid AND deleted_at IS NULL")
                .bind("uid", uid)
                .map(row -> row.get(0, Long.class))
                .first()
                .defaultIfEmpty(0L);
    }

    private Mono<Long> totalCountFor(UUID uid) {
        return databaseClient.sql(
                        "SELECT count(*) FROM metadata " +
                        "WHERE owner_id = :uid AND deleted_at IS NULL")
                .bind("uid", uid)
                .map(row -> row.get(0, Long.class))
                .first()
                .defaultIfEmpty(0L);
    }

    private Mono<List<MimeBucket>> bucketsFor(UUID uid) {
        // Group by content_type for the pie/histogram in NFT#21.
        // Empty bucket (NULL or "") collapses to "unknown".
        return databaseClient.sql(
                        "SELECT COALESCE(NULLIF(content_type, ''), 'unknown') AS mime, " +
                        "       sum(size) AS bytes, count(*) AS files " +
                        "FROM metadata WHERE owner_id = :uid AND deleted_at IS NULL " +
                        "GROUP BY mime ORDER BY bytes DESC")
                .bind("uid", uid)
                .map(row -> new MimeBucket(
                        row.get("mime", String.class),
                        zeroIfNull(row.get("bytes", Long.class)),
                        zeroIfNull(row.get("files", Long.class))))
                .all()
                .collectList();
    }

    private static long zeroIfNull(Long v) { return v == null ? 0L : v; }

    public record UserStats(
            long usedBytes,
            long quotaBytes,
            long fileCount,
            List<MimeBucket> byMime
    ) {}

    public record MimeBucket(String mime, long bytes, long files) {}
}
