package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class JWTAuthFilter implements WebFilter {

    private final JWTHandler jwtService;
    private final ReactiveUserDetailsService userDetailsService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        
        log.debug("Filter processing: {} (thread: {})", path, Thread.currentThread().getName());
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(7);
        String username;
        try {
            username = jwtService.extractUsername(token);
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return chain.filter(exchange);
        }

        return userDetailsService.findByUsername(username)
                .filter(userDetails -> jwtService.validateToken(token, userDetails))
                .single()
                .flatMap(userDetails -> {
                    Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                            userDetails, null, userDetails.getAuthorities()
                    );
                    log.debug("Authentication set for: {}", username);
                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("No valid user for token, continuing without authentication");
                    return chain.filter(exchange);
                }));
    }
}
