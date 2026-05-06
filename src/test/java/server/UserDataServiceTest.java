package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import server.repository.AdminRepo;
import server.repository.UserRepo;

import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDataServiceTest {

    @Mock
    private UserRepo userRepo;

    @Mock
    private AdminRepo adminRepo;

    @InjectMocks
    private UserDataService userDataService;

    @Test
    void findByUsername_shouldReturnUserDetails() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPassword("encoded");
        user.setRole("USER");

        when(userRepo.findByUsername("alice")).thenReturn(Mono.just(user));
        // switchIfEmpty evaluates its argument eagerly at assembly time
        lenient().when(adminRepo.findByUsername("alice")).thenReturn(Mono.empty());

        StepVerifier.create(userDataService.findByUsername("alice"))
                .expectNextMatches(ud -> ud.getUsername().equals("alice"))
                .verifyComplete();
    }

    @Test
    void findByUsername_shouldReturnEmptyWhenNotFound() {
        when(userRepo.findByUsername("unknown")).thenReturn(Mono.empty());
        when(adminRepo.findByUsername("unknown")).thenReturn(Mono.empty());

        StepVerifier.create(userDataService.findByUsername("unknown"))
                .verifyComplete();
    }
}
