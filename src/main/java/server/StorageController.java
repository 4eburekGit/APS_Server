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
                        String originalFilename = filePart.filename();
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
                                                metadata.setContentType(filePart.headers().getContentType().toString());
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

                                String newFilename = filePart.filename();
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
                	if (file.getDeletedAt() == null) {
                		throw new ResponseStatusException(HttpStatus.FORBIDDEN,"File with id = "+file.getId()+" is not deleted");
                	}
                    return Mono.fromRunnable(() -> {
                                try {
                                	log.debug("Purging file: {}",file.getId());
                                    Files.deleteIfExists(Paths.get(storagePath,file.getOwnerId().toString()).resolve("bin_"+file.getOwnerId().toString()).resolve(file.getFilename()));
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
    
    public Mono<FileMetaEntity> getFileMetadata(UUID id) {
        return metadataRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"File "+id.toString()+" not found")));
    }

    public Mono<Path> getFilePath(UUID id) {
        return getFileMetadata(id)
                .map(meta -> Paths.get(meta.getStoragePath()));
    }

    public Mono<FileMetaEntity> renameFile(UUID id, String newName) {
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
                                    Path dst = dir.resolve(newName);
                                    if (Files.exists(src)) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                    return dst;
                                }).subscribeOn(Schedulers.boundedElastic()))
                                .flatMap(dst -> {
                                    file.setFilename(newName);
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
