package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * FR#15 + NFT#4 — search files by name / type / date range.
 *
 * Strategy (no Elasticsearch needed for the prototype):
 *   • Postgres GIN index on a generated {@code search_vec tsvector} column
 *     handles whole-word matches via to_tsquery on plainto-style queries.
 *   • pg_trgm GIN on filename handles substring / fuzzy matches via {@code %}.
 *   • Type filter is a {@code content_type LIKE prefix%} (e.g. "image/" to
 *     match all images).
 *   • Date range filters on uploaded_at.
 *
 * The query OR's the tsquery match with the trigram-similar match so the
 * UI doesn't need to pick which mode to use — typo-friendly + word-aware
 * out of the box. NFT#4 ≤1 s on 1M rows is realistic with the indexes.
 */
@RestController
@Slf4j
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class SearchController {

    private final DatabaseClient databaseClient;

    @GetMapping("/search")
    public Flux<FileMetaEntity> search(
            @RequestParam(value = "q",     required = false) String q,
            @RequestParam(value = "type",  required = false) String typePrefix,
            @RequestParam(value = "from",  required = false) String fromIso,
            @RequestParam(value = "to",    required = false) String toIso,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        if (limit < 1 || limit > 500) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be in [1,500]"));
        }
        Instant from = parseInstant(fromIso);
        Instant to   = parseInstant(toIso);
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getPrincipal())
                .flatMapMany(p -> {
                    if (!(p instanceof UserEntity user)) {
                        return Flux.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "Admins have no personal storage"));
                    }
                    UUID uid = user.getId();
                    String trimmed = q == null ? "" : q.trim();
                    boolean hasQ = !trimmed.isEmpty();

                    StringBuilder sql = new StringBuilder(
                            "SELECT id, filename, content_type, size, storage_path, " +
                            "       uploaded_at, owner_id, folder_id, deleted_at, updated_at " +
                            "FROM metadata WHERE owner_id = :uid AND deleted_at IS NULL ");
                    if (hasQ) {
                        // tsvector match OR trigram-similar (case-insensitive substring).
                        sql.append("AND (search_vec @@ plainto_tsquery('simple', :q) ")
                                .append("     OR filename ILIKE :qLike) ");
                    }
                    if (typePrefix != null && !typePrefix.isBlank()) {
                        sql.append("AND content_type LIKE :typePrefix ");
                    }
                    if (from != null) sql.append("AND uploaded_at >= :from ");
                    if (to   != null) sql.append("AND uploaded_at <= :to ");
                    if (hasQ) {
                        // Rank by ts_rank — best matches first.
                        sql.append("ORDER BY ts_rank(search_vec, plainto_tsquery('simple', :q)) DESC, " +
                                "       uploaded_at DESC ");
                    } else {
                        sql.append("ORDER BY uploaded_at DESC ");
                    }
                    sql.append("LIMIT :limit");

                    var spec = databaseClient.sql(sql.toString())
                            .bind("uid", uid)
                            .bind("limit", limit);
                    if (hasQ) {
                        spec = spec.bind("q", trimmed)
                                .bind("qLike", "%" + trimmed.replace("%", "\\%").replace("_", "\\_") + "%");
                    }
                    if (typePrefix != null && !typePrefix.isBlank()) {
                        spec = spec.bind("typePrefix", typePrefix.endsWith("%") ? typePrefix : typePrefix + "%");
                    }
                    if (from != null) spec = spec.bind("from", from);
                    if (to   != null) spec = spec.bind("to",   to);

                    return spec.map(row -> {
                        FileMetaEntity f = new FileMetaEntity();
                        f.setId(row.get("id", UUID.class));
                        f.setFilename(row.get("filename", String.class));
                        f.setContentType(row.get("content_type", String.class));
                        f.setSize(row.get("size", Long.class));
                        f.setStoragePath(row.get("storage_path", String.class));
                        f.setUploadedAt(row.get("uploaded_at", Instant.class));
                        f.setOwnerId(row.get("owner_id", UUID.class));
                        f.setFolderId(row.get("folder_id", UUID.class));
                        f.setDeletedAt(row.get("deleted_at", Instant.class));
                        f.setUpdatedAt(row.get("updated_at", Instant.class));
                        return f;
                    }).all();
                });
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // Accept full ISO instant or just date (YYYY-MM-DD).
            if (s.length() == 10) return Instant.parse(s + "T00:00:00Z");
            return Instant.parse(s);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bad date: " + s + " (use ISO-8601 or YYYY-MM-DD)");
        }
    }
}
