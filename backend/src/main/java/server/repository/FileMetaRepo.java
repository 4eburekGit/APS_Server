package server.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import server.FileMetaEntity;

import java.util.UUID;

public interface FileMetaRepo extends ReactiveCrudRepository<FileMetaEntity, UUID> {
    Mono<FileMetaEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
    Flux<FileMetaEntity> findByOwnerIdAndFolderId(UUID ownerId, UUID folderId); // files in folder folderId
    Flux<FileMetaEntity> findByOwnerIdAndFolderIdIsNull(UUID ownerId); // files in root
}