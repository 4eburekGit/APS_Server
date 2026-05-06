package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

@Configuration
@Slf4j
@EnableWebFluxSecurity
@EnableTransactionManagement
@RequiredArgsConstructor
public class SecurityController {
	@RequiredArgsConstructor
	private class JWTAuthFilter implements WebFilter {

		private static final String AUTH_PROCESSED_ATTR = "JWT_AUTH_PROCESSED";
	    private final JWTHandler jwtService;
	    private final ReactiveUserDetailsService userDetailsService;

	    @Override
	    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
	        if (Boolean.TRUE.equals(exchange.getAttribute(AUTH_PROCESSED_ATTR))) {
	            return chain.filter(exchange);
	        }
	        exchange.getAttributes().put(AUTH_PROCESSED_ATTR, true);

	        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
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
	                .flatMap(userDetails -> {
	                    Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
	                            userDetails, null, userDetails.getAuthorities()
	                    );
	                    return chain.filter(exchange)
	                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).log("CONTEXT WRITTEN");
	                });
	    }
	}

    private final ReactiveUserDetailsService userDetailsService;
    private final JWTHandler jwtHandler;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
        		.csrf(csrf -> csrf.disable())
        		.cors(cors -> cors.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                       .pathMatchers("/auth/**").permitAll()
                       .anyExchange().authenticated()
                )
                .addFilterAt(new JWTAuthFilter(jwtHandler, userDetailsService), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public ReactiveAuthenticationManager authenticationManager() {
    	log.debug("Entering AUTH manager");
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder());
        log.debug("Exiting AUTH manager");
        return manager;
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
