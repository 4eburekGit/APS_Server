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
import java.nio.file.StandardCopyOption;
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
        // Personal-storage endpoints are user-only. Admins are stored in a
        // separate table and don't satisfy the folders.owner_id → users.id
        // FK, so let any admin call here fail fast with a clean 403 instead
        // of a downstream "fk_folder_owner" SQL exception.
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (IdentifiedPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(p -> p instanceof UserEntity
                        ? Mono.just(p.getId())
                        : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Administrator accounts have no personal storage")));
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
    
    /* =========================================================
       Delete folder = move it (as a single unit) into the user's bin.
       Per product spec: trashing a folder lands it directly in bin/
       — we do NOT unpack its contents. The folder's children keep
       their parent_folder_id pointing at the folder itself, so when
       the user restores the folder, its contents come back intact.

       Only the top-level binned folder gets deleted_at = now; its
       descendants stay "live" but unreachable except via the bin
       (since their ancestor is now under bin).
       ========================================================= */
    public Mono<Void> deleteFolder(UUID folderId) {
        return getCurrentUserId()
                .flatMap(userId -> folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                        .flatMap(folder -> {
                            if (folder.getParentFolderId() == null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete a system folder"));
                            }
                            if (folder.getDeletedAt() != null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Folder is already in the bin"));
                            }
                            return getOrCreateBinFolder()
                                    .flatMap(bin -> {
                                        if (bin.getId().equals(folder.getParentFolderId())) {
                                            // already lives under bin (shouldn't happen in normal flow)
                                            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Folder is already in the bin"));
                                        }
                                        // Pick a non-colliding name inside bin so the unique
                                        // constraint (name, parent_folder_id, owner_id) doesn't
                                        // trip when the user trashes two folders that share a name.
                                        return uniqueFolderName(userId, bin.getId(), folder.getName())
                                                .flatMap(uniqueName ->
                                                        Mono.zip(buildPhysicalPath(userId, folder), buildPhysicalPath(userId, bin))
                                                                .flatMap(t -> {
                                                                    Path src = t.getT1();
                                                                    Path dst = t.getT2().resolve(uniqueName);
                                                                    return Mono.fromCallable(() -> {
                                                                                Files.createDirectories(dst.getParent());
                                                                                if (Files.exists(src)) {
                                                                                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                                                                }
                                                                                return dst;
                                                                            }).subscribeOn(Schedulers.boundedElastic())
                                                                            // Refresh storage_path on every metadata row in
                                                                            // the moved subtree (folder + descendants).
                                                                            .then(databaseClient.sql(
                                                                                            "UPDATE metadata SET storage_path = REPLACE(storage_path, :old, :new) " +
                                                                                            "WHERE owner_id = :owner AND folder_id IN (" +
                                                                                            "  WITH RECURSIVE tree AS (" +
                                                                                            "    SELECT id FROM folders WHERE id = :rootId" +
                                                                                            "    UNION ALL" +
                                                                                            "    SELECT f.id FROM folders f INNER JOIN tree ON f.parent_folder_id = tree.id" +
                                                                                            "  ) SELECT id FROM tree)")
                                                                                    .bind("old", src.toString())
                                                                                    .bind("new", dst.toString())
                                                                                    .bind("owner", userId)
                                                                                    .bind("rootId", folder.getId())
                                                                                    .fetch().rowsUpdated())
                                                                            // Reparent + rename + mark deleted in one shot.
                                                                            .then(databaseClient.sql(
                                                                                            "UPDATE folders SET parent_folder_id = :bin, name = :name, deleted_at = :deletedAt WHERE id = :id")
                                                                                    .bind("bin", bin.getId())
                                                                                    .bind("name", uniqueName)
                                                                                    .bind("deletedAt", Instant.now())
                                                                                    .bind("id", folder.getId())
                                                                                    .fetch().rowsUpdated())
                                                                            .then();
                                                                }));
                                    });
                        }));
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

    /* =========================================================
       Rename folder — change name in DB and on disk. Updates the
       storage_path of every metadata row in the subtree (the folder
       itself and all its descendants) via a recursive CTE so files
       that live inside the renamed folder still resolve correctly.
       Refuses to rename system folders (root_/bin_) or to a name
       that already exists at the same parent level.
       ========================================================= */
    public Mono<FolderEntity> renameFolder(UUID folderId, String newName) {
        if (newName == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required"));
        }
        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty"));
        }
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot contain slashes"));
        }
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                        .flatMap(folder -> {
                            if (folder.getParentFolderId() == null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot rename a system folder"));
                            }
                            if (trimmed.equals(folder.getName())) {
                                return Mono.just(folder);
                            }
                            return folderRepository.existsByOwnerIdAndParentFolderIdAndName(userId, folder.getParentFolderId(), trimmed)
                                    .flatMap(exists -> {
                                        if (exists) {
                                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Folder with this name already exists here"));
                                        }
                                        return buildPhysicalPath(userId, folder)
                                                .flatMap(srcPath -> {
                                                    Path dstPath = srcPath.resolveSibling(trimmed);
                                                    return Mono.fromCallable(() -> {
                                                                if (Files.exists(srcPath)) {
                                                                    Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING);
                                                                }
                                                                return dstPath;
                                                            }).subscribeOn(Schedulers.boundedElastic())
                                                            .then(databaseClient.sql(
                                                                            // Refresh storage_path for every file in the subtree
                                                                            // (recursive CTE walks descendants).
                                                                            "UPDATE metadata SET storage_path = REPLACE(storage_path, :old, :new) " +
                                                                            "WHERE owner_id = :owner AND folder_id IN (" +
                                                                            "  WITH RECURSIVE tree AS (" +
                                                                            "    SELECT id FROM folders WHERE id = :rootId" +
                                                                            "    UNION ALL" +
                                                                            "    SELECT f.id FROM folders f INNER JOIN tree ON f.parent_folder_id = tree.id" +
                                                                            "  ) SELECT id FROM tree)")
                                                                    .bind("old", srcPath.toString())
                                                                    .bind("new", dstPath.toString())
                                                                    .bind("owner", userId)
                                                                    .bind("rootId", folder.getId())
                                                                    .fetch().rowsUpdated())
                                                            .then(databaseClient.sql("UPDATE folders SET name = :name WHERE id = :id")
                                                                    .bind("name", trimmed)
                                                                    .bind("id", folder.getId())
                                                                    .fetch().rowsUpdated())
                                                            .then(folderRepository.findByIdAndOwnerId(folderId, userId));
                                                });
                                    });
                        }));
    }

    /* =========================================================
       Restore folder — inverse of {@link #deleteFolder}. The binned
       folder lives under bin_<uid>/ on disk and has parent_folder_id =
       <bin>; we move it back under root_<uid>/, reparent it, and
       clear deleted_at. Descendants moved with it (we never unpacked
       them on delete) so their parent_folder_id is unchanged — only
       their storage_path needs prefix-rewriting.

       Also clears deleted_at on any descendant rows that still carry
       it from the legacy delete behaviour, so old trash data restores
       cleanly too.
       ========================================================= */
    public Mono<FolderEntity> restoreFolder(UUID folderId) {
        return getCurrentUserId()
                .flatMap(userId -> folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                        .flatMap(folder -> {
                            if (folder.getDeletedAt() == null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Folder is not deleted"));
                            }
                            return getOrCreateRootFolder()
                                    .flatMap(root -> uniqueFolderName(userId, root.getId(), folder.getName())
                                            .flatMap(uniqueName ->
                                                    Mono.zip(buildPhysicalPath(userId, folder), buildPhysicalPath(userId, root))
                                                            .flatMap(t -> {
                                                                Path src = t.getT1();
                                                                Path dst = t.getT2().resolve(uniqueName);
                                                                return Mono.fromCallable(() -> {
                                                                            Files.createDirectories(dst.getParent());
                                                                            if (Files.exists(src)) {
                                                                                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                                                            }
                                                                            return dst;
                                                                        }).subscribeOn(Schedulers.boundedElastic())
                                                                        .then(databaseClient.sql(
                                                                                        "UPDATE metadata SET storage_path = REPLACE(storage_path, :old, :new), deleted_at = NULL " +
                                                                                        "WHERE owner_id = :owner AND folder_id IN (" +
                                                                                        "  WITH RECURSIVE tree AS (" +
                                                                                        "    SELECT id FROM folders WHERE id = :rootId" +
                                                                                        "    UNION ALL" +
                                                                                        "    SELECT f.id FROM folders f INNER JOIN tree ON f.parent_folder_id = tree.id" +
                                                                                        "  ) SELECT id FROM tree)")
                                                                                .bind("old", src.toString())
                                                                                .bind("new", dst.toString())
                                                                                .bind("owner", userId)
                                                                                .bind("rootId", folder.getId())
                                                                                .fetch().rowsUpdated())
                                                                        // Clear deleted_at on legacy descendants (older trash)
                                                                        .then(databaseClient.sql(
                                                                                        "UPDATE folders SET deleted_at = NULL WHERE owner_id = :owner AND id IN (" +
                                                                                        "  WITH RECURSIVE tree AS (" +
                                                                                        "    SELECT id FROM folders WHERE id = :rootId" +
                                                                                        "    UNION ALL" +
                                                                                        "    SELECT f.id FROM folders f INNER JOIN tree ON f.parent_folder_id = tree.id" +
                                                                                        "  ) SELECT id FROM tree)")
                                                                                .bind("owner", userId)
                                                                                .bind("rootId", folder.getId())
                                                                                .fetch().rowsUpdated())
                                                                        // Reparent + rename + clear deleted_at on the folder itself
                                                                        .then(databaseClient.sql(
                                                                                        "UPDATE folders SET parent_folder_id = :p, name = :name, deleted_at = NULL WHERE id = :id")
                                                                                .bind("p", root.getId())
                                                                                .bind("name", uniqueName)
                                                                                .bind("id", folder.getId())
                                                                                .fetch().rowsUpdated())
                                                                        .then(folderRepository.findByIdAndOwnerId(folderId, userId));
                                                            })));
                        }));
    }

    /* =========================================================
       Move folder — change parent, also move on disk recursively.
       Validates that newParentId is not a descendant (would orphan the tree).
       ========================================================= */
    public Mono<FolderEntity> moveFolder(UUID folderId, UUID newParentId) {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                        .flatMap(folder -> {
                            if (folder.getParentFolderId() == null) {
                                // root_/bin_ folders must not be moved
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot move a system folder"));
                            }
                            return resolveTargetParent(userId, newParentId)
                                    .flatMap(targetParent -> {
                                        if (targetParent.getId().equals(folder.getId())) {
                                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move folder into itself"));
                                        }
                                        return isDescendantOf(targetParent, folder, userId)
                                                .flatMap(isDescendant -> {
                                                    if (isDescendant) {
                                                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move folder into one of its descendants"));
                                                    }
                                                    return moveFolderOnDisk(userId, folder, targetParent)
                                                            .then(databaseClient.sql("UPDATE folders SET parent_folder_id = :p WHERE id = :id")
                                                                    .bind("p", targetParent.getId())
                                                                    .bind("id", folder.getId())
                                                                    .fetch().rowsUpdated())
                                                            .then(folderRepository.findByIdAndOwnerId(folderId, userId));
                                                });
                                    });
                        }));
    }

    /* =========================================================
       Copy folder — deep recursive copy, preserves structure & files.
       Generates a unique name in the destination if a collision exists.
       ========================================================= */
    public Mono<FolderEntity> copyFolder(UUID folderId, UUID newParentId) {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                        .flatMap(src -> {
                            if (src.getParentFolderId() == null) {
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot copy a system folder"));
                            }
                            return resolveTargetParent(userId, newParentId)
                                    .flatMap(targetParent -> uniqueFolderName(userId, targetParent.getId(), src.getName())
                                            .flatMap(uniqueName -> copyFolderRecursive(userId, src, targetParent, uniqueName)));
                        }));
    }

    /* ---------- helpers for move/copy/restore ---------- */

    private Mono<FolderEntity> resolveTargetParent(UUID userId, UUID newParentId) {
        if (newParentId == null) {
            return getOrCreateRootFolder();
        }
        return folderRepository.findByIdAndOwnerId(newParentId, userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Target parent not found or access denied")));
    }

    /** True iff {@code candidate} is the same as or a descendant of {@code ancestor}. */
    private Mono<Boolean> isDescendantOf(FolderEntity candidate, FolderEntity ancestor, UUID userId) {
        return getAllDescendantFolders(ancestor, userId)
                .any(f -> f.getId().equals(candidate.getId()));
    }

    private Mono<String> uniqueFolderName(UUID userId, UUID parentId, String baseName) {
        return Flux.range(0, 10000)
                .concatMap(i -> {
                    String candidate = (i == 0) ? baseName : baseName + " (копия" + (i == 1 ? "" : " " + i) + ")";
                    return folderRepository.existsByOwnerIdAndParentFolderIdAndName(userId, parentId, candidate)
                            .map(exists -> exists ? null : candidate);
                })
                .filter(name -> name != null)
                .next();
    }

    private Mono<Void> moveFolderOnDisk(UUID userId, FolderEntity folder, FolderEntity newParent) {
        return Mono.zip(buildPhysicalPath(userId, folder), buildPhysicalPath(userId, newParent))
                .flatMap(t -> {
                    Path src = t.getT1();
                    Path dst = t.getT2().resolve(folder.getName());
                    return Mono.fromCallable(() -> {
                                if (Files.exists(src)) {
                                    Files.createDirectories(dst.getParent());
                                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                }
                                return dst;
                            }).subscribeOn(Schedulers.boundedElastic())
                            .then(databaseClient.sql(
                                    // Refresh storage_path on every metadata row that lives in
                                    // this subtree (the moved folder + all of its descendants).
                                    "UPDATE metadata SET storage_path = REPLACE(storage_path, :old, :new) " +
                                    "WHERE owner_id = :owner AND folder_id IN (" +
                                    "  WITH RECURSIVE tree AS (" +
                                    "    SELECT id FROM folders WHERE id = :rootId" +
                                    "    UNION ALL" +
                                    "    SELECT f.id FROM folders f INNER JOIN tree ON f.parent_folder_id = tree.id" +
                                    "  ) SELECT id FROM tree)")
                                .bind("old", src.toString())
                                .bind("new", dst.toString())
                                .bind("owner", userId)
                                .bind("rootId", folder.getId())
                                .fetch().rowsUpdated()
                                .then());
                });
    }

    private Mono<FolderEntity> copyFolderRecursive(UUID userId, FolderEntity src, FolderEntity targetParent, String newName) {
        // Create the destination folder
        FolderEntity newFolder = new FolderEntity();
        newFolder.setName(newName);
        newFolder.setOwnerId(userId);
        newFolder.setParentFolderId(targetParent.getId());
        newFolder.setCreatedAt(Instant.now());

        return Mono.zip(buildPhysicalPath(userId, src), buildPhysicalPath(userId, targetParent))
                .flatMap(t -> Mono.fromCallable(() -> {
                    Path dstDir = t.getT2().resolve(newName);
                    Files.createDirectories(dstDir);
                    return dstDir;
                }).subscribeOn(Schedulers.boundedElastic())
                  .flatMap(dstDir -> folderRepository.save(newFolder)
                        .flatMap(saved ->
                                // 1) Copy files in this folder
                                metadataRepository.findByOwnerIdAndFolderId(userId, src.getId())
                                        .filter(f -> f.getDeletedAt() == null)
                                        .concatMap(file -> Mono.fromCallable(() -> {
                                                    Path srcFile = Path.of(file.getStoragePath());
                                                    Path dstFile = dstDir.resolve(file.getFilename());
                                                    if (Files.exists(srcFile)) Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING);
                                                    return dstFile;
                                                }).subscribeOn(Schedulers.boundedElastic())
                                                .flatMap(dstFile -> {
                                                    FileMetaEntity copy = new FileMetaEntity();
                                                    // NB: leave id null — INSERT (see StorageController.copyFile)
                                                    copy.setFilename(file.getFilename());
                                                    copy.setContentType(file.getContentType());
                                                    copy.setSize(file.getSize());
                                                    copy.setStoragePath(dstFile.toString());
                                                    copy.setUploadedAt(Instant.now());
                                                    copy.setOwnerId(userId);
                                                    copy.setFolderId(saved.getId());
                                                    return metadataRepository.save(copy);
                                                }))
                                        .then()
                                // 2) Recursively copy sub-folders
                                .then(folderRepository.findByOwnerIdAndParentFolderId(userId, src.getId())
                                        .filter(sub -> sub.getDeletedAt() == null)
                                        .concatMap(sub -> copyFolderRecursive(userId, sub, saved, sub.getName()))
                                        .then())
                                .thenReturn(saved))));
    }

    public record FolderContent(FolderEntity currentFolder, List<FolderEntity> subFolders, List<FileMetaEntity> files) {}
    public record FolderMeta(UUID folderId, String name, UUID parentId, UUID ownerId, Instant createdAt, Instant deletedAt) {}
}
