package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminPanelController {

    private final AdminPanelService adminPanelService;

    /** List all users with their storage quota usage. Requires ROLE_ADMIN. */
    @GetMapping("/users")
    public Flux<AdminPanelService.UserInfo> listUsers() {
        return adminPanelService.listUsers();
    }

    /** Get a single user with quota usage info. Requires ROLE_ADMIN. */
    @GetMapping("/users/{userId}")
    public Mono<AdminPanelService.UserInfo> getUser(@PathVariable UUID userId) {
        return adminPanelService.getUser(userId);
    }

    /**
     * Permanently delete a user along with all their files and folders
     * (DB rows cascade, on-disk storage is wiped). Requires ROLE_ADMIN.
     */
    @DeleteMapping("/users/{userId}")
    public Mono<ResponseEntity<String>> deleteUser(@PathVariable UUID userId) {
        return adminPanelService.deleteUser(userId)
                .then(Mono.just(ResponseEntity.ok("User " + userId + " deleted")));
    }
}
