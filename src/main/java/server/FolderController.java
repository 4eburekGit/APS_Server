package server;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import server.repository.FileMetaRepo;
import server.repository.FolderRepo;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FolderController {

    private final FolderRepo folderRepository;
    private final FileMetaRepo metadataRepository;

    private Mono<UUID> getCurrentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (UserEntity) ctx.getAuthentication().getPrincipal())
                .map(UserEntity::getId);
    }

    public Mono<FolderEntity> getOrCreateRootFolder() {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByOwnerIdAndParentFolderIdIsNullAndName(userId, "root"+userId.toString())
                        .switchIfEmpty(createRootFolder(userId))
        );
    }

    public Mono<FolderEntity> createRootFolder(UUID userId) {
        FolderEntity root = new FolderEntity();
        root.setName("root_"+userId.toString());
        root.setOwnerId(userId);
        root.setParentFolderId(null);
        return folderRepository.save(root);
    }

    public Mono<FolderEntity> createFolder(String name, UUID parentFolderId) {
        return getCurrentUserId().flatMap(userId -> {
            return folderRepository.findByIdAndOwnerId(parentFolderId, userId)
                    .switchIfEmpty(Mono.error(new RuntimeException("Parent folder not found or access denied")))
                    .flatMap(parent ->
                            folderRepository.existsByOwnerIdAndParentFolderIdAndName(userId, parentFolderId, name)
                                    .flatMap(exists -> {
                                        if (exists) {
                                            return Mono.error(new RuntimeException("Folder already exists"));
                                        }
                                        FolderEntity newFolder = new FolderEntity();
                                        newFolder.setName(name);
                                        newFolder.setOwnerId(userId);
                                        newFolder.setParentFolderId(parentFolderId);
                                        return folderRepository.save(newFolder);
                                    })
                    );
        });
    }

    public Mono<FolderContent> getFolderContent(UUID folderId) {
        return getCurrentUserId().flatMap(userId ->
                folderRepository.findByIdAndOwnerId(folderId, userId)
                        .switchIfEmpty(Mono.error(new RuntimeException("Folder not found or access denied")))
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

    public record FolderContent(FolderEntity currentFolder, List<FolderEntity> subFolders, List<FileMetaEntity> files) {}
}
