package server.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import server.CommentEntity;

import java.util.UUID;

public interface CommentRepo extends ReactiveCrudRepository<CommentEntity, UUID> {
    Flux<CommentEntity> findByFileIdOrderByCreatedAtDesc(UUID fileId);
}
