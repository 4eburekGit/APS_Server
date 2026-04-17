package server.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import server.FolderEntity;

import java.util.UUID;

public interface FolderRepo extends ReactiveCrudRepository<FolderEntity, UUID> {
    Flux<FolderEntity> findByOwnerIdAndParentFolderId(UUID ownerId, UUID parentFolderId);
    Flux<FolderEntity> findByOwnerIdAndParentFolderIdIsNull(UUID ownerId); // root folder
    Mono<FolderEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
    Mono<FolderEntity> findByOwnerIdAndParentFolderIdIsNullAndName(UUID ownerId, String name); // root by name
    Mono<Boolean> existsByOwnerIdAndParentFolderIdAndName(UUID ownerId, UUID parentFolderId, String name);
}
