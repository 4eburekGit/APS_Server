package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * FR#22 (audit log "who viewed/modified what when").
 *
 * Fire-and-forget logger. The bound methods return Mono&lt;Void&gt; that
 * controllers chain via .then(...) so a failed audit insert doesn't tank
 * the user-facing operation. We log warnings on failure rather than
 * propagating — audit reliability is best-effort, not load-bearing on the
 * happy path.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final DatabaseClient databaseClient;

    public Mono<Void> record(UUID userId, String action, String targetType, UUID targetId, String ip) {
        return databaseClient.sql(
                        "INSERT INTO audit_log(user_id, action, target_type, target_id, ip, ts) " +
                        "VALUES (:uid, :action, :ttype, :tid, :ip::inet, :ts)")
                .bind("uid", Parameter.fromOrEmpty(userId, UUID.class))
                .bind("action", action)
                .bind("ttype", Parameter.fromOrEmpty(targetType, String.class))
                .bind("tid", Parameter.fromOrEmpty(targetId, UUID.class))
                .bind("ip", Parameter.fromOrEmpty(ip, String.class))
                .bind("ts", Instant.now())
                .fetch().rowsUpdated()
                .doOnError(e -> log.warn("audit insert failed: {}", e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    public Flux<AuditEntity> forTarget(String targetType, UUID targetId) {
        return databaseClient.sql(
                        "SELECT id, user_id, action, target_type, target_id, host(ip) AS ip, ts " +
                        "FROM audit_log WHERE target_type = :tt AND target_id = :tid " +
                        "ORDER BY ts DESC LIMIT 200")
                .bind("tt", targetType)
                .bind("tid", targetId)
                .map(row -> {
                    AuditEntity a = new AuditEntity();
                    a.setId(row.get("id", UUID.class));
                    a.setUserId(row.get("user_id", UUID.class));
                    a.setAction(row.get("action", String.class));
                    a.setTargetType(row.get("target_type", String.class));
                    a.setTargetId(row.get("target_id", UUID.class));
                    a.setIp(row.get("ip", String.class));
                    a.setTs(row.get("ts", Instant.class));
                    return a;
                })
                .all();
    }
}
