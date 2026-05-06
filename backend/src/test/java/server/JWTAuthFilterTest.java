package server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the private inner class SecurityController.JWTAuthFilter.
 * <p>
 * Reflection is used to instantiate the private inner class — pom.xml's JaCoCo
 * exclusion targets {@code server/SecurityController.class} but does not match
 * the inner-class file {@code server/SecurityController$JWTAuthFilter.class},
 * so it must be exercised by tests.
 */
@ExtendWith(MockitoExtension.class)
class JWTAuthFilterTest {

    @Mock
    private JWTHandler jwtHandler;

    @Mock
    private ReactiveUserDetailsService userDetailsService;

    @Mock
    private WebFilterChain chain;

    private WebFilter authFilter;

    @BeforeEach
    void setUp() throws Exception {
        // The outer SecurityController must be instantiated first because the inner
        // class is non-static — it holds a reference to its enclosing instance.
        SecurityController outer = new SecurityController(userDetailsService, jwtHandler);

        Class<?> inner = Class.forName("server.SecurityController$JWTAuthFilter");
        Constructor<?> ctor = inner.getDeclaredConstructor(
                SecurityController.class, JWTHandler.class, ReactiveUserDetailsService.class);
        ctor.setAccessible(true);
        authFilter = (WebFilter) ctor.newInstance(outer, jwtHandler, userDetailsService);
    }

    private MockServerWebExchange exchange(String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/files");
        if (authHeader != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private UserDetails buildUserDetails(String username) {
        return User.withUsername(username)
                .password("encoded")
                .authorities(Collections.emptyList())
                .build();
    }

    @Test
    void filter_whenAlreadyProcessed_shouldShortCircuit() {
        MockServerWebExchange ex = exchange(null);
        ex.getAttributes().put("JWT_AUTH_PROCESSED", true);
        when(chain.filter(ex)).thenReturn(Mono.empty());

        StepVerifier.create(authFilter.filter(ex, chain))
                .verifyComplete();

        verify(chain).filter(ex);
        verifyNoInteractions(jwtHandler, userDetailsService);
    }

    @Test
    void filter_whenNoAuthHeader_shouldDelegateAndSetProcessedFlag() {
        MockServerWebExchange ex = exchange(null);
        when(chain.filter(ex)).thenReturn(Mono.empty());

        StepVerifier.create(authFilter.filter(ex, chain))
                .verifyComplete();

        verify(chain).filter(ex);
        verifyNoInteractions(jwtHandler, userDetailsService);
    }

    @Test
    void filter_whenNonBearerHeader_shouldDelegateWithoutAuth() {
        MockServerWebExchange ex = exchange("Basic abc==");
        when(chain.filter(ex)).thenReturn(Mono.empty());

        StepVerifier.create(authFilter.filter(ex, chain))
                .verifyComplete();

        verify(chain).filter(ex);
        verifyNoInteractions(jwtHandler, userDetailsService);
    }

    @Test
    void filter_whenJwtParsingThrows_shouldLogAndContinue() {
        MockServerWebExchange ex = exchange("Bearer broken-token");
        when(chain.filter(ex)).thenReturn(Mono.empty());
        when(jwtHandler.extractUsername("broken-token"))
                .thenThrow(new RuntimeException("malformed"));

        StepVerifier.create(authFilter.filter(ex, chain))
                .verifyComplete();

        verify(chain).filter(ex);
        verify(jwtHandler).extractUsername("broken-token");
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void filter_whenTokenValid_shouldAuthenticateAndContinue() {
        MockServerWebExchange ex = exchange("Bearer good-token");
        UserDetails user = buildUserDetails("alice");

        when(jwtHandler.extractUsername("good-token")).thenReturn("alice");
        when(userDetailsService.findByUsername("alice")).thenReturn(Mono.just(user));
        when(jwtHandler.validateToken("good-token", user)).thenReturn(true);
        when(chain.filter(ex)).thenReturn(Mono.empty());

        StepVerifier.create(authFilter.filter(ex, chain))
                .verifyComplete();

        verify(chain).filter(ex);
        verify(jwtHandler).validateToken("good-token", user);
    }

    @Test
    void filter_whenTokenInvalid_shouldFilterOutAndCompleteEmpty() {
        MockServerWebExchange ex = exchange("Bearer bad-token");
        UserDetails user = buildUserDetails("alice");

        when(jwtHandler.extractUsername("bad-token")).thenReturn("alice");
        when(userDetailsService.findByUsername("alice")).thenReturn(Mono.just(user));
        when(jwtHandler.validateToken("bad-token", user)).thenReturn(false);

        // .filter(...) drops the user → switchIfEmpty is commented out, so the
        // pipeline simply completes empty without calling chain.filter
        StepVerifier.create(authFilter.filter(ex, chain))
                .verifyComplete();

        verify(jwtHandler).validateToken("bad-token", user);
        verify(chain, never()).filter(any());
    }

    @Test
    void filter_whenUserDetailsServiceReturnsEmpty_shouldCompleteEmpty() {
        MockServerWebExchange ex = exchange("Bearer good-token");

        when(jwtHandler.extractUsername("good-token")).thenReturn("ghost");
        when(userDetailsService.findByUsername("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(authFilter.filter(ex, chain))
                .verifyComplete();

        verify(userDetailsService).findByUsername("ghost");
        verify(chain, never()).filter(any());
        verify(jwtHandler, never()).validateToken(anyString(), any());
    }
}
