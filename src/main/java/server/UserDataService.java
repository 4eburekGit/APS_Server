package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import server.repository.AdminRepo;
import server.repository.UserRepo;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserDataService implements ReactiveUserDetailsService {

    private final UserRepo userRepository;
    private final AdminRepo adminRepository;

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        log.debug("Looking up principal: {}", username);
        return userRepository.findByUsername(username)
                .<UserDetails>cast(UserDetails.class)
                .switchIfEmpty(adminRepository.findByUsername(username).cast(UserDetails.class))
                .doOnNext(principal -> log.debug("Found principal: {} ({})", principal.getUsername(), principal.getAuthorities()))
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn("Principal not found: {}", username)));
    }
}
