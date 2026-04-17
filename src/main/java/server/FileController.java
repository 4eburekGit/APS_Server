package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    public Mono<FileMetaEntity> uploadFile(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @RequestParam(required = false) UUID folderId) {
        return filePartMono.flatMap(filePart -> storageController.saveFile(filePart, folderId));
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

    @GetMapping("/folders/root")
    public Mono<FolderController.FolderContent> getRootContent() {
        return folderController.getRootContent();
    }

    @GetMapping("/folders/{folderId}")
    public Mono<FolderController.FolderContent> getFolderContent(@PathVariable UUID folderId) {
        return folderController.getFolderContent(folderId);
    }

    @GetMapping("/folders/list")
    public Flux<FileMetaEntity> listFiles(@RequestParam(required = false) UUID folderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
                .flatMapMany(user -> {
                    if (folderId == null) {
                        return metadataRepository.findByOwnerIdAndFolderIdIsNull(user.getId());
                    } else {
                        return metadataRepository.findByOwnerIdAndFolderId(user.getId(), folderId);
                    }
                });
    }

    public record CreateFolderRequest(String name, UUID parentFolderId) {}
}
