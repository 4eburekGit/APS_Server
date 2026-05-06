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
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
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
                                            	log.debug("Checking size quota");
                                            	Mono<Long> added = filePart.content().map(dataB -> {
                                            		long size = dataB.readableByteCount();
                                            		DataBufferUtils.release(dataB);
                                            		return size;
                                            	}).reduce((long)0, (acc,val)->{ return (long) (acc+val); }); // what the fuck, why can't you infer that it is Mono<Long> without those stupid typcasts
                                            	log.debug("Collecting taken space");
                                            	
                                            	Mono<Long> total = databaseClient.sql("SELECT sum(size) FROM metadata WHERE " +
                                                        "owner_id = :ownerId AND deleted_at IS NULL")
                                                .bind("ownerId", currentUser.getId()).map(row -> row.get(0,Long.class)).first();
                                               
                                            	total.zipWith(added).flatMap(sizes -> {
                                            		if (sizes.getT1()+sizes.getT2() > storageQuota) { // 10GB
                                            			return Mono.just(Boolean.TRUE); // Exceeds
                                            		}
                                            		else {
                                            			return Mono.just(Boolean.FALSE); // Passed
                                            		}
                                            	}).subscribe(value->{ if(value.booleanValue()) { 
                                            		  log.error("Capacity exceeded");
                                            		  throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Size exceeds user capacity");
                                            		}
                                            	else {
                                            		
                                            	}
                                            	});
                                            	
                                                log.debug("Quota not exceeded, starting file transfer");
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
                                                if (folderId == null) {
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
                                                }
                                                else {
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
                                                    .map(row -> {
                                                        return metadata;
                                                    });
                                                }
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
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
                .single()
                .flatMap(currentUser -> {
                    log.debug("Current user: {}", currentUser.getUsername());
                    return metadataRepository.findByIdAndOwnerId(fileId,currentUser.getId())
                    		.flatMap(file -> { return Mono.just(file.getFolderId()); })
                    		.flatMap(folderId -> {
                    			return (folderId == null) 
                    					? folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(currentUser.getId(),"root_"+currentUser.getId().toString()).log("Writing into root")
                    							.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Root folder not found or access denied")))
                                        : folderRepository.findByIdAndOwnerId(folderId, currentUser.getId()).log("Writing into folder")
                                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Folder not found or access denied")));
                    		})
                    		.flatMap(folder -> {
		                    	String newFilename = filePart.filename();
		                    	return metadataRepository.findByIdAndOwnerId(fileId,currentUser.getId())
		                    			.flatMap(file -> {
		                    				return buildPhysicalPath(currentUser.getId(), folder)
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
		                                                        	log.debug("Checking size quota");
		                                                        	Mono<Long> added = filePart.content().map(dataB -> {
		                                                        		long size = dataB.readableByteCount();
		                                                        		DataBufferUtils.release(dataB);
		                                                        		return size;
		                                                        	}).reduce((long)0, (acc,val)->{ return (long) (acc+val); }); // what the fuck, why can't you infer that it is Mono<Long> without those stupid typcasts
		                                                        	log.debug("Collecting taken space");
		                                                        	
		                                                        	Mono<Long> total = databaseClient.sql("SELECT sum(size) FROM metadata WHERE " +
		                                                                    "owner_id = :ownerId AND NOT id = :id AND deleted_at IS NULL")
		                                                            .bind("ownerId", currentUser.getId())
		                                                            .bind("id", file.getId())
		                                                            .map(row -> row.get(0,Long.class)).first();
		                                                           
		                                                        	total.zipWith(added).flatMap(sizes -> {
		                                                        		if (sizes.getT1()+sizes.getT2() > storageQuota) { // 10GB
		                                                        			return Mono.just(Boolean.TRUE); // Exceeds
		                                                        		}
		                                                        		else {
		                                                        			return Mono.just(Boolean.FALSE); // Passed
		                                                        		}
		                                                        	}).subscribe(value->{ if(value.booleanValue()) { 
		                                                        		  log.error("Capacity exceeded");
		                                                        		  throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Size exceeds user capacity");
		                                                        		}
		                                                        	else {
		                                                        		
		                                                        	}
		                                                        	});
		                                                        	
		                                                            log.debug("Quota not exceeded, starting file transfer");
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
		                                                        	file.setFilename(newFilename);
		                                                        	file.setSize(size);
		                                                        	
		                                                            log.debug("Updating metadata: {}", file);
		                                                            return databaseClient.sql("UPDATE metadata SET " +
		                                                                    "filename = :filename " +
		                                                                    "content_type = :contentType " +
		                                                                    "size = :size " +
		                                                                    "WHERE id = :id")
		                                                            .bind("id", file.getId())
		                                                            .bind("filename", newFilename)
		                                                            .bind("contentType", file.getContentType())
		                                                            .bind("size", size)
		                                                            .filter((statement, executeFunction) -> statement.returnGeneratedValues("id").execute())
		                                                            .fetch()
		                                                            .first()
		                                                            .switchIfEmpty(Mono.error(new RuntimeException("Could not update db")));
		                                                        })
		                                                        .flatMap(res -> {
		                                                        	return Mono.just(file);
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
    
    public Mono<Void> deleteFile(UUID id) throws RuntimeException {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
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
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found or access denied")));
                })
                .flatMap(file -> {
                	if (file.getDeletedAt() != null) {
                		throw new ResponseStatusException(HttpStatus.FORBIDDEN,"File with id = "+file.getId()+" is already deleted");
                	}
                    return Mono.fromRunnable(() -> {
                                try {
                                	log.debug("Deleting file: {}",file.getId());
                                    if (Files.exists(Path.of(file.getStoragePath()))) {
                                    	Files.move(Paths.get(file.getStoragePath()),
                                        		Paths.get(storagePath,file.getOwnerId().toString()).resolve("bin_"+file.getOwnerId().toString()).resolve(file.getFilename()),
                                        		StandardCopyOption.REPLACE_EXISTING);
                                        log.info("File moved to bin: {}", file.getStoragePath());
                                    }
                                } catch (IOException e) {
                                    log.error("Failed to trash file from disk: {}", file.getStoragePath(), e);
                                    throw new RuntimeException("Failed to trash file", e);
                                }
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(databaseClient.sql("UPDATE metadata SET deleted_at = :deletedAt WHERE id = :fileId")
                                    .bind("deletedAt", Instant.now())
                            		.bind("fileId", file.getId())
                                    .fetch()
                                    .rowsUpdated())
                            .then();
                });
    }
    
    public Mono<Void> purgeFile(UUID id) throws RuntimeException {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
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
}
