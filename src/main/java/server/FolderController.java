package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import server.repository.FileMetaRepo;
import server.repository.FolderRepo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class FolderController {
	
	@Value("${storage.path:./uploads}")
    private String storagePath;

    private final FolderRepo folderRepository;
    private final FileMetaRepo metadataRepository;
    private final StorageController storageCtl;
    private final DatabaseClient databaseClient;

    private Mono<UUID> getCurrentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
                .map(UserEntity::getId);
    }

    public Mono<FolderEntity> getOrCreateRootFolder() {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(userId, "root_"+userId.toString())
                        .switchIfEmpty(createRootFolder(userId))
        );
    }
    
    public Mono<FolderEntity> getOrCreateBinFolder() {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(userId, "bin_"+userId.toString())
                        .switchIfEmpty(createBinFolder(userId))
        );
    }

    public Mono<FolderEntity> createRootFolder(UUID userId) {
        FolderEntity root = new FolderEntity();
        root.setName("root_"+userId.toString());
        root.setOwnerId(userId);
        root.setParentFolderId(null);
        root.setCreatedAt(Instant.now());
        try {
			Files.createDirectories(Paths.get(storagePath).resolve(userId.toString()).resolve("root_"+userId.toString()));
		} catch (IOException e) {
			e.printStackTrace();
			return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Root folder already exists"));
		}
        return folderRepository.save(root);
    }
    
    public Mono<FolderEntity> createBinFolder(UUID userId) {
        FolderEntity bin = new FolderEntity();
        bin.setName("bin_"+userId.toString());
        bin.setOwnerId(userId);
        bin.setParentFolderId(null);
        bin.setCreatedAt(Instant.now());
        try {
			Files.createDirectories(Paths.get(storagePath).resolve(userId.toString()).resolve("bin_"+userId.toString()));
		} catch (IOException e) {
			e.printStackTrace();
			return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Bin folder already exists"));
		}
        return folderRepository.save(bin);
    }
    
    public Mono<Void> deleteFolder(UUID folderId) {
        return getCurrentUserId()
                .flatMap(userId -> folderRepository.findByIdAndOwnerId(folderId, userId)
                		.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                )
                .flatMap(folder -> {
                    Flux<FolderEntity> allSubs = getAllDescendantFolders(folder, folder.getOwnerId());
                    return allSubs
                        	.collectList()
                        	.flatMapMany(list -> {
                        	    Collections.reverse(list);
                        	    return Flux.fromIterable(list);
                        	})
                            .concatMap(subfolder -> {
                                log.info("Folder list contents in order: " + subfolder.getName());
                                return deleteFilesInFolder(subfolder.getId(), subfolder.getOwnerId())
                                	.then(markDeleted(subfolder));
                            }).collectList().then();
                    });
    }
    
    public Mono<Void> restoreFolder(UUID folderId) {
        return getCurrentUserId()
                .flatMap(userId -> folderRepository.findByIdAndOwnerId(folderId, userId)
                		.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                )
                .flatMap(folder -> {
                    Flux<FolderEntity> allSubs = getAllDescendantFolders(folder, folder.getOwnerId());
                    return allSubs
                        	.collectList()
                        	.flatMapMany(list -> {
                        	    Collections.reverse(list);
                        	    return Flux.fromIterable(list);
                        	})
                            .concatMap(subfolder -> {
                                log.info("Folder list contents in order: " + subfolder.getName());
                                return restoreFilesInFolder(subfolder.getId(), subfolder.getOwnerId())
                                	.then(markRestored(subfolder));
                            }).collectList().then();
                    });
    }
    
    private Mono<Void> markRestored(FolderEntity folder) {
    	return databaseClient.sql("UPDATE folders SET deleted_at = :deletedAt WHERE id = :id")
    			.bindNull("deletedAt", Instant.class)
    			.bind("id", folder.getId())
    			.fetch()
    			.rowsUpdated()
    			.then();
    }
    
    private Mono<Void> markDeleted(FolderEntity folder) {
    	return databaseClient.sql("UPDATE folders SET deleted_at = :deletedAt WHERE id = :id")
    			.bind("deletedAt", Instant.now())
    			.bind("id", folder.getId())
    			.fetch()
    			.rowsUpdated()
    			.then();
    }

    private Mono<Void> restoreFilesInFolder(UUID folderId, UUID userId) {
    	log.info("Restoring files in folder: "+folderId.toString());
        return metadataRepository.findByOwnerIdAndFolderId(userId, folderId)
                .concatMap(file -> {
                	log.info("Restoring file: "+file.getFilename());
                    return storageCtl.restoreFile(file.getId());
                }).subscribeOn(Schedulers.boundedElastic()).collectList()
                .then();
    }
    
    private Mono<Void> deleteFilesInFolder(UUID folderId, UUID userId) {
    	log.info("Deleting files in folder: "+folderId.toString());
        return metadataRepository.findByOwnerIdAndFolderId(userId, folderId)
                .concatMap(file -> {
                	log.info("Deleting file: "+file.getFilename());
                    return storageCtl.deleteFile(file.getId());
                }).subscribeOn(Schedulers.boundedElastic()).collectList()
                .then();
    }
    
    public Mono<Void> purgeFolder(UUID folderId) {
        return getCurrentUserId()
                .flatMap(userId -> folderRepository.findByIdAndOwnerId(folderId, userId)
                		.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                )
                .flatMap(folder -> {
                    Flux<FolderEntity> allSubs = getAllDescendantFolders(folder, folder.getOwnerId());
                    return allSubs
                    	.collectList()
                    	.flatMapMany(list -> {
                    	    Collections.reverse(list);
                    	    return Flux.fromIterable(list);
                    	})
                        .concatMap(subfolder -> {
                            log.info("Folder list contents in order: " + subfolder.getName());
                            return purgeFilesInFolder(subfolder.getId(), subfolder.getOwnerId())
                            	.then(buildPhysicalPath(folder.getOwnerId(),subfolder)
		                			.map(path -> {
		                				try {
											Files.deleteIfExists(path);
											log.info("Deleting path: " + path);
										} catch (IOException e) {
											e.printStackTrace();
											throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder " + subfolder.getName()+
													" on path "+ path + " was not found during deletion");
										}
		                				return Mono.just(path);
		                			})
		                		)
	                			.then(folderRepository.deleteById(subfolder.getId()));
                        }).collectList().then();
                });
    }

    private Mono<Void> purgeFilesInFolder(UUID folderId, UUID userId) {
    	log.info("Purging file in folder: "+folderId.toString());
        return metadataRepository.findByOwnerIdAndFolderId(userId, folderId)
        		.concatMap(file -> {
                	log.info("Purging file: "+file.getFilename());
                    return storageCtl.purgeFile(file.getId());
                }).subscribeOn(Schedulers.boundedElastic()).collectList()
                .then();
    }
    
    private Flux<FolderEntity> getAllDescendantFolders(FolderEntity root, UUID userId) {
        return Mono.just(root)
            .expandDeep(folder -> {
                if (folder.getId() == null) {
                    return Mono.empty();
                }
                return folderRepository.findByOwnerIdAndParentFolderId(userId, folder.getId()).cache();
            });
    }

    public Mono<FolderEntity> createFolder(String name, UUID parentFolderId) {
        return getCurrentUserId().flatMap(userId -> {
        	if (parentFolderId == null) {
        		return getOrCreateRootFolder()
        				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Parent folder not found or access denied")))
                        .flatMap(parent ->
                                folderRepository.existsByOwnerIdAndParentFolderIdIsNullAndName(userId, name)
                                        .flatMap(exists -> {
                                            if (exists) {
                                                return Mono.error(new RuntimeException("Folder already exists"));
                                            }
                                            FolderEntity newFolder = new FolderEntity();
                                            newFolder.setName(name);
                                            newFolder.setOwnerId(userId);
                                            newFolder.setParentFolderId(parent.getId());
                                            newFolder.setCreatedAt(Instant.now());
                                    		try {
												Files.createDirectories(Paths.get(storagePath, userId.toString()).resolve("root_"+userId.toString()).resolve(name));
											} catch (IOException e) {
												e.printStackTrace();
												return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Folder already exists"));
											}
                                            return folderRepository.save(newFolder);
                                        })
                        );
        	}
        	else {
        		return folderRepository.findByIdAndOwnerId(parentFolderId, userId)
                        .switchIfEmpty(Mono.error(new RuntimeException("Parent folder not found or access denied")))
                        .flatMap(parent ->
                                folderRepository.existsByOwnerIdAndParentFolderIdAndName(userId, parentFolderId, name)
                                        .flatMap(exists -> {
                                            if (exists) {
                                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,"Folder already exists"));
                                            }
                                            FolderEntity newFolder = new FolderEntity();
                                            newFolder.setName(name);
                                            newFolder.setOwnerId(userId);
                                            newFolder.setParentFolderId(parentFolderId);
                                            newFolder.setCreatedAt(Instant.now());
                                    		return folderRepository.findById(parentFolderId)
	                                    		.flatMap(parentFolder -> {
	                                    			return buildPhysicalPath(userId,parentFolder);
	                                    		})
	                                    		.flatMap(path -> {
	                                    			try {
														Files.createDirectories(path.resolve(name));
													} catch (IOException e) {
														e.printStackTrace();
														throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Folder already exists");
													}
	                                    			return Mono.just(newFolder);
	                                    		})
	                                    		.then(folderRepository.save(newFolder));
                                        })
                        );
        	}
        });
    }

    public Mono<FolderContent> getFolderContent(UUID folderId) {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Folder not found or access denied")))
                        .flatMap(folder -> {
                            Flux<FolderEntity> subFolders = folderRepository.findByOwnerIdAndParentFolderId(userId, folderId);
                            Flux<FileMetaEntity> files = metadataRepository.findByOwnerIdAndFolderId(userId, folderId);
                            return Mono.zip(subFolders.collectList(), files.collectList(),
                                    (folders, fileList) -> new FolderContent(folder, folders, fileList));
                        })
        );
    }
    public Mono<FolderContent> getRootContent() {
        return getOrCreateRootFolder().flatMap(root -> getFolderContent(root.getId()));
    }
    public Mono<FolderContent> getBinContent() {
        return getOrCreateBinFolder().flatMap(root -> getFolderContent(root.getId()));
    }
    public Mono<FolderMeta> getFolderMeta(UUID folderId) {
    	return getCurrentUserId().flatMap(userId -> {
    		if (folderId != null) {
    			return folderRepository.findByIdAndOwnerId(folderId, userId).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Folder not found or access denied")))
    					.flatMap(folder -> {
    						return Mono.just(
    								new FolderMeta(
    										folder.getId(),
    										folder.getName(),
    										folder.getParentFolderId(),
    										folder.getOwnerId(),
    										folder.getCreatedAt(),
    										folder.getDeletedAt()
    										)
    								);
    					});
    		}
    		else {
    			return getOrCreateRootFolder().switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Folder not found or access denied")))
    					.flatMap(folder -> {
    						return Mono.just(
    								new FolderMeta(
    										folder.getId(),
    										folder.getName(),
    										folder.getParentFolderId(),
    										folder.getOwnerId(),
    										folder.getCreatedAt(),
    										folder.getDeletedAt()
    										)
    								);
    					});

    		}
    	}
    	);
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

    public record FolderContent(FolderEntity currentFolder, List<FolderEntity> subFolders, List<FileMetaEntity> files) {}
    public record FolderMeta(UUID folderId, String name, UUID parentId, UUID ownerId, Instant createdAt, Instant deletedAt) {}
}
