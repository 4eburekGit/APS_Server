package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
/*
    public Mono<FileMetaEntity> saveFile(FilePart filePart, UUID folderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
                .flatMap(currentUser -> {
                    Mono<FolderEntity> folderMono = folderId == null 
                            ? Mono.empty() 
                            : folderRepository.findByIdAndOwnerId(folderId, currentUser.getId())
                                    .switchIfEmpty(Mono.error(new RuntimeException("Folder not found or access denied")));
                    return folderMono.defaultIfEmpty(null).flatMap(folder -> {
                        String originalFilename = filePart.filename();
                        String fileId = UUID.randomUUID().toString();
                        String storedFilename = fileId + "_" + originalFilename;
                        return buildPhysicalPath(currentUser.getId(), folder)
                                .flatMap(userFolderPath -> {
                                    Path targetPath = userFolderPath.resolve(storedFilename);
                                    return Mono.fromCallable(() -> {
                                                Files.createDirectories(targetPath.getParent());
                                                return targetPath.getParent();
                                            })
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .thenMany(filePart.content())
                                            .flatMap(dataBuffer -> writeDataBuffer(targetPath, dataBuffer))
                                            .reduce(0L, (total, written) -> total + written)
                                            .flatMap(totalBytes -> {
                                                FileMetaEntity metadata = new FileMetaEntity();
                                                metadata.setId(UUID.fromString(fileId));
                                                metadata.setFilename(originalFilename);
                                                metadata.setContentType(filePart.headers().getContentType().toString());
                                                metadata.setSize(totalBytes);
                                                metadata.setStoragePath(targetPath.toString());
                                                metadata.setUploadedAt(Instant.now());
                                                metadata.setOwnerId(currentUser.getId());
                                                metadata.setFolderId(folderId);
                                                return metadataRepository.save(metadata);
                                            });
                                });
                    });
                    //
                    return Mono.fromCallable(() -> Files.createDirectories(targetPath.getParent()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(filePart.transferTo(targetPath))
                            .then(Mono.fromCallable(() -> Files.size(targetPath)))
                            .flatMap(totalBytes -> {
                                FileMetadata metadata = new FileMetadata();
                                // ... заполнение полей
                                metadata.setSize(totalBytes);
                                return metadataRepository.save(metadata);
                            });
                });
    }
    */
    
    public Mono<FileMetaEntity> saveFile(FilePart filePart, UUID folderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser -> {
                    log.debug("Current user: {}", currentUser.getUsername());

                    Mono<FolderEntity> folderMono = (folderId == null)
                            ? folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(currentUser.getId(),"root_"+currentUser.getId().toString()).log("Writing into root")
                                    .switchIfEmpty(Mono.error(new RuntimeException("Root folder not found or access denied")))
                            : folderRepository.findByIdAndOwnerId(folderId, currentUser.getId()).log("Writing into folder")
                                    .switchIfEmpty(Mono.error(new RuntimeException("Folder not found or access denied")));

                    return folderMono.flatMap(folder -> {
                        String originalFilename = filePart.filename();
                        String fileId = UUID.randomUUID().toString();
                        String storedFilename = fileId + "_" + originalFilename;

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
                                                log.debug("Starting file transfer");
                                                return filePart.transferTo(path)
                                                        .doOnSuccess(v -> log.debug("File transfer completed"))
                                                        .doOnError(e -> log.error("File transfer failed", e))
                                                        .then(Mono.fromCallable(() -> {
                                                            long size = Files.size(path);
                                                            log.debug("File size: {}", size);
                                                            return size;
                                                        }).subscribeOn(Schedulers.boundedElastic()));
                                            })
                                            .flatMap(size -> {
                                                FileMetaEntity metadata = new FileMetaEntity();
                                                metadata.setId(UUID.fromString(fileId));
                                                metadata.setFilename(originalFilename);
                                                metadata.setContentType(filePart.headers().getContentType().toString());
                                                metadata.setSize(size);
                                                metadata.setStoragePath(targetPath.toString());
                                                metadata.setUploadedAt(Instant.now());
                                                metadata.setOwnerId(currentUser.getId());
                                                metadata.setFolderId(folderId);

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
                                                .bindNull("folderId", UUID.class)
                                                .filter((statement, executeFunction) -> statement.returnGeneratedValues("id").execute())
                                                .fetch()
                                                .first()
                                                .map(row -> {
                                                    return metadata;
                                                });
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
/*
    public Mono<FileMetaEntity> saveFile(FilePart filePart, UUID folderId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(user -> {
                    FileMetaEntity meta = new FileMetaEntity();
                    // meta.setId(UUID.randomUUID());
                    meta.setFilename("test.txt");
                    meta.setContentType("text/plain");
                    meta.setSize(0L);
                    meta.setStoragePath("/tmp/test.txt");
                    meta.setUploadedAt(Instant.now());
                    meta.setOwnerId(user.getId());
                    meta.setFolderId(folderId);
                    return metadataRepository.save(meta);
                });
    }
*/
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
/*
    private Mono<Long> writeDataBuffer(Path path, DataBuffer dataBuffer) {
        return Mono.fromCallable(() -> {
            AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            return channel;
        }).flatMapMany(channel -> {
            return DataBufferUtils.write(dataBuffer, channel, 0)
                    .doFinally(signal -> {
                        try {
                            channel.close();
                        } catch (IOException e) {
                            log.warn("Failed to close channel", e);
                        }
                    });
        }).reduce(0L, (total, written) -> total + written);
    }
*/
    public Mono<FileMetaEntity> getFileMetadata(UUID id) {
        return metadataRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("File "+id.toString()+" not found")));
    }

    public Mono<Path> getFilePath(UUID id) {
        return getFileMetadata(id)
                .map(meta -> Paths.get(meta.getStoragePath()));
    }
}
