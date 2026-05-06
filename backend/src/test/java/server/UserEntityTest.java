package server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserEntityTest {

    private UserEntity buildUser(String role) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPassword("encoded");
        user.setRole(role);
        return user;
    }

    @Test
    void getAuthorities_shouldReturnRolePrefixed() {
        UserEntity user = buildUser("USER");
        var authorities = user.getAuthorities();
        assertEquals(1, authorities.size());
        assertEquals("ROLE_USER", authorities.iterator().next().getAuthority());
    }

    @Test
    void accountStatus_shouldAlwaysBeActive() {
        UserEntity user = buildUser("USER");
        assertTrue(user.isAccountNonExpired());
        assertTrue(user.isAccountNonLocked());
        assertTrue(user.isCredentialsNonExpired());
        assertTrue(user.isEnabled());
    }

    @Test
    void dataAnnotation_shouldGenerateEqualsAndToString() {
        UserEntity a = buildUser("USER");
        UserEntity b = new UserEntity();
        b.setId(a.getId());
        b.setUsername(a.getUsername());
        b.setPassword(a.getPassword());
        b.setRole(a.getRole());
        assertEquals(a, b);
        assertNotNull(a.toString());
    }
}
