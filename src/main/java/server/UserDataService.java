package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import server.repository.UserRepo;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserDataService implements ReactiveUserDetailsService {

    private final UserRepo userRepository;

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        log.debug("Looking up user: {}", username);
        return userRepository.findByUsername(username)
                .doOnNext(user -> log.debug("Found user: {}", user))
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn("User not found: {}", username)))
                .cast(UserDetails.class);
    }
}
