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
            @PathVariable UUID fileId) {
        return filePartMono.flatMap(filePart -> storageController.updateFile(fileId, filePart));
    }
    
    @PatchMapping("/{id}/rename") // STUB!!! - renames file to newName
    public Mono<FileMetaEntity> renameFile(
    		@PathVariable UUID id,
    		@RequestBody String newName) {
    	return storageController.getFileMetadata(id);
    }
    
    @PatchMapping("/{id}/move") // STUB!!! - moves file to folder = folderId
    public Mono<FileMetaEntity> moveFile(
    		@PathVariable UUID id,
    		@RequestBody UUID folderId) {
    	return storageController.getFileMetadata(id);
    }
    
    @PostMapping("/{id}/copy") // STUB!!! - copies file to folder = folderid
    public Mono<FileMetaEntity> copyFile(
    		@PathVariable UUID id,
    		@RequestBody UUID folderId) {
    	return storageController.getFileMetadata(id);
    }
    
    @PostMapping("/{id}/restore") // STUB!!! - restores file from the bin
    public Mono<FileMetaEntity> restoreFile(@PathVariable UUID id) {
    	return storageController.getFileMetadata(id);
    }
    
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<String>> deleteFile(@PathVariable UUID id) {
    	return storageController.getFileMetadata(id)
    			.flatMap(metadata -> {
    				return storageController.deleteFile(id);
    			})
    			.flatMap(res -> {
    				return Mono.just(ResponseEntity.ok("Successfully deleted file"));
    			});
    }
    
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<String>> purgeFile(@PathVariable UUID id) {
    	return storageController.getFileMetadata(id)
    			.flatMap(metadata -> {
    				return storageController.deleteFile(id);
    			})
    			.flatMap(res -> {
    				return Mono.just(ResponseEntity.ok("Successfully purged file"));
    			});
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
    			.flatMap(res -> {
    				return Mono.just(ResponseEntity.ok("Successfully deleted folder"));
    			});
    }
    
    @DeleteMapping("/folders/{folderId}/purge")
    public Mono<ResponseEntity<String>> purgeFolder(@PathVariable UUID folderId) {
    	return folderController.purgeFolder(folderId)
    			.flatMap(res -> {
    				return Mono.just(ResponseEntity.ok("Successfully purged folder"));
    			});
    }

    public record CreateFolderRequest(String name, UUID parentFolderId) {}
}
