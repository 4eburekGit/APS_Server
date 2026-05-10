package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import net.coobird.thumbnailator.Thumbnails;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;

/**
 * FR#16 + NFT#5 — file preview as JPEG.
 *
 * Supported source types:
 *   • image/* (PNG, JPG, GIF, WEBP, BMP) — Thumbnailator scales to ?size px
 *   • application/pdf — PDFBox renders page 1 at 72 DPI, then thumbnails
 *   • everything else → 415
 *
 * Output is always JPEG (small, universally renderable in <img>). No on-disk
 * cache for now — the preview is cheap (≈100–300 ms for a 5 MB image,
 * 300–800 ms for a PDF page) and HTTP cache headers let the browser keep
 * a copy. Concurrency is implicit via Schedulers.boundedElastic.
 */
@RestController
@Slf4j
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class PreviewController {

    private final StorageController storageController;

    @Value("${preview.max-size:1024}")
    private int maxSize;

    @GetMapping("/{id}/preview")
    public Mono<ResponseEntity<Resource>> preview(
            @PathVariable UUID id,
            @RequestParam(value = "size", required = false, defaultValue = "512") int size) {
        final int s = Math.max(64, Math.min(maxSize, size));
        return storageController.getFileMetadata(id)
                .flatMap(meta -> Mono.fromCallable(() -> renderToJpeg(meta, s))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .body((Resource) new ByteArrayResource(bytes)));
    }

    /**
     * Text-content preview for JSON / TXT / LOG / source files.
     * Returns a JSON payload {@code {content, truncated, size}} so the UI
     * can show a "first N KB shown" hint when the file is bigger than the
     * cap. Cap defaults to 256 KB, hard-clamped to 1 MB.
     *
     * Accepts files whose stored content_type starts with text/, or any of
     * a known list of text-ish application MIMEs, or whose filename
     * extension is in TEXT_EXTS. UTF-8 decode is lenient — bytes that
     * aren't valid UTF-8 become replacement chars, so binaries don't crash
     * the endpoint when accidentally hit.
     */
    @GetMapping("/{id}/preview-text")
    public Mono<TextPreview> previewText(
            @PathVariable UUID id,
            @RequestParam(value = "max", required = false, defaultValue = "262144") int max) {
        final int cap = Math.max(1024, Math.min(1_048_576, max));
        return storageController.getFileMetadata(id)
                .flatMap(meta -> Mono.fromCallable(() -> readTextSnippet(meta, cap))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public record TextPreview(String content, boolean truncated, long size, String contentType) {}

    private byte[] renderToJpeg(FileMetaEntity meta, int size) {
        Path path = Paths.get(meta.getStoragePath());
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
        }
        String ct = meta.getContentType() == null ? "" : meta.getContentType().toLowerCase();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (ct.startsWith("image/")) {
                Thumbnails.of(path.toFile())
                        .size(size, size)
                        .outputFormat("jpg")
                        .outputQuality(0.85)
                        .toOutputStream(out);
            } else if (ct.equals("application/pdf")) {
                try (PDDocument doc = Loader.loadPDF(path.toFile())) {
                    if (doc.getNumberOfPages() == 0) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "PDF has no pages");
                    }
                    PDFRenderer renderer = new PDFRenderer(doc);
                    java.awt.image.BufferedImage page = renderer.renderImageWithDPI(0, 96);
                    Thumbnails.of(page)
                            .size(size, size)
                            .outputFormat("jpg")
                            .outputQuality(0.85)
                            .toOutputStream(out);
                }
            } else {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Preview not supported for " + ct);
            }
            return out.toByteArray();
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            log.warn("Preview generation failed for {}: {}", id(meta), e.toString());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Failed to render preview: " + e.getMessage());
        }
    }

    private static String id(FileMetaEntity m) { return m == null || m.getId() == null ? "?" : m.getId().toString(); }

    /** MIMEs and extensions we treat as text for preview purposes. */
    private static final java.util.Set<String> TEXTLIKE_MIMES = java.util.Set.of(
            "application/json",
            "application/xml",
            "application/yaml", "application/x-yaml",
            "application/javascript", "application/x-javascript",
            "application/typescript",
            "application/sql",
            "application/x-sh", "application/x-shellscript",
            "application/x-httpd-php",
            "application/toml",
            "application/x-properties",
            "application/x-www-form-urlencoded"
    );
    private static final java.util.Set<String> TEXT_EXTS = java.util.Set.of(
            "txt","log","md","markdown","csv","tsv","json","jsonl","xml",
            "yaml","yml","toml","ini","conf","cfg","env","properties",
            "html","htm","css","scss","less","js","mjs","ts","tsx","jsx",
            "py","rb","go","rs","java","kt","kts","scala","cs","cpp","c",
            "h","hpp","sh","bash","zsh","fish","ps1","psm1","sql","graphql","gql",
            "patch","diff","gitignore","dockerfile","makefile","cmake",
            "tex","bib","r","lua","pl","pm","php","ex","exs","clj"
    );

    private TextPreview readTextSnippet(FileMetaEntity meta, int cap) throws java.io.IOException {
        Path path = Paths.get(meta.getStoragePath());
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
        }
        if (!isTextLike(meta)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Not a text-like file: " + meta.getContentType());
        }
        long size = Files.size(path);
        // Read up to cap bytes — never load the entire file in one go.
        byte[] buf = new byte[(int) Math.min(size, cap)];
        try (var in = Files.newInputStream(path)) {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            if (read < buf.length) {
                byte[] shrunk = new byte[read];
                System.arraycopy(buf, 0, shrunk, 0, read);
                buf = shrunk;
            }
        }
        // Lenient UTF-8 decode: invalid sequences → U+FFFD, no exception
        // (the file might be Latin-1 or contain a BOM; we don't want to 500
        // on every weird text file).
        java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        String content;
        try {
            content = dec.decode(java.nio.ByteBuffer.wrap(buf)).toString();
        } catch (Exception e) {
            content = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        }
        // Strip a UTF-8 BOM if present so it doesn't render as an invisible
        // glyph at the start of every preview.
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        boolean truncated = size > buf.length;
        return new TextPreview(content, truncated, size, meta.getContentType());
    }

    private static boolean isTextLike(FileMetaEntity meta) {
        String ct = meta.getContentType() == null ? "" : meta.getContentType().toLowerCase();
        if (ct.startsWith("text/")) return true;
        if (TEXTLIKE_MIMES.contains(ct)) return true;
        // Fall back to extension — covers files whose content_type wasn't
        // sniffed correctly at upload (octet-stream).
        String name = meta.getFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                if (TEXT_EXTS.contains(ext)) return true;
            }
            // Special filenames without extensions
            String low = name.toLowerCase(java.util.Locale.ROOT);
            if (low.equals("readme") || low.equals("license") || low.equals("changelog")
                    || low.equals("authors") || low.equals("dockerfile") || low.equals("makefile")) {
                return true;
            }
        }
        return false;
    }
}
