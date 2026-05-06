package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPanelControllerTest {

    @Mock
    private AdminPanelService adminPanelService;

    @InjectMocks
    private AdminPanelController adminPanelController;

    @Test
    void listUsers_shouldReturnFluxFromService() {
        AdminPanelService.UserInfo info = new AdminPanelService.UserInfo(
                UUID.randomUUID(), "alice", "USER", 100L, 10737418240L, 1L);
        when(adminPanelService.listUsers()).thenReturn(Flux.just(info));

        StepVerifier.create(adminPanelController.listUsers())
                .expectNextMatches(u -> "alice".equals(u.username()))
                .verifyComplete();
    }

    @Test
    void getUser_shouldReturnUserFromService() {
        UUID userId = UUID.randomUUID();
        AdminPanelService.UserInfo info = new AdminPanelService.UserInfo(
                userId, "bob", "USER", 200L, 10737418240L, 2L);
        when(adminPanelService.getUser(userId)).thenReturn(Mono.just(info));

        StepVerifier.create(adminPanelController.getUser(userId))
                .expectNextMatches(u -> "bob".equals(u.username()))
                .verifyComplete();
    }

    @Test
    void getUser_whenNotFound_shouldPropagateError() {
        UUID userId = UUID.randomUUID();
        when(adminPanelService.getUser(userId))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));

        StepVerifier.create(adminPanelController.getUser(userId))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void deleteUser_shouldReturnOkResponse() {
        UUID userId = UUID.randomUUID();
        when(adminPanelService.deleteUser(userId)).thenReturn(Mono.empty());

        StepVerifier.create(adminPanelController.deleteUser(userId))
                .expectNextMatches(r -> r.getStatusCode() == HttpStatus.OK)
                .verifyComplete();
    }

    @Test
    void deleteUser_whenNotFound_shouldPropagateError() {
        UUID userId = UUID.randomUUID();
        when(adminPanelService.deleteUser(userId))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));

        StepVerifier.create(adminPanelController.deleteUser(userId))
                .expectError(ResponseStatusException.class)
                .verify();
    }
}
