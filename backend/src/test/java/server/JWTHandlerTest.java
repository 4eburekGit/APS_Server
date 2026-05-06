package server;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;

class JWTHandlerTest {

    private JWTHandler jwtHandler;

    private static final String SECRET = "TestSecretKeyThatIsLongEnoughForHmacSha256Algorithm1234";
    private static final long EXPIRATION = 3_600_000L;

    @BeforeEach
    void setUp() {
        jwtHandler = new JWTHandler();
        ReflectionTestUtils.setField(jwtHandler, "secret", SECRET);
        ReflectionTestUtils.setField(jwtHandler, "expiration", EXPIRATION);
    }

    @Test
    void generateToken_shouldReturnNonEmptyToken() {
        UserDetails user = buildUser("alice");
        String token = jwtHandler.generateToken(user);
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void extractUsername_shouldReturnCorrectUsername() {
        UserDetails user = buildUser("alice");
        String token = jwtHandler.generateToken(user);
        assertEquals("alice", jwtHandler.extractUsername(token));
    }

    @Test
    void validateToken_shouldReturnTrueForValidToken() {
        UserDetails user = buildUser("alice");
        String token = jwtHandler.generateToken(user);
        assertTrue(jwtHandler.validateToken(token, user));
    }

    @Test
    void validateToken_shouldReturnFalseForDifferentUser() {
        UserDetails alice = buildUser("alice");
        UserDetails bob = buildUser("bob");
        String tokenForAlice = jwtHandler.generateToken(alice);
        assertFalse(jwtHandler.validateToken(tokenForAlice, bob));
    }

    @Test
    void validateToken_shouldThrowForExpiredToken() {
        jwtHandler = new JWTHandler();
        ReflectionTestUtils.setField(jwtHandler, "secret", SECRET);
        ReflectionTestUtils.setField(jwtHandler, "expiration", -1L); // already expired
        UserDetails user = buildUser("alice");
        String token = jwtHandler.generateToken(user);
        // JJWT throws ExpiredJwtException when parsing an expired token
        assertThrows(ExpiredJwtException.class, () -> jwtHandler.validateToken(token, user));
    }

    @Test
    void validateToken_shouldReturnFalseWhenUsernameMatchesButTokenExpired() {
        // Cover the branch (username matches) AND (isTokenExpired == true).
        // Real JJWT throws ExpiredJwtException on parse, so we spy on
        // extractExpiration to simulate an expired token without throwing.
        UserDetails user = buildUser("alice");
        String token = jwtHandler.generateToken(user);

        JWTHandler spy = Mockito.spy(jwtHandler);
        Mockito.doReturn(new Date(0)).when(spy).extractExpiration(anyString());

        assertFalse(spy.validateToken(token, user));
    }

    @Test
    void generateToken_withRoleAuthority_shouldExtractRoleFromAuthorities() {
        // Covers the .map(a -> a.getAuthority()).findFirst() success branch
        // in generateToken — i.e. when authorities is non-empty so we use
        // the principal's actual role instead of falling back to ROLE_USER.
        UserEntity user = new UserEntity();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("alice");
        user.setPassword("password");
        user.setRole("USER");

        String token = jwtHandler.generateToken(user);
        assertNotNull(token);
        assertEquals("alice", jwtHandler.extractUsername(token));
        // Decode the role claim to verify the success branch ran
        String role = jwtHandler.extractClaim(token, c -> c.get("role", String.class));
        // UserEntity.getAuthorities() prefixes the role with "ROLE_"
        assertEquals("ROLE_USER", role);
    }

    // ── Fail-fast guards on jwt.secret ────────────────────────────────────────
    //
    // Previously the secret had a hard-coded fallback in application.yml, so a
    // misconfigured deployment shipped a known-good signing key — anyone with
    // the repo could forge tokens. Now JWTHandler#getSigningKey throws on
    // missing or short secrets so misconfig is loud at first use.

    @Test
    void getSigningKey_whenSecretMissing_throwsIllegalState() {
        JWTHandler bare = new JWTHandler();
        ReflectionTestUtils.setField(bare, "secret", "");
        ReflectionTestUtils.setField(bare, "expiration", EXPIRATION);
        // Trip the guard via any token-issuing operation.
        UserDetails user = buildUser("alice");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bare.generateToken(user));
        assertTrue(ex.getMessage().toLowerCase().contains("jwt.secret"),
                "expected message to mention jwt.secret, got: " + ex.getMessage());
    }

    @Test
    void getSigningKey_whenSecretNull_throwsIllegalState() {
        JWTHandler bare = new JWTHandler();
        ReflectionTestUtils.setField(bare, "secret", null);
        ReflectionTestUtils.setField(bare, "expiration", EXPIRATION);
        UserDetails user = buildUser("alice");
        assertThrows(IllegalStateException.class, () -> bare.generateToken(user));
    }

    @Test
    void getSigningKey_whenSecretTooShort_throwsIllegalState() {
        // HS256 requires >= 32 bytes (256 bits). A 16-char ASCII secret = 16 bytes.
        JWTHandler bare = new JWTHandler();
        ReflectionTestUtils.setField(bare, "secret", "tooShortSecret16");
        ReflectionTestUtils.setField(bare, "expiration", EXPIRATION);
        UserDetails user = buildUser("alice");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bare.generateToken(user));
        assertTrue(ex.getMessage().toLowerCase().contains("too short")
                        || ex.getMessage().toLowerCase().contains("32"),
                "expected length error, got: " + ex.getMessage());
    }

    private UserDetails buildUser(String username) {
        return User.withUsername(username)
                .password("password")
                .authorities(Collections.emptyList())
                .build();
    }
}
