package server;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/auth")
// TODO: Expand auth to 2-factor and add sharing (hopefully without rewriting half the fucking code)
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Mono<Map<String, String>> register(@RequestBody AuthRequest request) {
    	return authService.register(request.username(), request.password())
                .map(token -> Map.of("token", token));
    }

    @PostMapping("/login")
    public Mono<Map<String, String>> login(@RequestBody AuthRequest request) {
        return authService.login(request.username(), request.password())
                .map(token -> Map.of("token", token));
    }
    
    @GetMapping("/me")
    public Mono<String> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName());
    }

    record AuthRequest(String username, String password) {}
}
