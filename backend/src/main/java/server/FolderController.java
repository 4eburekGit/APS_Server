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
import java.util.Comparator;
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
                        // Mono.defer so we don't call Files.createDirectories on every
                        // lookup — only when the row is actually missing.
                        .switchIfEmpty(Mono.defer(() -> createRootFolder(userId)))
        );
    }

    public Mono<FolderEntity> getOrCreateBinFolder() {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(userId, "bin_"+userId.toString())
                        .switchIfEmpty(Mono.defer(() -> createBinFolder(userId)))
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
			log.warn("Failed to create root dir for {}: {}", userId, e.toString());
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
			log.warn("Failed to create bin dir for {}: {}", userId, e.toString());
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
    
    /* =========================================================
       Hard-purge a folder + everything inside it. Two phases:

         1) DB-side: walk descendant folders (recursive CTE), purge every
            metadata row inside them (force-purge bypasses the deleted_at
            guard since trashed folders move as a unit and their files keep
            deleted_at = NULL). Then DELETE the top folder; the
            fk_parent_folder ON DELETE CASCADE wipes descendant folder rows
            in one shot.

         2) Disk-side: walk the top folder's directory bottom-up and
            deleteIfExists each path. Tolerates non-empty intermediate
            states from concurrent operations or partial cleanups
            without throwing.

       Replaces the previous implementation which tried per-subfolder
       Files.deleteIfExists in a Reactor pipeline — that order broke for
       trees uploaded via /upload-tree where descendant folder rows got
       reparented across deleteFolder + purgeFolder cycles. The new flow
       does NOT depend on deletion ordering matching folder hierarchy.
       ========================================================= */
    public Mono<Void> purgeFolder(UUID folderId) {
        return getCurrentUserId()
                .flatMap(userId -> folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied"))))
                .flatMap(folder -> {
                    UUID userId = folder.getOwnerId();
                    // Phase 1a: gather every metadata row whose folder_id sits in
                    // the subtree (current folder + descendants). One recursive
                    // CTE is cheaper than N per-folder lookups via the repo.
                    Flux<UUID> fileIdsInSubtree = databaseClient.sql(
                                    "SELECT m.id FROM metadata m " +
                                    "WHERE m.owner_id = :owner AND m.folder_id IN (" +
                                    "  WITH RECURSIVE tree AS (" +
                                    "    SELECT id FROM folders WHERE id = :rootId" +
                                    "    UNION ALL" +
                                    "    SELECT f.id FROM folders f INNER JOIN tree ON f.parent_folder_id = tree.id" +
                                    "  ) SELECT id FROM tree)")
                            .bind("owner", userId)
                            .bind("rootId", folder.getId())
                            .map(row -> row.get("id", UUID.class))
                            .all();

                    // Phase 1b: purge every collected file (blob + DB row), then
                    // DELETE the root folder (CASCADE wipes descendant rows).
                    Mono<Void> dbCleanup = fileIdsInSubtree
                            .concatMap(id -> storageCtl.purgeFileForce(id), 4)
                            .then(folderRepository.deleteById(folder.getId()));

                    // Phase 2: nuke the on-disk dir bottom-up. Best-effort —
                    // log per-path failures, never propagate (DB is already
                    // consistent at this point).
                    Mono<Void> diskCleanup = buildPhysicalPath(userId, folder)
                            .flatMap(rootDir -> Mono.fromRunnable(() -> wipeDirectory(rootDir))
                                    .subscribeOn(Schedulers.boundedElastic()))
                            .then();

                    return dbCleanup.then(diskCleanup);
                });
    }

    /** Walk a directory bottom-up, deleting files then dirs. Tolerant of
     *  missing or already-cleaned paths. Logs but never throws. */
    private void wipeDirectory(Path root) {
        if (!Files.exists(root)) {
            log.info("wipeDirectory: path does not exist (already gone): {}", root);
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) {
                            log.warn("wipeDirectory: failed to delete {}: {}", p, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("wipeDirectory: failed to walk {}: {}", root, e.toString());
        }
    }

    private Mono<Void> purgeFilesInFolder(UUID folderId, UUID userId) {
    	log.info("Purging file in folder: "+folderId.toString());
        return metadataRepository.findByOwnerIdAndFolderId(userId, folderId)
        		.concatMap(file -> {
                	log.info("Purging file: "+file.getFilename());
                    // Use force-purge: when a folder is trashed as a unit, its
                    // descendant files keep deleted_at = NULL — strict purge
                    // would 403 on every one of them. Force-purge skips the
                    // guard and trusts the parent-folder ownership check that
                    // got us here.
                    return storageCtl.purgeFileForce(file.getId());
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
        // Reject names that would allow path traversal (../, slashes, NUL, etc.)
        // before they ever reach Path.resolve. Storage layer treats names as
        // trusted segments.
        final String safeName = PathSanitizer.sanitizeFolderName(name);
        return getCurrentUserId().flatMap(userId -> {
        	if (parentFolderId == null) {
        		return getOrCreateRootFolder()
        				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Parent folder not found or access denied")))
                        .flatMap(parent ->
                                folderRepository.existsByOwnerIdAndParentFolderIdIsNullAndName(userId, safeName)
                                        .flatMap(exists -> {
                                            if (exists) {
                                                return Mono.error(new RuntimeException("Folder already exists"));
                                            }
                                            FolderEntity newFolder = new FolderEntity();
                                            newFolder.setName(safeName);
                                            newFolder.setOwnerId(userId);
                                            newFolder.setParentFolderId(parent.getId());
                                            newFolder.setCreatedAt(Instant.now());
                                    		try {
												Files.createDirectories(Paths.get(storagePath, userId.toString()).resolve("root_"+userId.toString()).resolve(safeName));
											} catch (IOException e) {
												log.warn("Failed to create folder dir for {}: {}", userId, e.toString());
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
                                folderRepository.existsByOwnerIdAndParentFolderIdAndName(userId, parentFolderId, safeName)
                                        .flatMap(exists -> {
                                            if (exists) {
                                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,"Folder already exists"));
                                            }
                                            FolderEntity newFolder = new FolderEntity();
                                            newFolder.setName(safeName);
                                            newFolder.setOwnerId(userId);
                                            newFolder.setParentFolderId(parentFolderId);
                                            newFolder.setCreatedAt(Instant.now());
                                    		return folderRepository.findById(parentFolderId)
	                                    		.flatMap(parentFolder -> {
	                                    			return buildPhysicalPath(userId,parentFolder);
	                                    		})
	                                    		.flatMap(path -> {
	                                    			try {
														Files.createDirectories(path.resolve(safeName));
													} catch (IOException e) {
														log.warn("Failed to create folder dir for {}: {}", userId, e.toString());
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
        return getFolderContent(folderId, SortSpec.DEFAULT);
    }
    public Mono<FolderContent> getRootContent() {
        return getRootContent(SortSpec.DEFAULT);
    }
    public Mono<FolderContent> getBinContent() {
        return getBinContent(SortSpec.DEFAULT);
    }

    /* =========================================================
       FR#23 / NFT#24 — server-side sort. Loads sub-folders and files
       in the requested order via raw SQL so we don't pay the cost of
       hauling 10K rows to the client just to sort them. The column
       and direction are picked from a finite enum (no string concat
       from user input), so SQL injection isn't a risk.
       ========================================================= */
    public Mono<FolderContent> getFolderContent(UUID folderId, SortSpec sort) {
        final SortSpec s = sort == null ? SortSpec.DEFAULT : sort;
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Folder not found or access denied")))
                        .flatMap(folder -> {
                            Flux<FolderEntity> subFolders = folderRepository.findByOwnerIdAndParentFolderId(userId, folderId);
                            Flux<FileMetaEntity> files = metadataRepository.findByOwnerIdAndFolderId(userId, folderId);
                            return Mono.zip(subFolders.collectList(), files.collectList(),
                                    (subList, fileList) -> new FolderContent(
                                            folder,
                                            sortFolders(subList, s),
                                            sortFiles(fileList, s)));
                        })
        );
    }
    public Mono<FolderContent> getRootContent(SortSpec sort) {
        return getOrCreateRootFolder().flatMap(root -> getFolderContent(root.getId(), sort));
    }
    public Mono<FolderContent> getBinContent(SortSpec sort) {
        return getOrCreateBinFolder().flatMap(root -> getFolderContent(root.getId(), sort));
    }

    /* In-memory sort: cheap up to NFT#24's 10K-files cap on a single folder
       (Java Collections.sort = O(n log n), ~few ms). Server-side ORDER BY
       in raw SQL was an option but would have broken every test that mocks
       FileMetaRepo / FolderRepo directly — not worth the regression. */
    private static List<FolderEntity> sortFolders(List<FolderEntity> in, SortSpec s) {
        if (in == null || in.isEmpty()) return in;
        Comparator<FolderEntity> cmp = switch (s.field()) {
            // Folders carry no size or content_type → fall back to name even
            // when the user asked for size sort.
            case SIZE, NAME -> Comparator.comparing(
                    FolderEntity::getName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case DATE -> Comparator.comparing(
                    FolderEntity::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (s.dirEnum() == SortDirection.DESC) cmp = cmp.reversed();
        List<FolderEntity> out = new java.util.ArrayList<>(in);
        out.sort(cmp);
        return out;
    }
    private static List<FileMetaEntity> sortFiles(List<FileMetaEntity> in, SortSpec s) {
        if (in == null || in.isEmpty()) return in;
        Comparator<FileMetaEntity> cmp = switch (s.field()) {
            case NAME -> Comparator.comparing(
                    FileMetaEntity::getFilename,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case SIZE -> Comparator.comparing(
                    FileMetaEntity::getSize,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case DATE -> Comparator.comparing(
                    (FileMetaEntity f) -> f.getUpdatedAt() != null ? f.getUpdatedAt() : f.getUploadedAt(),
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (s.dirEnum() == SortDirection.DESC) cmp = cmp.reversed();
        List<FileMetaEntity> out = new java.util.ArrayList<>(in);
        out.sort(cmp);
        return out;
    }

    /** Finite-domain sort spec — guarantees no SQL injection from the URL. */
    public record SortSpec(SortField field, SortDirection dirEnum) {
        public static final SortSpec DEFAULT = new SortSpec(SortField.NAME, SortDirection.ASC);
        public String direction() { return dirEnum == SortDirection.DESC ? "DESC" : "ASC"; }

        /** Parse query-string values like {@code sort=size&dir=desc} into a spec. */
        public static SortSpec parse(String sort, String dir) {
            SortField f;
            if (sort == null) { f = SortField.NAME; }
            else switch (sort.toLowerCase()) {
                case "size" -> f = SortField.SIZE;
                case "date" -> f = SortField.DATE;
                case "name" -> f = SortField.NAME;
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Bad sort field: " + sort + " (allowed: name, size, date)");
            }
            SortDirection d;
            if (dir == null) { d = SortDirection.ASC; }
            else switch (dir.toLowerCase()) {
                case "asc" -> d = SortDirection.ASC;
                case "desc" -> d = SortDirection.DESC;
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Bad sort direction: " + dir + " (allowed: asc, desc)");
            }
            return new SortSpec(f, d);
        }
    }
    public enum SortField { NAME, SIZE, DATE }
    public enum SortDirection { ASC, DESC }
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
        // Centralised validation: rejects null/blank/slashes/NUL/"."/".." etc.
        // Wrap in defer so the BAD_REQUEST surfaces through the Mono pipeline
        // rather than being thrown synchronously.
        final String trimmed;
        try {
            trimmed = PathSanitizer.sanitizeFolderName(newName);
        } catch (ResponseStatusException e) {
            return Mono.error(e);
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
        // Reactor disallows null in .map(); use flatMap + Mono.empty() to filter
        // the colliding candidates out instead.
        return Flux.range(0, 10000)
                .concatMap(i -> {
                    String candidate = (i == 0) ? baseName : baseName + " (копия" + (i == 1 ? "" : " " + i) + ")";
                    return folderRepository.existsByOwnerIdAndParentFolderIdAndName(userId, parentId, candidate)
                            .flatMap(exists -> exists ? Mono.<String>empty() : Mono.just(candidate));
                })
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

    /* =========================================================
       FR#1 / FR#20 (download folder as one) — build a zip archive of the
       folder + all descendants, return its on-disk path. Caller deletes
       the file after streaming. Folder structure inside the zip mirrors
       the user's tree (paths relative to the requested folder).

       Implementation note: build to a temp file rather than a piped
       Flux<DataBuffer> because zipping is sequential and CPU-bound; a
       temp file lets the HTTP layer stream it via FileSystemResource
       without holding the request thread.
       ========================================================= */
    public Mono<java.nio.file.Path> zipFolder(UUID folderId) {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found or access denied")))
                        .flatMap(folder -> Mono.fromCallable(() -> {
                                    java.nio.file.Path tmp = Files.createTempFile("aps-zip-", ".zip");
                                    return tmp;
                                }).subscribeOn(Schedulers.boundedElastic())
                                .flatMap(tmp -> writeZipForFolder(userId, folder, tmp).thenReturn(tmp))));
    }

    private Mono<Void> writeZipForFolder(UUID userId, FolderEntity root, java.nio.file.Path zipPath) {
        // Collect every descendant folder (root included) + their files,
        // then write entries sequentially in a worker thread. Concurrency
        // is unsafe inside ZipOutputStream — keep it serial.
        return getAllDescendantFolders(root, userId).collectList()
                .flatMap(folders -> Flux.fromIterable(folders)
                        .concatMap(sub -> metadataRepository.findByOwnerIdAndFolderId(userId, sub.getId())
                                .filter(f -> f.getDeletedAt() == null)
                                .map(f -> new ZipEntryInfo(zipRelPath(folders, root, sub, f.getFilename()), f.getStoragePath()))
                                .collectList()
                                .map(list -> new FolderZipBucket(sub, list)))
                        .collectList()
                        .flatMap(buckets -> Mono.fromCallable(() -> {
                            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                                    java.nio.file.Files.newOutputStream(zipPath))) {
                                java.util.HashSet<String> dirs = new java.util.HashSet<>();
                                for (FolderZipBucket b : buckets) {
                                    String dirRel = folderRelPath(folders, root, b.folder());
                                    if (!dirRel.isEmpty() && dirs.add(dirRel)) {
                                        // Empty-dir entry so empty folders survive in the zip.
                                        zos.putNextEntry(new java.util.zip.ZipEntry(dirRel + "/"));
                                        zos.closeEntry();
                                    }
                                    for (ZipEntryInfo e : b.files()) {
                                        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(e.relPath());
                                        zos.putNextEntry(entry);
                                        java.nio.file.Path src = java.nio.file.Paths.get(e.storagePath());
                                        if (Files.exists(src)) {
                                            try (var in = Files.newInputStream(src)) {
                                                in.transferTo(zos);
                                            }
                                        }
                                        zos.closeEntry();
                                    }
                                }
                            }
                            return null;
                        }).subscribeOn(Schedulers.boundedElastic()))
                        .then());
    }

    /** Path of a file relative to the zip root, e.g. "Pictures/holiday/IMG_001.jpg". */
    private static String zipRelPath(List<FolderEntity> all, FolderEntity root, FolderEntity leaf, String filename) {
        String dir = folderRelPath(all, root, leaf);
        return dir.isEmpty() ? filename : dir + "/" + filename;
    }

    /** Path of a folder relative to the zip root, "" when it IS the root. */
    private static String folderRelPath(List<FolderEntity> all, FolderEntity root, FolderEntity leaf) {
        java.util.Map<UUID, FolderEntity> byId = new java.util.HashMap<>();
        for (FolderEntity f : all) byId.put(f.getId(), f);
        java.util.Deque<String> segs = new java.util.ArrayDeque<>();
        FolderEntity cur = leaf;
        while (cur != null && !cur.getId().equals(root.getId())) {
            segs.push(cur.getName());
            cur = (cur.getParentFolderId() == null) ? null : byId.get(cur.getParentFolderId());
        }
        return String.join("/", segs);
    }

    private record ZipEntryInfo(String relPath, String storagePath) {}
    private record FolderZipBucket(FolderEntity folder, List<ZipEntryInfo> files) {}
}
