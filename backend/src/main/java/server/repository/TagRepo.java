package server.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import server.TagEntity;

import java.util.UUID;

public interface TagRepo extends ReactiveCrudRepository<TagEntity, UUID> {
    Flux<TagEntity> findByOwnerId(UUID ownerId);
    Mono<TagEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
    Mono<Boolean> existsByOwnerIdAndName(UUID ownerId, String name);
}
