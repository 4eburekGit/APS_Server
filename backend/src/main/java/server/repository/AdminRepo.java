package server.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import server.AdminEntity;

import java.util.UUID;

public interface AdminRepo extends ReactiveCrudRepository<AdminEntity, UUID> {
    Mono<AdminEntity> findByUsername(String username);
}
