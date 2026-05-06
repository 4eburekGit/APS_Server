package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class JWTService implements ServerAuthenticationConverter {

    private final JWTHandler jwtService;
    private final ReactiveUserDetailsService userDetailsService;
    
    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        log.debug("Convert called for path: {}, Authorization header: {}", path, authHeader);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token found for path: {}", path);
            return Mono.empty();
        }

        String token = authHeader.substring(7);
        log.debug("Extracted token: {}", token.substring(0, Math.min(20, token.length())) + "...");

        String username;
        try {
            username = jwtService.extractUsername(token);
            log.debug("Username extracted from token: {}", username);
        } catch (Exception e) {
            log.error("Failed to extract username from token", e);
            return Mono.empty();
        }

        return userDetailsService.findByUsername(username)
                .filter(userDetails -> jwtService.validateToken(token, userDetails))
                .single()
                .map(userDetails -> UsernamePasswordAuthenticationToken.authenticated(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                ))
                .cast(Authentication.class);
    }
}
