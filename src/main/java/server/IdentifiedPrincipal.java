package server;

import org.springframework.security.core.userdetails.UserDetails;

import java.util.UUID;

/**
 * Common contract for the two principal types used in this app — {@link UserEntity}
 * (regular users) and {@link AdminEntity} (admins). Both authenticate through
 * {@link UserDataService}, which means the principal placed into the security
 * context can be either type. Controllers used to cast directly to
 * {@code UserEntity}, which blew up with a {@link ClassCastException} as soon
 * as an admin made any request.
 *
 * <p>By having both entities implement this interface, handlers can simply
 * cast to {@code IdentifiedPrincipal} and read whatever they need —
 * {@link #getId()} for storage scoping, {@link #getUsername()} for logging,
 * authorities for role checks via Spring Security.
 */
public interface IdentifiedPrincipal extends UserDetails {
    UUID getId();
}
