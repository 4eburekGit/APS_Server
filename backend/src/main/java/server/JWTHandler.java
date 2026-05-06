package server;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JWTHandler {

    /**
     * HMAC-SHA256 signing key. MUST be supplied via the {@code JWT_SECRET}
     * environment variable (or {@code jwt.secret} property in tests). No
     * default is provided in production: a committed default would let
     * anyone with read access to the repo forge tokens. The application
     * fails fast on boot if the key is missing or shorter than 32 bytes
     * (the minimum HS256 requires).
     */
    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.expiration:3600000}")
    private Long expiration; // millis == 1 hour by default

    private SecretKey getSigningKey() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not configured. Set the JWT_SECRET environment variable " +
                    "to a random string of at least 32 bytes (e.g. `openssl rand -base64 48`).");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret is too short (" + bytes.length + " bytes); HS256 requires at least 32. " +
                    "Generate a fresh value with `openssl rand -base64 48` and set JWT_SECRET.");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String generateToken(UserDetails userDetails) {
        // Stamp the principal's role into the JWT. Without it the frontend
        // can't tell an admin from a user purely from the token, which let
        // the admin slip into routes guarded by <UserOnly> and bounce back
        // out of /admin (Admin.jsx checks user.role).
        Map<String, Object> claims = new HashMap<>();
        String role = userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .findFirst()
                .orElse("ROLE_USER");
        claims.put("role", role);
        return createToken(claims, userDetails.getUsername());
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
}