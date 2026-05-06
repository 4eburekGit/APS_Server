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


    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<FileMetaEntity> uploadFileToRoot(@RequestPart("file") Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart -> storageController.saveFile(filePart, null));
    }
    
    @PostMapping(value = "/upload/to/{folderId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<FileMetaEntity> uploadFileTo(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @PathVariable UUID folderId) {
        return filePartMono.flatMap(filePart -> storageController.saveFile(filePart, folderId));
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
    public Mono<ResponseEntity<String>> deleteFile(@PathVariable UUID id) {
    	return storageController.getFileMetadata(id)
    			.flatMap(metadata -> {
    				return storageController.deleteFile(id);
    			})
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
    public Mono<ResponseEntity<Resource>> downloadFile(@PathVariable UUID id) {
        return storageController.getFileMetadata(id)
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
    public Mono<FolderController.FolderContent> getRootContent() {
        return folderController.getRootContent();
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
    public Mono<FolderController.FolderContent> getBinContent() {
        return folderController.getBinContent();
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
    public Mono<FolderController.FolderContent> getFolderContent(@PathVariable UUID folderId) {
        return folderController.getFolderContent(folderId);
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

    public record CreateFolderRequest(String name, UUID parentFolderId) {}

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
