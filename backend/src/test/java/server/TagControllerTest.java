package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import server.repository.FileMetaRepo;
import server.repository.TagRepo;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagControllerTest {

    @Mock private TagRepo tagRepo;
    @Mock private FileMetaRepo fileMetaRepo;
    @Mock private DatabaseClient databaseClient;

    @InjectMocks
    private TagController controller;

    private UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("alice");
        u.setRole("USER");
        u.setPassword("x");
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
    void createRejectsBlankName() {
        StepVerifier.create(controller.create(new TagController.CreateTagRequest(" ", null)))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void createRejectsTooLongName() {
        String big = "x".repeat(65);
        StepVerifier.create(controller.create(new TagController.CreateTagRequest(big, null)))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void createRejectsDuplicate() {
        UserEntity u = user();
        when(tagRepo.existsByOwnerIdAndName(u.getId(), "work")).thenReturn(Mono.just(true));

        StepVerifier.create(withUser(
                        controller.create(new TagController.CreateTagRequest("work", null)),
                        u))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.CONFLICT)
                .verify();
    }

    @Test
    void createPersistsNewTag() {
        UserEntity u = user();
        when(tagRepo.existsByOwnerIdAndName(u.getId(), "work")).thenReturn(Mono.just(false));
        when(tagRepo.save(any(TagEntity.class))).thenAnswer(inv -> {
            TagEntity t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return Mono.just(t);
        });

        StepVerifier.create(withUser(
                        controller.create(new TagController.CreateTagRequest("work", "#abc")),
                        u))
                .assertNext(t -> {
                    assertEquals("work", t.getName());
                    assertEquals(u.getId(), t.getOwnerId());
                    assertEquals("#abc", t.getColor());
                })
                .verifyComplete();
    }

    @Test
    void listReturnsOwnerScoped() {
        UserEntity u = user();
        TagEntity t = new TagEntity();
        t.setId(UUID.randomUUID()); t.setOwnerId(u.getId()); t.setName("a");
        when(tagRepo.findByOwnerId(u.getId())).thenReturn(Flux.just(t));

        StepVerifier.create(withUser(controller.list(), u))
                .expectNext(t)
                .verifyComplete();
    }
}
