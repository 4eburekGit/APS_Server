package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import server.FolderController.FolderMeta;
import server.repository.FileMetaRepo;

import java.nio.file.Path;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageController storageController;
    private final FolderController folderController;
    private final FileMetaRepo metadataRepository;
    private final AuditService auditService;


    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<FileMetaEntity> uploadFileToRoot(
            @RequestPart("file") Mono<FilePart> filePartMono,
            org.springframework.http.server.reactive.ServerHttpRequest request) {
        return filePartMono.flatMap(filePart -> storageController.saveFile(filePart, null))
                .flatMap(meta -> auditService.record(meta.getOwnerId(), "upload",
                        "file", meta.getId(), clientIp(request)).thenReturn(meta));
    }

    @PostMapping(value = "/upload/to/{folderId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<FileMetaEntity> uploadFileTo(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @PathVariable UUID folderId) {
        return filePartMono.flatMap(filePart -> storageController.saveFile(filePart, folderId));
    }

    /* =========================================================
       FR#1 (folder upload) + FR#20 (drag-and-drop). Accepts a multipart
       with N "files" parts. Each part's filename is the FULL relative path
       (slash-separated, e.g. "subdir/nested/img.jpg"). Server creates any
       missing intermediate folders, then saves each file in its leaf
       folder. Concurrency is bounded (4 in flight) so we don't blow up
       the R2DBC pool on big trees.
       Optional path param: target parent folder (default = user root).
       ========================================================= */
    @PostMapping(value = "/upload-tree", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<FileMetaEntity> uploadTreeToRoot(
            @RequestPart("files") Flux<FilePart> parts) {
        return storageController.saveFileTree(parts, null);
    }

    @PostMapping(value = "/upload-tree/to/{folderId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<FileMetaEntity> uploadTreeTo(
            @RequestPart("files") Flux<FilePart> parts,
            @PathVariable UUID folderId) {
        return storageController.saveFileTree(parts, folderId);
    }
    
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<FileMetaEntity> updateFile(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @PathVariable UUID id) {
        return filePartMono.flatMap(filePart -> storageController.updateFile(id, filePart));
    }

    @PatchMapping("/{id}/rename") // renames file to newName
    public Mono<FileMetaEntity> renameFile(
    		@PathVariable UUID id,
    		@RequestBody String newName) {
    	return storageController.renameFile(id, newName);
    }

    @PatchMapping("/{id}/move") // moves file to folder = folderId
    public Mono<FileMetaEntity> moveFile(
    		@PathVariable UUID id,
    		@RequestBody(required = false) String folderId) {
    	return storageController.moveFile(id, parseNullableUuid(folderId));
    }

    @PostMapping("/{id}/copy") // copies file to folder = folderId
    public Mono<FileMetaEntity> copyFile(
    		@PathVariable UUID id,
    		@RequestBody(required = false) String folderId) {
    	return storageController.copyFile(id, parseNullableUuid(folderId));
    }

    @PostMapping("/{id}/restore") // restores file from the bin to its original folder
    public Mono<FileMetaEntity> restoreFile(@PathVariable UUID id) {
    	return storageController.restoreFile(id);
    }
    
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<String>> deleteFile(
            @PathVariable UUID id,
            org.springframework.http.server.reactive.ServerHttpRequest request) {
    	return storageController.getFileMetadata(id)
    			.flatMap(metadata -> storageController.deleteFile(id)
    			        .then(auditService.record(metadata.getOwnerId(), "delete-file",
    			                "file", id, clientIp(request))))
    			.then(Mono.just(ResponseEntity.ok("Successfully deleted file")));
    }
    
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<String>> purgeFile(@PathVariable UUID id) {
    	return storageController.getFileMetadata(id)
    			.flatMap(metadata -> {
    				return storageController.purgeFile(id);
    			})
    			.then(Mono.just(ResponseEntity.ok("Successfully purged file")));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Resource>> downloadFile(
            @PathVariable UUID id,
            org.springframework.http.server.reactive.ServerHttpRequest request) {
        return storageController.getFileMetadata(id)
                .flatMap(metadata -> auditService.record(metadata.getOwnerId(), "download",
                                "file", id, clientIp(request))
                        .thenReturn(metadata))
                .flatMap(metadata -> {
                    Path path = Path.of(metadata.getStoragePath());
                    Resource resource = new FileSystemResource(path);
                    return Mono.just(
                            ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType(metadata.getContentType()))
                                    .header(HttpHeaders.CONTENT_DISPOSITION,
                                            ContentDisposition.attachment()
                                                    .filename(metadata.getFilename())
                                                    .build()
                                                    .toString())
                                    .body(resource)
                    );
                });
    }
    
    @GetMapping("/{id}/meta")
    public Mono<FileMetaEntity> downloadMetadata(@PathVariable UUID id) {
        return storageController.getFileMetadata(id);
    }

    @PostMapping("/folders/create")
    public Mono<FolderEntity> createFolder(@RequestBody CreateFolderRequest request) {
        return folderController.createFolder(request.name(), request.parentFolderId());
    }

    @GetMapping("/folders/root/content")
    public Mono<FolderController.FolderContent> getRootContent(
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir",  required = false) String dir) {
        return folderController.getRootContent(FolderController.SortSpec.parse(sort, dir));
    }

    @GetMapping("/folders/root/meta")
    public Mono<FolderMeta> getRootMeta() {
        return folderController.getFolderMeta(null);
    }

    /**
     * Lists the contents of the user's bin folder. Used by the trash view —
     * everything moved to bin (files individually + folders as a whole) lives
     * directly under bin_<uid>, so this endpoint returns the full top-level
     * listing of trashed items in one call.
     */
    @GetMapping("/folders/bin/content")
    public Mono<FolderController.FolderContent> getBinContent(
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir",  required = false) String dir) {
        return folderController.getBinContent(FolderController.SortSpec.parse(sort, dir));
    }

    @GetMapping("/folders/bin/meta")
    public Mono<FolderMeta> getBinMeta() {
        return folderController.getOrCreateBinFolder()
                .map(folder -> new FolderMeta(
                        folder.getId(),
                        folder.getName(),
                        folder.getParentFolderId(),
                        folder.getOwnerId(),
                        folder.getCreatedAt(),
                        folder.getDeletedAt()));
    }

    @GetMapping("/folders/{folderId}/content")
    public Mono<FolderController.FolderContent> getFolderContent(
            @PathVariable UUID folderId,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir",  required = false) String dir) {
        return folderController.getFolderContent(folderId, FolderController.SortSpec.parse(sort, dir));
    }
    
    @GetMapping("/folders/{folderId}/meta")
    public Mono<FolderMeta> getFolderMeta(@PathVariable UUID folderId) {
        return folderController.getFolderMeta(folderId);
    }
    
    @DeleteMapping("/folders/{folderId}")
    public Mono<ResponseEntity<String>> deleteFolder(@PathVariable UUID folderId) {
    	return folderController.deleteFolder(folderId)
    			.then(Mono.just(ResponseEntity.ok("Successfully deleted folder")));
    }

    @DeleteMapping("/folders/{folderId}/purge")
    public Mono<ResponseEntity<String>> purgeFolder(@PathVariable UUID folderId) {
    	return folderController.purgeFolder(folderId)
    			.then(Mono.just(ResponseEntity.ok("Successfully purged folder")));
    }

    @PostMapping("/folders/{folderId}/restore") // un-soft-deletes a folder + descendants
    public Mono<FolderEntity> restoreFolder(@PathVariable UUID folderId) {
        return folderController.restoreFolder(folderId);
    }

    @PatchMapping("/folders/{folderId}/move") // moves folder to new parent (null = root)
    public Mono<FolderEntity> moveFolder(
            @PathVariable UUID folderId,
            @RequestBody(required = false) String newParentId) {
        return folderController.moveFolder(folderId, parseNullableUuid(newParentId));
    }

    @PostMapping("/folders/{folderId}/copy") // recursive copy of folder + contents
    public Mono<FolderEntity> copyFolder(
            @PathVariable UUID folderId,
            @RequestBody(required = false) String newParentId) {
        return folderController.copyFolder(folderId, parseNullableUuid(newParentId));
    }

    @PatchMapping("/folders/{folderId}/rename") // renames folder (and on-disk dir)
    public Mono<FolderEntity> renameFolder(
            @PathVariable UUID folderId,
            @RequestBody String newName) {
        return folderController.renameFolder(folderId, newName);
    }

    /* =========================================================
       FR#1 / FR#20 — download a folder as a single ZIP archive. The
       backend builds a temp .zip on disk, streams it as the response,
       and deletes it once the response completes. Audit-logged the
       same way as a single-file download.
       ========================================================= */
    @GetMapping("/folders/{folderId}/download.zip")
    public Mono<ResponseEntity<Resource>> downloadFolderZip(
            @PathVariable UUID folderId,
            org.springframework.http.server.reactive.ServerHttpRequest request) {
        return folderController.zipFolder(folderId)
                .flatMap(zipPath -> folderController.getFolderMeta(folderId)
                        .map(meta -> {
                            String safe = (meta.name() == null ? "folder" : meta.name())
                                    .replaceAll("[^A-Za-z0-9_.-]", "_");
                            Resource resource = new FileSystemResource(zipPath) {
                                // Best-effort cleanup once the streaming finishes.
                                // Spring closes the InputStream after the body is sent;
                                // hooking finalize-style cleanup is brittle, so we
                                // schedule deletion via Path.toFile().deleteOnExit() too.
                                @Override
                                public java.io.InputStream getInputStream() throws java.io.IOException {
                                    java.io.InputStream in = super.getInputStream();
                                    return new java.io.FilterInputStream(in) {
                                        @Override public void close() throws java.io.IOException {
                                            try { super.close(); } finally {
                                                try { java.nio.file.Files.deleteIfExists(zipPath); }
                                                catch (Exception ignored) { /* leave for OS */ }
                                            }
                                        }
                                    };
                                }
                            };
                            // Fall back to deleteOnExit if close() never fires (e.g. client abort).
                            zipPath.toFile().deleteOnExit();
                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType("application/zip"))
                                    .header(HttpHeaders.CONTENT_DISPOSITION,
                                            ContentDisposition.attachment()
                                                    .filename(safe + ".zip")
                                                    .build()
                                                    .toString())
                                    .body((Resource) resource);
                        })
                        .flatMap(resp -> auditService.record(null, "download-folder",
                                "folder", folderId, clientIp(request))
                                .thenReturn(resp)));
    }

    public record CreateFolderRequest(String name, UUID parentFolderId) {}

    /** GET /api/files/{id}/audit — show recent events on this file. */
    @GetMapping("/{id}/audit")
    public Flux<AuditEntity> fileAudit(@PathVariable UUID id) {
        return storageController.getFileMetadata(id)
                .flatMapMany(meta -> auditService.forTarget("file", id));
    }

    /** Pull client IP from header chain (X-Forwarded-For first, then remote). */
    private static String clientIp(org.springframework.http.server.reactive.ServerHttpRequest req) {
        if (req == null) return null;
        String h = req.getHeaders().getFirst("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            int comma = h.indexOf(',');
            return comma < 0 ? h.trim() : h.substring(0, comma).trim();
        }
        return req.getRemoteAddress() == null
                ? null
                : req.getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * Parse a UUID from a raw text/plain body. Empty/blank/whitespace = null,
     * which downstream handlers treat as "the user's root folder". WebFlux's
     * built-in body decoders don't convert text/plain → UUID, so this lets us
     * keep the request shape simple (a bare UUID, no JSON wrapper / quotes).
     */
    private static UUID parseNullableUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.equals("null")) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Body must be a UUID or empty (got: '" + s + "')");
        }
    }
}
