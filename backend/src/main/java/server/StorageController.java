package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import server.repository.FileMetaRepo;
import server.repository.FolderRepo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageController {

    private final FileMetaRepo metadataRepository;
    private final FolderRepo folderRepository;
    private final DatabaseClient databaseClient;

    @Value("${storage.path:./uploads}")
    private String storagePath;

    @Value("${storage.quota:10737418240}")
    private Long storageQuota;

    /**
     * Resolve a usable MIME for a file. Prefer the part-supplied type unless
     * it's missing or {@code application/octet-stream} (browsers' generic
     * fallback when they don't recognise the extension). For those cases,
     * sniff from the filename via {@link java.net.URLConnection#guessContentTypeFromName}
     * with a small in-house override table for common types Java's default
     * mime.types misses (md, csv, mkv, webp, etc).
     */
    static String resolveMime(String partCt, String filename) {
        boolean useless = partCt == null
                || partCt.isBlank()
                || partCt.equalsIgnoreCase("application/octet-stream")
                || partCt.equalsIgnoreCase("application/x-octet-stream");
        if (!useless) return partCt;
        String guessed = guessFromFilename(filename);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String guessFromFilename(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            String fromMap = MIME_OVERRIDES.get(ext);
            if (fromMap != null) return fromMap;
        }
        // JDK default maps a small set of standard exts (txt, html, png, ...)
        String fromJdk = java.net.URLConnection.guessContentTypeFromName(name);
        return fromJdk;
    }

    private static final java.util.Map<String, String> MIME_OVERRIDES = java.util.Map.ofEntries(
            java.util.Map.entry("md",   "text/markdown"),
            java.util.Map.entry("csv",  "text/csv"),
            java.util.Map.entry("json", "application/json"),
            java.util.Map.entry("yaml", "application/yaml"),
            java.util.Map.entry("yml",  "application/yaml"),
            java.util.Map.entry("toml", "application/toml"),
            java.util.Map.entry("webp", "image/webp"),
            java.util.Map.entry("avif", "image/avif"),
            java.util.Map.entry("svg",  "image/svg+xml"),
            java.util.Map.entry("ico",  "image/x-icon"),
            java.util.Map.entry("mkv",  "video/x-matroska"),
            java.util.Map.entry("webm", "video/webm"),
            java.util.Map.entry("mov",  "video/quicktime"),
            java.util.Map.entry("flac", "audio/flac"),
            java.util.Map.entry("ogg",  "audio/ogg"),
            java.util.Map.entry("m4a",  "audio/mp4"),
            java.util.Map.entry("opus", "audio/opus"),
            java.util.Map.entry("zip",  "application/zip"),
            java.util.Map.entry("rar",  "application/vnd.rar"),
            java.util.Map.entry("7z",   "application/x-7z-compressed"),
            java.util.Map.entry("tar",  "application/x-tar"),
            java.util.Map.entry("gz",   "application/gzip"),
            java.util.Map.entry("bz2",  "application/x-bzip2"),
            java.util.Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            java.util.Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            java.util.Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            java.util.Map.entry("doc",  "application/msword"),
            java.util.Map.entry("xls",  "application/vnd.ms-excel"),
            java.util.Map.entry("ppt",  "application/vnd.ms-powerpoint"),
            java.util.Map.entry("rtf",  "application/rtf"),
            java.util.Map.entry("epub", "application/epub+zip"),
            java.util.Map.entry("apk",  "application/vnd.android.package-archive"),
            java.util.Map.entry("deb",  "application/vnd.debian.binary-package"),
            java.util.Map.entry("rpm",  "application/x-rpm"),
            java.util.Map.entry("iso",  "application/x-iso9660-image"),
            java.util.Map.entry("dmg",  "application/x-apple-diskimage"),
            java.util.Map.entry("ttf",  "font/ttf"),
            java.util.Map.entry("otf",  "font/otf"),
            java.util.Map.entry("woff", "font/woff"),
            java.util.Map.entry("woff2","font/woff2")
    );
    
    public Mono<FileMetaEntity> saveFile(FilePart filePart, UUID folderId) throws RuntimeException {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser -> {
                    log.debug("Current user: {}", currentUser.getUsername());

                    Mono<FolderEntity> folderMono = (folderId == null)
                            ? folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(currentUser.getId(),"root_"+currentUser.getId().toString()).log("Writing into root")
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Root folder not found or access denied")))
                            : folderRepository.findByIdAndOwnerId(folderId, currentUser.getId()).log("Writing into folder")
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Folder not found or access denied")));

                    return folderMono.flatMap(folder -> {
                        // Sanitize the uploaded filename so a malicious client
                        // can't smuggle "../" segments and write outside the
                        // user's storage directory.
                        String originalFilename = PathSanitizer.sanitizeFilename(filePart.filename());
                        String fileId = UUID.randomUUID().toString();
                        String storedFilename = originalFilename;

                        return buildPhysicalPath(currentUser.getId(), folder)
                                .flatMap(userFolderPath -> {
                                    Path targetPath = userFolderPath.resolve(storedFilename);
                                    log.debug("Target file path: {}", targetPath);

                                    return Mono.fromCallable(() -> {
                                                Files.createDirectories(targetPath.getParent());
                                                log.debug("Directories created");
                                                return targetPath;
                                            })
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .flatMap(path -> {
                                                // Properly chained quota check (no .subscribe() side-channel).
                                                // We can't pre-read filePart.content() to size it (the buffers are
                                                // not replayable — transferTo would receive nothing). Instead we
                                                // do an upfront best-effort check using Content-Length and a
                                                // post-transfer verification with rollback.
                                                long declaredSize = filePart.headers().getContentLength();
                                                Mono<Long> usedMono = databaseClient.sql(
                                                                "SELECT COALESCE(sum(size), 0) FROM metadata WHERE owner_id = :ownerId AND deleted_at IS NULL")
                                                        .bind("ownerId", currentUser.getId())
                                                        .map(row -> row.get(0, Long.class))
                                                        .first()
                                                        .defaultIfEmpty(0L);
                                                return usedMono.flatMap(used -> {
                                                    if (declaredSize > 0 && used + declaredSize > storageQuota) {
                                                        log.error("Capacity exceeded (upfront, declared={}, used={})", declaredSize, used);
                                                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Size exceeds user capacity"));
                                                    }
                                                    log.debug("Quota check passed, starting file transfer");
                                                    return filePart.transferTo(path)
                                                            .doOnSuccess(v -> log.debug("File transfer completed"))
                                                            .doOnError(e -> log.error("File transfer failed", e))
                                                            .then(Mono.fromCallable(() -> Files.size(path))
                                                                    .subscribeOn(Schedulers.boundedElastic()))
                                                            .flatMap(actualSize -> {
                                                                if (used + actualSize > storageQuota) {
                                                                    log.error("Capacity exceeded after transfer (actual={}, used={})", actualSize, used);
                                                                    return Mono.fromRunnable(() -> {
                                                                                try { Files.deleteIfExists(path); }
                                                                                catch (IOException ignored) { /* best-effort rollback */ }
                                                                            }).subscribeOn(Schedulers.boundedElastic())
                                                                            .then(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Size exceeds user capacity")));
                                                                }
                                                                return Mono.just(actualSize);
                                                            });
                                                });
                                            })
                                            .flatMap(size -> {
                                                FileMetaEntity metadata = new FileMetaEntity();
                                                metadata.setId(UUID.fromString(fileId));
                                                metadata.setFilename(originalFilename);
                                                // Pick the most specific MIME we can: trust the part header
                                                // unless it's missing or "application/octet-stream" (the
                                                // generic browser fallback for unknown extensions). In that
                                                // case derive from the filename.
                                                String partCt = filePart.headers().getContentType() == null
                                                        ? null
                                                        : filePart.headers().getContentType().toString();
                                                metadata.setContentType(resolveMime(partCt, originalFilename));
                                                metadata.setSize(size);
                                                metadata.setStoragePath(targetPath.toString());
                                                metadata.setUploadedAt(Instant.now());
                                                metadata.setOwnerId(currentUser.getId());
                                                // Always store the resolved folder UUID (incl. the "root_<userId>" folder
                                                // when the upload is targeted to root). Storing NULL here meant files
                                                // uploaded to root never appeared in getRootContent (which queries by
                                                // folder_id = <rootUUID>).
                                                metadata.setFolderId(folder.getId());

                                                log.debug("Saving metadata to DB: {}", metadata);
                                                return databaseClient.sql("INSERT INTO metadata " +
                                                        "(id, filename, content_type, size, storage_path, uploaded_at, owner_id, folder_id) " +
                                                        "VALUES (:id, :filename, :contentType, :size, :storagePath, :uploadedAt, :ownerId, :folderId)")
                                                .bind("id", metadata.getId())
                                                .bind("filename", metadata.getFilename())
                                                .bind("contentType", metadata.getContentType())
                                                .bind("size", metadata.getSize())
                                                .bind("storagePath", metadata.getStoragePath())
                                                .bind("uploadedAt", metadata.getUploadedAt())
                                                .bind("ownerId", metadata.getOwnerId())
                                                .bind("folderId", metadata.getFolderId())
                                                .filter((statement, executeFunction) -> statement.returnGeneratedValues("id").execute())
                                                .fetch()
                                                .first()
                                                .map(row -> metadata);
                                            });
                                });
                    });
                })
                .doOnSuccess(m -> {
                    if (m == null) {
                        log.error("Received null from save operation!");
                    } else {
                        log.info("File saved: {} by user {}", m.getFilename(), m.getOwnerId());
                    }
                })
                .doOnError(e -> log.error("Failed to save file", e));
    }
    
    /* =========================================================
       FR#1 (folder upload) + FR#20 (drag-and-drop). For each FilePart in
       the multipart request, treats {@code filePart.filename()} as the
       FULL relative path (slash-separated). Creates any intermediate
       folders under {@code rootFolderId} (or user root if null), then
       saves the file in the leaf folder. Concurrency capped at 4 so a
       large tree doesn't drain the R2DBC pool.

       Frontend convention: when sending via FormData, set the third arg
       of {@code formData.append('files', file, relativePath)} so the
       browser writes the slash-bearing path into the part's filename
       header (Chrome respects this; Firefox 78+ too).
       ========================================================= */
    public Flux<FileMetaEntity> saveFileTree(Flux<FilePart> parts, UUID rootFolderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMapMany(currentUser -> {
                    UUID userId = currentUser.getId();
                    Mono<FolderEntity> rootMono = (rootFolderId == null)
                            ? folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(
                                    userId, "root_" + userId.toString())
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "Root folder not found")))
                            : folderRepository.findByIdAndOwnerId(rootFolderId, userId)
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "Folder not found or access denied")));
                    // Per-request cache of resolved (parent_id + "/" + name) → Mono<FolderEntity>.
                    // Without this, concurrency=4 inside flatMap below races N parallel
                    // existsByOwnerIdAndParentFolderIdAndName calls for the same (parent, name)
                    // — they all see exists=false, all save() in parallel, all but one hit
                    // unique-constraint "uq_folder_name_parent_owner" → 500. ConcurrentHashMap +
                    // .cache() guarantees the save runs at most once per directory key.
                    final java.util.concurrent.ConcurrentHashMap<String, Mono<FolderEntity>> dirCache =
                            new java.util.concurrent.ConcurrentHashMap<>();
                    return rootMono.flatMapMany(rootFolder ->
                            parts.flatMap(part ->
                                    saveOneTreePart(userId, rootFolder, part, dirCache), 4));
                });
    }

    /** Resolve relative-path leaf folder, then call {@link #saveFile}. */
    private Mono<FileMetaEntity> saveOneTreePart(UUID userId, FolderEntity rootFolder, FilePart part,
                                                 java.util.concurrent.ConcurrentHashMap<String, Mono<FolderEntity>> dirCache) {
        // The full relative path comes in part.filename(). Split off the
        // last segment as the actual file name; everything before is folder
        // segments to ensure-or-create under rootFolder.
        String raw = part.filename() == null ? "" : part.filename();
        // Normalise back-slashes and strip leading slashes.
        String norm = raw.replace('\\', '/').replaceFirst("^/+", "");
        int lastSlash = norm.lastIndexOf('/');
        if (lastSlash < 0) {
            // No relative path — degenerate to a flat upload into rootFolder.
            return saveFile(part, rootFolder.getParentFolderId() == null
                    && ("root_" + userId).equals(rootFolder.getName())
                            ? null
                            : rootFolder.getId());
        }
        // saveFile() calls PathSanitizer.sanitizeFilename(part.filename()),
        // which rejects slashes. Strip relative-path prefix so the downstream
        // sees only the basename.
        String basename = norm.substring(lastSlash + 1);
        String[] segments = norm.substring(0, lastSlash).split("/");
        FilePart leafPart = new RenamedFilePart(part, basename);
        return ensureFolderPath(userId, rootFolder, segments, dirCache)
                .flatMap(leaf -> saveFile(leafPart, leaf.getId()));
    }

    /**
     * Thin {@link FilePart} delegate that overrides {@code filename()} so we
     * can pass the basename to {@link #saveFile} after folder-tree upload
     * stripped the relative-path prefix. All other methods proxy through.
     */
    private static final class RenamedFilePart implements FilePart {
        private final FilePart delegate;
        private final String name;
        RenamedFilePart(FilePart delegate, String name) {
            this.delegate = delegate; this.name = name;
        }
        @Override public String filename() { return name; }
        @Override public String name() { return delegate.name(); }
        @Override public org.springframework.http.HttpHeaders headers() { return delegate.headers(); }
        @Override public reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> content() { return delegate.content(); }
        @Override public Mono<Void> transferTo(java.nio.file.Path dest) { return delegate.transferTo(dest); }
        @Override public Mono<Void> transferTo(java.io.File dest) { return delegate.transferTo(dest); }
        @Override public Mono<Void> delete() { return delegate.delete(); }
    }

    /**
     * Walk segments, get-or-create each folder under the previous one.
     * Caches resolved Mono per (parentId + "/" + name) so concurrent uploads
     * touching the same directory share one INSERT and don't trip
     * {@code uq_folder_name_parent_owner}. The cache is per saveFileTree
     * call (request-scoped). On {@link DuplicateKeyException} we re-SELECT
     * — covers the ABA case where another request inserted between our
     * existsByOwner check and save.
     */
    private Mono<FolderEntity> ensureFolderPath(UUID userId, FolderEntity start, String[] segments,
                                                java.util.concurrent.ConcurrentHashMap<String, Mono<FolderEntity>> dirCache) {
        Mono<FolderEntity> chain = Mono.just(start);
        for (String seg : segments) {
            if (seg == null || seg.isBlank() || ".".equals(seg) || "..".equals(seg)) {
                continue;   // skip junk; PathSanitizer would also reject
            }
            final String safe = PathSanitizer.sanitizeFolderName(seg);
            chain = chain.flatMap(parent -> {
                String key = parent.getId() + "/" + safe;
                return dirCache.computeIfAbsent(key, k ->
                        resolveOrCreateFolder(userId, parent, safe).cache());
            });
        }
        return chain;
    }

    /** Single-step resolve-or-create (no chain), with race-safe duplicate handling. */
    private Mono<FolderEntity> resolveOrCreateFolder(UUID userId, FolderEntity parent, String safe) {
        Mono<FolderEntity> findExisting = databaseClient.sql(
                        "SELECT id, name, parent_folder_id, owner_id, created_at, deleted_at " +
                        "FROM folders WHERE owner_id = :owner AND parent_folder_id = :parent AND name = :name")
                .bind("owner", userId)
                .bind("parent", parent.getId())
                .bind("name", safe)
                .map(row -> {
                    FolderEntity f = new FolderEntity();
                    f.setId(row.get("id", UUID.class));
                    f.setName(row.get("name", String.class));
                    f.setParentFolderId(row.get("parent_folder_id", UUID.class));
                    f.setOwnerId(row.get("owner_id", UUID.class));
                    f.setCreatedAt(row.get("created_at", Instant.class));
                    f.setDeletedAt(row.get("deleted_at", Instant.class));
                    return f;
                })
                .one();
        return folderRepository.existsByOwnerIdAndParentFolderIdAndName(userId, parent.getId(), safe)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) return findExisting;
                    FolderEntity n = new FolderEntity();
                    n.setName(safe);
                    n.setOwnerId(userId);
                    n.setParentFolderId(parent.getId());
                    n.setCreatedAt(Instant.now());
                    return buildPhysicalPath(userId, parent)
                            .flatMap(parentPath -> Mono.fromCallable(() -> {
                                        Files.createDirectories(parentPath.resolve(safe));
                                        return parentPath.resolve(safe);
                                    }).subscribeOn(Schedulers.boundedElastic())
                                    .then(folderRepository.save(n)))
                            // Lost race: another request inserted between our exists check
                            // and save → unique-constraint violation. Recover by selecting
                            // the row that won the race.
                            .onErrorResume(org.springframework.dao.DuplicateKeyException.class, e -> findExisting);
                });
    }

    public Mono<FileMetaEntity> updateFile(UUID fileId, FilePart filePart) throws RuntimeException {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser -> {
                    log.debug("Current user: {}", currentUser.getUsername());
                    return metadataRepository.findByIdAndOwnerId(fileId, currentUser.getId())
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")))
                            .flatMap(file -> {
                                Mono<FolderEntity> folderMono = (file.getFolderId() == null)
                                        ? folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(currentUser.getId(), "root_" + currentUser.getId().toString())
                                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Root folder not found or access denied")))
                                        : folderRepository.findByIdAndOwnerId(file.getFolderId(), currentUser.getId())
                                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")));

                                String newFilename = PathSanitizer.sanitizeFilename(filePart.filename());
                                return folderMono.flatMap(folder ->
                                        buildPhysicalPath(currentUser.getId(), folder)
                                                .flatMap(userFolderPath -> {
                                                    Path targetPath = userFolderPath.resolve(file.getFilename());
                                                    log.debug("Target file path: {}", targetPath);
                                                    return Mono.fromCallable(() -> {
                                                                Files.createDirectories(targetPath.getParent());
                                                                log.debug("Directories created");
                                                                return targetPath;
                                                            })
                                                            .subscribeOn(Schedulers.boundedElastic())
                                                            .flatMap(path -> {
                                                                // Properly chained quota check (no .subscribe() side-channel,
                                                                // no race condition). The existing row is excluded from the
                                                                // sum so the new size is compared net of the old size.
                                                                long declaredSize = filePart.headers().getContentLength();
                                                                Mono<Long> usedMono = databaseClient.sql(
                                                                                "SELECT COALESCE(sum(size), 0) FROM metadata WHERE owner_id = :ownerId AND id <> :id AND deleted_at IS NULL")
                                                                        .bind("ownerId", currentUser.getId())
                                                                        .bind("id", file.getId())
                                                                        .map(row -> row.get(0, Long.class))
                                                                        .first()
                                                                        .defaultIfEmpty(0L);
                                                                return usedMono.flatMap(used -> {
                                                                    if (declaredSize > 0 && used + declaredSize > storageQuota) {
                                                                        log.error("Capacity exceeded (upfront, declared={}, used={})", declaredSize, used);
                                                                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Size exceeds user capacity"));
                                                                    }
                                                                    log.debug("Quota check passed, starting file transfer");
                                                                    return filePart.transferTo(path)
                                                                            .doOnSuccess(v -> log.debug("File transfer completed"))
                                                                            .doOnError(e -> log.error("File transfer failed", e))
                                                                            .then(Mono.fromCallable(() -> Files.size(path))
                                                                                    .subscribeOn(Schedulers.boundedElastic()))
                                                                            .flatMap(actualSize -> {
                                                                                if (used + actualSize > storageQuota) {
                                                                                    log.error("Capacity exceeded after transfer (actual={}, used={})", actualSize, used);
                                                                                    return Mono.fromRunnable(() -> {
                                                                                                try { Files.deleteIfExists(path); }
                                                                                                catch (IOException ignored) { /* best-effort rollback */ }
                                                                                            }).subscribeOn(Schedulers.boundedElastic())
                                                                                            .then(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Size exceeds user capacity")));
                                                                                }
                                                                                return Mono.just(actualSize);
                                                                            });
                                                                });
                                                            })
                                                            .flatMap(size -> {
                                                                file.setFilename(newFilename);
                                                                file.setSize(size);
                                                                file.setUploadedAt(Instant.now());
                                                                if (filePart.headers().getContentType() != null) {
                                                                    file.setContentType(filePart.headers().getContentType().toString());
                                                                }
                                                                log.debug("Updating metadata: {}", file);
                                                                return databaseClient.sql("UPDATE metadata SET " +
                                                                                "filename = :filename, " +
                                                                                "content_type = :contentType, " +
                                                                                "size = :size, " +
                                                                                "uploaded_at = :uploadedAt " +
                                                                                "WHERE id = :id")
                                                                        .bind("id", file.getId())
                                                                        .bind("filename", file.getFilename())
                                                                        .bind("contentType", file.getContentType())
                                                                        .bind("size", file.getSize())
                                                                        .bind("uploadedAt", file.getUploadedAt())
                                                                        .fetch()
                                                                        .rowsUpdated()
                                                                        .thenReturn(file);
                                                            });
                                                })
                                );
                            });
                })
                .doOnSuccess(m -> {
                    if (m == null) {
                        log.error("Received null from update operation!");
                    } else {
                        log.info("File updated: {} by user {}", m.getFilename(), m.getOwnerId());
                    }
                })
                .doOnError(e -> log.error("Failed to update file", e));
    }
    
    public Mono<Void> deleteFile(UUID id) throws RuntimeException {
        // Trashing a file = move its row into the user's bin folder so it
        // shows up in the trash view (which queries by folder_id), and move
        // it on disk into bin_<uid>/. The DB folder_id and storage_path
        // MUST stay consistent with the on-disk location, otherwise the
        // bin view (filtered by folder_id) silently hides the file even
        // though it's safely on disk in the trash.
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(currentUser -> {
                	FileMetaEntity meta = new FileMetaEntity();

                    log.debug("Deleting file: {}", id);
                    return databaseClient.sql("SELECT * FROM metadata WHERE id = :fileId AND owner_id = :ownerId")
	                    .bind("fileId", id)
	                    .bind("ownerId", currentUser.getId())
                        .map(row -> {
                            meta.setId(row.get("id", UUID.class));
                            meta.setFilename(row.get("filename", String.class));
                            meta.setContentType(row.get("content_type", String.class));
                            meta.setSize(row.get("size", Long.class));
                            meta.setStoragePath(row.get("storage_path", String.class));
                            meta.setUploadedAt(row.get("uploaded_at", Instant.class));
                            meta.setOwnerId(row.get("owner_id", UUID.class));
                            meta.setFolderId(row.get("folder_id", UUID.class));
                            meta.setDeletedAt(row.get("deleted_at",Instant.class));
                            return meta;
                        })
                        .one()
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")))
                        .map(found -> new Object[]{ currentUser, found });
                })
                .flatMap(tuple -> {
                    IdentifiedPrincipal currentUser = (IdentifiedPrincipal) tuple[0];
                    FileMetaEntity file = (FileMetaEntity) tuple[1];
                    if (file.getDeletedAt() != null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "File with id = " + file.getId() + " is already deleted"));
                    }
                    return folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(
                                    currentUser.getId(), "bin_" + currentUser.getId())
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Bin folder not found")))
                            .flatMap(bin -> Mono.fromCallable(() -> {
                                        Path binDir = Paths.get(storagePath, currentUser.getId().toString())
                                                .resolve("bin_" + currentUser.getId());
                                        Files.createDirectories(binDir);
                                        // Pick a non-colliding name on disk inside the bin so
                                        // we never silently overwrite a previously-trashed file
                                        // with the same filename.
                                        Path dst = uniqueDestination(binDir, file.getFilename());
                                        if (Files.exists(Path.of(file.getStoragePath()))) {
                                            Files.move(Paths.get(file.getStoragePath()), dst, StandardCopyOption.REPLACE_EXISTING);
                                            log.info("File moved to bin: {} -> {}", file.getStoragePath(), dst);
                                        }
                                        return dst;
                                    }).subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(dst -> databaseClient.sql(
                                                    "UPDATE metadata SET deleted_at = :deletedAt, " +
                                                    "folder_id = :folderId, storage_path = :path, filename = :name " +
                                                    "WHERE id = :fileId")
                                            .bind("deletedAt", Instant.now())
                                            .bind("folderId", bin.getId())
                                            .bind("path", dst.toString())
                                            .bind("name", dst.getFileName().toString())
                                            .bind("fileId", file.getId())
                                            .fetch()
                                            .rowsUpdated()
                                            .then()));
                });
    }
    
    public Mono<Void> purgeFile(UUID id) throws RuntimeException {
        return purgeFileInternal(id, /*requireDeleted=*/true);
    }

    /**
     * Force-purge bypassing the {@code deleted_at} guard. Used by
     * {@link FolderController#purgeFolder} when an enclosing folder is
     * already trashed-as-a-unit — its file rows keep deleted_at = NULL
     * (we don't unpack on delete) so the strict purge would 403.
     * Public for cross-package access from FolderController.
     */
    public Mono<Void> purgeFileForce(UUID id) throws RuntimeException {
        return purgeFileInternal(id, /*requireDeleted=*/false);
    }

    private Mono<Void> purgeFileInternal(UUID id, boolean requireDeleted) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser -> {
                	FileMetaEntity meta = new FileMetaEntity();

                    log.debug("Deleting file: {}", id);
                    return databaseClient.sql("SELECT * FROM metadata WHERE id = :fileId AND owner_id = :ownerId")
	                    .bind("fileId", id)
	                    .bind("ownerId", currentUser.getId())
                        .map(row -> {
                            meta.setId(row.get("id", UUID.class));
                            meta.setFilename(row.get("filename", String.class));
                            meta.setContentType(row.get("content_type", String.class));
                            meta.setSize(row.get("size", Long.class));
                            meta.setStoragePath(row.get("storage_path", String.class));
                            meta.setUploadedAt(row.get("uploaded_at", Instant.class));
                            meta.setOwnerId(row.get("owner_id", UUID.class));
                            meta.setFolderId(row.get("folder_id", UUID.class));
                            meta.setDeletedAt(row.get("deleted_at", Instant.class));
                            return meta;
                        })
                        .one()
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")));
                })
                .flatMap(file -> {
                	if (requireDeleted && file.getDeletedAt() == null) {
                		throw new ResponseStatusException(HttpStatus.FORBIDDEN,"File with id = "+file.getId()+" is not deleted");
                	}
                    return Mono.fromRunnable(() -> {
                                try {
                                	log.debug("Purging file: {}",file.getId());
                                    // Use the actual storage_path (works for files inside
                                    // a trashed folder where the path may be deeper than
                                    // bin_<uid>/<filename>).
                                    if (file.getStoragePath() != null) {
                                        Files.deleteIfExists(Paths.get(file.getStoragePath()));
                                    } else {
                                        Files.deleteIfExists(Paths.get(storagePath, file.getOwnerId().toString())
                                                .resolve("bin_" + file.getOwnerId().toString())
                                                .resolve(file.getFilename()));
                                    }
                                } catch (IOException e) {
                                    log.error("Failed to purge file from bin: {}", file.getStoragePath(), e);
                                    throw new RuntimeException("Failed to trash file", e);
                                }
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(databaseClient.sql("DELETE FROM metadata WHERE id = :fileId")
                                    .bind("fileId", file.getId())
                                    .fetch()
                                    .rowsUpdated())
                            .then();
                });
    }

    private Mono<Path> buildPhysicalPath(UUID userId, FolderEntity folder) {
        if (folder == null) {
            return Mono.just(Paths.get(storagePath, userId.toString()));
        }
        return getFolderPathSegments(folder)
                .map(segments -> {
                    Path path = Paths.get(storagePath, userId.toString());
                    for (String segment : segments) {
                        path = path.resolve(segment);
                    }
                    return path;
                });
    }

    private Mono<List<String>> getFolderPathSegments(FolderEntity folder) {
        return Mono.just(folder)
                .expand(current -> {
                    if (current.getParentFolderId() == null) {
                        return Mono.empty();
                    }
                    return folderRepository.findById(current.getParentFolderId());
                })
                .collectList()
                .map(list -> {
                    Collections.reverse(list);
                    return list.stream().map(FolderEntity::getName).collect(Collectors.toList());
                });
    }
    
    /**
     * Owner-scoped metadata lookup. The previous implementation called
     * {@code metadataRepository.findById(id)} with no ownership check, so any
     * authenticated user could enumerate UUIDs and download other users' files
     * (IDOR). All callers now route through {@link ReactiveSecurityContextHolder}
     * and only see rows where {@code owner_id = current user}.
     */
    public Mono<FileMetaEntity> getFileMetadata(UUID id) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(currentUser -> metadataRepository.findByIdAndOwnerId(id, currentUser.getId()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File " + id.toString() + " not found")));
    }

    public Mono<Path> getFilePath(UUID id) {
        return getFileMetadata(id)
                .map(meta -> Paths.get(meta.getStoragePath()));
    }

    public Mono<FileMetaEntity> renameFile(UUID id, String newName) {
        // Reject path-traversal attempts (../, slashes, NULs, control chars)
        // before they reach Path.resolve.
        final String safeName = PathSanitizer.sanitizeFilename(newName);
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser ->
                    metadataRepository.findByIdAndOwnerId(id, currentUser.getId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")))
                        .flatMap(file ->
                            resolveOwnedFolder(currentUser.getId(), file.getFolderId())
                                .flatMap(folder -> buildPhysicalPath(currentUser.getId(), folder))
                                .flatMap(dir -> Mono.fromCallable(() -> {
                                    Path src = dir.resolve(file.getFilename());
                                    Path dst = dir.resolve(safeName);
                                    if (Files.exists(src)) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                    return dst;
                                }).subscribeOn(Schedulers.boundedElastic()))
                                .flatMap(dst -> {
                                    file.setFilename(safeName);
                                    file.setStoragePath(dst.toString());
                                    return metadataRepository.save(file);
                                })
                        )
                );
    }

    public Mono<FileMetaEntity> moveFile(UUID id, UUID targetFolderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser ->
                    metadataRepository.findByIdAndOwnerId(id, currentUser.getId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")))
                        .flatMap(file ->
                            resolveOwnedFolder(currentUser.getId(), file.getFolderId())
                                .flatMap(srcFolder -> buildPhysicalPath(currentUser.getId(), srcFolder))
                                .zipWith(
                                    resolveOwnedFolder(currentUser.getId(), targetFolderId)
                                        .flatMap(dstFolder -> buildPhysicalPath(currentUser.getId(), dstFolder))
                                )
                                .flatMap(paths -> Mono.fromCallable(() -> {
                                    Path src = paths.getT1().resolve(file.getFilename());
                                    Path dst = paths.getT2().resolve(file.getFilename());
                                    Files.createDirectories(dst.getParent());
                                    if (Files.exists(src)) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                    return dst;
                                }).subscribeOn(Schedulers.boundedElastic()))
                                .flatMap(dst -> {
                                    file.setFolderId(targetFolderId);
                                    file.setStoragePath(dst.toString());
                                    return metadataRepository.save(file);
                                })
                        )
                );
    }

    public Mono<FileMetaEntity> copyFile(UUID id, UUID targetFolderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser ->
                    metadataRepository.findByIdAndOwnerId(id, currentUser.getId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")))
                        .flatMap(file ->
                            resolveOwnedFolder(currentUser.getId(), file.getFolderId())
                                .flatMap(srcFolder -> buildPhysicalPath(currentUser.getId(), srcFolder))
                                .zipWith(
                                    resolveOwnedFolder(currentUser.getId(), targetFolderId)
                                        .flatMap(dstFolder -> buildPhysicalPath(currentUser.getId(), dstFolder))
                                )
                                .flatMap(paths -> Mono.fromCallable(() -> {
                                    Path src = paths.getT1().resolve(file.getFilename());
                                    Path dstDir = paths.getT2();
                                    // Pick a non-colliding filename in the destination directory:
                                    // "name.ext" → "name (копия).ext" → "name (копия 2).ext" …
                                    Path dst = uniqueDestination(dstDir, file.getFilename());
                                    Files.createDirectories(dst.getParent());
                                    if (Files.exists(src)) Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                    return dst;
                                }).subscribeOn(Schedulers.boundedElastic()))
                                .flatMap(dst -> {
                                    // NB: do NOT set the @Id — Spring Data R2DBC treats a non-null
                                    // id as "this row already exists" and emits UPDATE … WHERE id =
                                    // <fresh uuid>, which silently affects 0 rows. Leaving it null
                                    // forces an INSERT and the DB default (gen_random_uuid()) fills in.
                                    FileMetaEntity copy = new FileMetaEntity();
                                    copy.setFilename(dst.getFileName().toString());
                                    copy.setContentType(file.getContentType());
                                    copy.setSize(file.getSize());
                                    copy.setStoragePath(dst.toString());
                                    copy.setUploadedAt(Instant.now());
                                    copy.setOwnerId(currentUser.getId());
                                    copy.setFolderId(targetFolderId);
                                    return metadataRepository.save(copy);
                                })
                        )
                );
    }

    public Mono<FileMetaEntity> restoreFile(UUID id) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser ->
                    metadataRepository.findByIdAndOwnerId(id, currentUser.getId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")))
                        .flatMap(file -> {
                            if (file.getDeletedAt() == null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "File is not deleted"));
                            }
                            return folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(
                                    currentUser.getId(), "root_" + currentUser.getId())
                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Root folder not found")))
                                .flatMap(rootFolder -> buildPhysicalPath(currentUser.getId(), rootFolder)
                                        .flatMap(rootPath -> Mono.fromCallable(() -> {
                                            Path binPath = Paths.get(storagePath, currentUser.getId().toString())
                                                    .resolve("bin_" + currentUser.getId()).resolve(file.getFilename());
                                            Files.createDirectories(rootPath);
                                            // Pick a non-colliding name in case another file with the
                                            // same name was uploaded after this one was trashed.
                                            Path dst = uniqueDestination(rootPath, file.getFilename());
                                            if (Files.exists(binPath)) Files.move(binPath, dst, StandardCopyOption.REPLACE_EXISTING);
                                            return dst;
                                        }).subscribeOn(Schedulers.boundedElastic())
                                        .flatMap(dst -> {
                                            file.setDeletedAt(null);
                                            // Files are stored with folder_id = <rootUUID> when they live
                                            // at the root, NOT null. Setting null here would hide the file
                                            // from getRootContent (which queries by folder_id = rootUUID).
                                            file.setFolderId(rootFolder.getId());
                                            file.setStoragePath(dst.toString());
                                            file.setFilename(dst.getFileName().toString());
                                            return metadataRepository.save(file);
                                        }))
                                );
                        })
                );
    }

    private Mono<FolderEntity> resolveOwnedFolder(UUID userId, UUID folderId) {
        if (folderId == null) {
            return folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(userId, "root_" + userId)
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Root folder not found")));
        }
        return folderRepository.findByIdAndOwnerId(folderId, userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")));
    }

    /**
     * Build a non-colliding destination path inside {@code dir} by appending
     * " (копия)", " (копия 2)", … before the extension.
     * Used by copy operations so the user never silently overwrites a file.
     */
    static Path uniqueDestination(Path dir, String filename) {
        Path candidate = dir.resolve(filename);
        if (!Files.exists(candidate)) return candidate;

        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String ext  = dot > 0 ? filename.substring(dot) : "";

        for (int i = 1; i < 10000; i++) {
            String suffix = (i == 1) ? " (копия)" : " (копия " + i + ")";
            Path next = dir.resolve(stem + suffix + ext);
            if (!Files.exists(next)) return next;
        }
        // pathological — fall back to a UUID stamp
        return dir.resolve(stem + " (" + UUID.randomUUID() + ")" + ext);
    }
}
