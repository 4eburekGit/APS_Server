package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import server.repository.CommentRepo;
import server.repository.FileMetaRepo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentControllerTest {

    @Mock private CommentRepo commentRepo;
    @Mock private FileMetaRepo fileMetaRepo;

    @InjectMocks
    private CommentController controller;

    private UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id == null ? UUID.randomUUID() : id);
        u.setUsername("alice"); u.setRole("USER"); u.setPassword("x");
        return u;
    }

    private static <T> Mono<T> withUser(Mono<T> m, UserEntity u) {
        Authentication a = new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return m.contextWrite(ReactiveSecurityContextHolder.withAuthentication(a));
    }
    private static <T> Flux<T> withUser(Flux<T> f, UserEntity u) {
        Authentication a = new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return f.contextWrite(ReactiveSecurityContextHolder.withAuthentication(a));
    }

    @Test
    void addRejectsBlank() {
        StepVerifier.create(controller.add(UUID.randomUUID(),
                        new CommentController.AddCommentRequest("  ")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void addRejectsTooLong() {
        StepVerifier.create(controller.add(UUID.randomUUID(),
                        new CommentController.AddCommentRequest("x".repeat(5001))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void addRequiresFileOwnership() {
        UserEntity u = user(null);
        UUID fileId = UUID.randomUUID();
        when(fileMetaRepo.findByIdAndOwnerId(fileId, u.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(
                        controller.add(fileId, new CommentController.AddCommentRequest("hi")),
                        u))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void addPersistsComment() {
        UserEntity u = user(null);
        UUID fileId = UUID.randomUUID();
        FileMetaEntity f = new FileMetaEntity();
        f.setId(fileId); f.setOwnerId(u.getId()); f.setFilename("x");
        when(fileMetaRepo.findByIdAndOwnerId(fileId, u.getId())).thenReturn(Mono.just(f));
        when(commentRepo.save(any(CommentEntity.class))).thenAnswer(inv -> {
            CommentEntity c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });

        StepVerifier.create(withUser(
                        controller.add(fileId, new CommentController.AddCommentRequest("looks good")),
                        u))
                .assertNext(c -> {
                    assertEquals(fileId, c.getFileId());
                    assertEquals(u.getId(), c.getAuthorId());
                    assertEquals("looks good", c.getBody());
                })
                .verifyComplete();
    }

    @Test
    void deleteAuthorCanRemoveOwn() {
        UserEntity u = user(null);
        UUID fileId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        CommentEntity c = new CommentEntity();
        c.setId(commentId); c.setFileId(fileId); c.setAuthorId(u.getId());
        c.setBody("hi"); c.setCreatedAt(Instant.now());
        when(commentRepo.findById(commentId)).thenReturn(Mono.just(c));
        when(commentRepo.deleteById(commentId)).thenReturn(Mono.empty());

        StepVerifier.create(withUser(controller.delete(fileId, commentId), u))
                .assertNext(resp -> assertEquals(HttpStatus.OK, resp.getStatusCode()))
                .verifyComplete();
    }
}
