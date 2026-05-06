package server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminEntityTest {

    private AdminEntity buildAdmin() {
        AdminEntity admin = new AdminEntity();
        admin.setId(UUID.randomUUID());
        admin.setUsername("adminUser");
        admin.setPassword("encodedPass");
        admin.setRole("ADMIN");
        return admin;
    }

    @Test
    void getAuthorities_shouldReturnRolePrefixed() {
        AdminEntity admin = buildAdmin();
        var authorities = admin.getAuthorities();
        assertEquals(1, authorities.size());
        assertEquals("ROLE_ADMIN", authorities.iterator().next().getAuthority());
    }

    @Test
    void accountStatus_shouldAlwaysBeActive() {
        AdminEntity admin = buildAdmin();
        assertTrue(admin.isAccountNonExpired());
        assertTrue(admin.isAccountNonLocked());
        assertTrue(admin.isCredentialsNonExpired());
        assertTrue(admin.isEnabled());
    }

    @Test
    void dataAnnotation_shouldGenerateEqualsAndToString() {
        AdminEntity a = buildAdmin();
        AdminEntity b = new AdminEntity();
        b.setId(a.getId());
        b.setUsername(a.getUsername());
        b.setPassword(a.getPassword());
        b.setRole(a.getRole());
        assertEquals(a, b);
        assertNotNull(a.toString());
    }
}
