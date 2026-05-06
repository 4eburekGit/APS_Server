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
                .map(ctx -> (ctx.getAuthentication().getName() + "Role: " + ctx.getAuthentication().getAuthorities()));
    }
    
    /**
     * Provision a new admin. Restricted at the security filter chain to
     * existing admins only — the previous implementation was on the
     * {@code /auth/**} permitAll path, so anyone could create themselves an
     * admin account (privilege-escalation vulnerability). The very first
     * admin must be bootstrapped via the {@code ADMIN_BOOTSTRAP_USERNAME} /
     * {@code ADMIN_BOOTSTRAP_PASSWORD} environment variables (see
     * {@link AdminBootstrap}).
     */
    @PostMapping("/register/admin")
    public Mono<Map<String, String>> registerAdmin(@RequestBody AuthRequest request) {
    	return authService.registerAdmin(request.username(), request.password())
                .map(token -> Map.of("token", token));
    }

    @PostMapping("/login/admin")
    public Mono<Map<String, String>> loginAdmin(@RequestBody AuthRequest request) {
        return authService.loginAdmin(request.username(), request.password())
                .map(token -> Map.of("token", token));
    }

    record AuthRequest(String username, String password) {}
}
