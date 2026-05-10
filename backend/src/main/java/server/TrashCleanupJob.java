package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

/**
 * Closes NFT#13: trash retains items for 30 days, then auto-purges.
 *
 * Implementation runs daily at 03:00 server time. Two passes:
 *  1) Files: select metadata rows with deleted_at older than 30 days, delete
 *     blob from disk (best-effort), then DELETE the row.
 *  2) Folders: select folder rows with deleted_at older than 30 days, walk
 *     each subtree, delete files (and blobs) inside, then delete folder rows
 *     and on-disk dirs in reverse-DFS order.
 *
 * The job never crashes the scheduler — every disk error is logged but not
 * rethrown. Worst case: orphan blobs on disk; cleanup re-runs daily.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TrashCleanupJob {

    /** Files older than this in the bin get hard-deleted. */
    private static final Duration RETENTION = Duration.ofDays(30);

    private final DatabaseClient databaseClient;

    @Value("${storage.path:./uploads}")
    private String storagePath;

    /**
     * Daily run at 03:00. Cron: sec min hour day month dow.
     * Override via {@code trash.cleanup.cron} env var (e.g. for tests).
     */
    @Scheduled(cron = "${trash.cleanup.cron:0 0 3 * * *}")
    public void runDaily() {
        Instant cutoff = Instant.now().minus(RETENTION);
        log.info("Trash cleanup starting; cutoff = {}", cutoff);
        purgeExpired(cutoff)
                .doOnSuccess(stats -> log.info(
                        "Trash cleanup done: {} files purged, {} folders purged",
                        stats.filesPurged(), stats.foldersPurged()))
                .doOnError(e -> log.error("Trash cleanup failed", e))
                .subscribe();
    }

    /** Public for tests — returns counts so assertions stay sync. */
    public Mono<Stats> purgeExpired(Instant cutoff) {
        return purgeExpiredFiles(cutoff)
                .zipWith(purgeExpiredFolders(cutoff), Stats::new);
    }

    private Mono<Long> purgeExpiredFiles(Instant cutoff) {
        // Fetch (id, storage_path) so we can delete the blob, then the row.
        return databaseClient.sql(
                        "SELECT id, storage_path FROM metadata " +
                        "WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
                .bind("cutoff", cutoff)
                .map(row -> new ExpiredFile(
                        row.get("id", java.util.UUID.class),
                        row.get("storage_path", String.class)))
                .all()
                // .thenReturn(f) so Mono<Void> turns into one signal per file
                // — otherwise .count() always returns 0.
                .flatMap(f -> purgeOneFile(f).thenReturn(f), 4)
                .count();
    }

    private Mono<Void> purgeOneFile(ExpiredFile f) {
        return Mono.fromRunnable(() -> {
                    if (f.storagePath() != null) {
                        try {
                            Files.deleteIfExists(Paths.get(f.storagePath()));
                        } catch (IOException e) {
                            log.warn("Trash cleanup: failed to delete blob {}: {}",
                                    f.storagePath(), e.toString());
                        }
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .then(databaseClient.sql("DELETE FROM metadata WHERE id = :id")
                        .bind("id", f.id())
                        .fetch().rowsUpdated())
                .then();
    }

    private Mono<Long> purgeExpiredFolders(Instant cutoff) {
        // Pull top-level expired folders. Their descendants get walked via
        // recursive CTE so we delete files+folders in the whole subtree.
        return databaseClient.sql(
                        "SELECT id, owner_id, name FROM folders " +
                        "WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
                .bind("cutoff", cutoff)
                .map(row -> new ExpiredFolder(
                        row.get("id", java.util.UUID.class),
                        row.get("owner_id", java.util.UUID.class),
                        row.get("name", String.class)))
                .all()
                .flatMap(f -> purgeOneFolderTree(f).thenReturn(f), 2)
                .count();
    }

    private Mono<Void> purgeOneFolderTree(ExpiredFolder root) {
        // 1) Delete metadata rows in subtree (blobs first, then rows).
        // 2) Delete folder rows in subtree (cascade FK takes care of order
        //    if we delete the root, but we want to clean the on-disk dir
        //    too). Walk dir reverse-DFS to remove children first.
        Mono<Void> deleteFiles = databaseClient.sql(
                        "SELECT id, storage_path FROM metadata " +
                        "WHERE owner_id = :owner AND folder_id IN (" +
                        "  WITH RECURSIVE tree AS (" +
                        "    SELECT id FROM folders WHERE id = :rootId" +
                        "    UNION ALL" +
                        "    SELECT f.id FROM folders f INNER JOIN tree ON f.parent_folder_id = tree.id" +
                        "  ) SELECT id FROM tree)")
                .bind("owner", root.ownerId())
                .bind("rootId", root.id())
                .map(row -> new ExpiredFile(
                        row.get("id", java.util.UUID.class),
                        row.get("storage_path", String.class)))
                .all()
                .flatMap(this::purgeOneFile, 4)
                .then();

        Mono<Long> deleteFolderRows = databaseClient.sql(
                        "DELETE FROM folders WHERE id = :id")
                .bind("id", root.id())
                .fetch().rowsUpdated();

        Mono<Void> wipeDisk = Mono.fromRunnable(() -> {
            // Folder lives under bin_<uid>/<name> on disk after delete.
            Path dir = Paths.get(storagePath, root.ownerId().toString())
                    .resolve("bin_" + root.ownerId().toString())
                    .resolve(root.name());
            if (!Files.exists(dir)) {
                return;
            }
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException e) {
                                log.warn("Trash cleanup: failed to delete {}: {}",
                                        p, e.toString());
                            }
                        });
            } catch (IOException e) {
                log.warn("Trash cleanup: failed to walk dir {}: {}",
                        dir, e.toString());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();

        return deleteFiles.then(deleteFolderRows).then(wipeDisk);
    }

    public record Stats(long filesPurged, long foldersPurged) {}

    private record ExpiredFile(java.util.UUID id, String storagePath) {}
    private record ExpiredFolder(java.util.UUID id, java.util.UUID ownerId, String name) {}
}
