package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.time.Instant;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * FR#19 (CSV export) + NFT#8 (≤3 s for 10k files).
 *
 * Builds the CSV in memory and returns it as a single ByteArrayResource.
 * The previous Flux<DataBuffer> body shape didn't always stream through
 * the WebFlux response writer — under some negotiation paths the body
 * was dropped and clients received a 0-byte file. ByteArrayResource is
 * the canonical, well-supported shape for binary/textual file responses.
 *
 * Memory budget: ≈100 bytes per row × 10k rows ≈1 MB — well below NFT#8's
 * 3 s window and within the per-request heap.
 *
 * Output is RFC-4180-ish (comma-separated, double-quote escaped, CRLF).
 */
@RestController
@Slf4j
@RequestMapping("/api/files/folders")
@RequiredArgsConstructor
public class CsvExportController {

    /** Header now includes a `path` column so recursive exports remain
     *  navigable (file at row N is at `<folder>/<path>/<filename>`). */
    private static final String HEADER = "path,filename,size,content_type,uploaded_at,updated_at\r\n";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final FolderController folderController;
    private final DatabaseClient databaseClient;

    @GetMapping(value = "/{folderId}/export.csv", produces = "text/csv;charset=UTF-8")
    public Mono<ResponseEntity<Resource>> exportFolder(@PathVariable UUID folderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ((IdentifiedPrincipal) ctx.getAuthentication().getPrincipal()).getId())
                .flatMap(uid -> folderController.getFolderContent(folderId)
                        .flatMap(content -> collectSubtree(uid, folderId)
                                .map(rows -> attachment(content.currentFolder().getName(),
                                        toCsvBytes(rows)))));
    }

    @GetMapping(value = "/root/export.csv", produces = "text/csv;charset=UTF-8")
    public Mono<ResponseEntity<Resource>> exportRoot() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ((IdentifiedPrincipal) ctx.getAuthentication().getPrincipal()).getId())
                .flatMap(uid -> folderController.getOrCreateRootFolder()
                        .flatMap(root -> collectSubtree(uid, root.getId())
                                .map(rows -> attachment(root.getName(), toCsvBytes(rows)))));
    }

    /** Per-row record so we can add a relative path column. */
    record CsvRow(String path, String filename, Long size, String contentType,
                  Instant uploadedAt, Instant updatedAt) {}

    /**
     * Walk the folder subtree (recursive CTE) and emit one CSV row per file.
     * Includes a relative path so users can rebuild the original tree from
     * the spreadsheet. Files in the root level get path = "" (just filename).
     *
     * Folder names are joined with `/` to form `path`. We compute path in SQL
     * via a recursive CTE that accumulates the segment chain — single query,
     * no N+1.
     */
    private Mono<java.util.List<CsvRow>> collectSubtree(UUID userId, UUID rootFolderId) {
        // Build recursive CTE walking from rootFolderId downward, tracking
        // the relative path via array_to_string(array_agg(name)). Postgres
        // accumulates an array column across the recursion, then we strip
        // the root-folder name from the front.
        String sql =
            "WITH RECURSIVE tree AS ( " +
            "  SELECT id, name, parent_folder_id, ARRAY[]::text[] AS rel_segs " +
            "    FROM folders WHERE id = :rootId AND owner_id = :owner " +
            "  UNION ALL " +
            "  SELECT f.id, f.name, f.parent_folder_id, t.rel_segs || f.name " +
            "    FROM folders f INNER JOIN tree t ON f.parent_folder_id = t.id " +
            "   WHERE f.owner_id = :owner " +
            ") " +
            "SELECT array_to_string(t.rel_segs, '/') AS path, " +
            "       m.filename, m.size, m.content_type, m.uploaded_at, m.updated_at " +
            "  FROM metadata m " +
            "  JOIN tree t ON t.id = m.folder_id " +
            " WHERE m.owner_id = :owner AND m.deleted_at IS NULL " +
            " ORDER BY path, m.filename";
        return databaseClient.sql(sql)
                .bind("owner", userId)
                .bind("rootId", rootFolderId)
                .map(row -> new CsvRow(
                        row.get("path", String.class),
                        row.get("filename", String.class),
                        row.get("size", Long.class),
                        row.get("content_type", String.class),
                        row.get("uploaded_at", Instant.class),
                        row.get("updated_at", Instant.class)))
                .all()
                .collectList();
    }

    static byte[] toCsvBytes(java.util.List<CsvRow> rows) {
        StringBuilder sb = new StringBuilder(HEADER.length() + (rows == null ? 0 : rows.size() * 140));
        sb.append(HEADER);
        if (rows != null) {
            for (CsvRow r : rows) {
                appendRow(sb, r);
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder sb, CsvRow r) {
        sb.append(quote(r.path() == null ? "" : r.path())).append(',');
        sb.append(quote(r.filename())).append(',');
        sb.append(r.size() == null ? 0 : r.size()).append(',');
        sb.append(quote(r.contentType())).append(',');
        sb.append(r.uploadedAt() == null ? "" : ISO.format(r.uploadedAt())).append(',');
        sb.append(r.updatedAt()  == null ? "" : ISO.format(r.updatedAt()));
        sb.append("\r\n");
    }

    /**
     * RFC-4180 quote: wrap fields with comma/quote/newline in double quotes,
     * doubling any internal double quotes. {@code null} → empty.
     */
    static String quote(String v) {
        if (v == null) return "";
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0
                && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) {
            return v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static ResponseEntity<Resource> attachment(String folderName, byte[] body) {
        String safe = folderName == null ? "folder" : folderName.replaceAll("[^A-Za-z0-9_.-]", "_");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(safe + ".csv")
                                .build()
                                .toString())
                .body(new ByteArrayResource(body));
    }
}
