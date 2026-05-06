package server;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FolderEntityTest {

    private FolderEntity buildFolder() {
        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("documents");
        folder.setParentFolderId(UUID.randomUUID());
        folder.setOwnerId(UUID.randomUUID());
        folder.setCreatedAt(Instant.now());
        folder.setDeletedAt(null);
        return folder;
    }

    @Test
    void gettersAndSetters_shouldWorkCorrectly() {
        FolderEntity folder = buildFolder();
        assertNotNull(folder.getId());
        assertEquals("documents", folder.getName());
        assertNotNull(folder.getParentFolderId());
        assertNotNull(folder.getOwnerId());
        assertNotNull(folder.getCreatedAt());
        assertNull(folder.getDeletedAt());
    }

    @Test
    void dataAnnotation_shouldGenerateEqualsAndToString() {
        FolderEntity a = buildFolder();
        FolderEntity b = new FolderEntity();
        b.setId(a.getId());
        b.setName(a.getName());
        b.setParentFolderId(a.getParentFolderId());
        b.setOwnerId(a.getOwnerId());
        b.setCreatedAt(a.getCreatedAt());
        b.setDeletedAt(a.getDeletedAt());
        assertEquals(a, b);
        assertNotNull(a.toString());
    }

    @Test
    void deletedAt_canBeSet() {
        FolderEntity folder = buildFolder();
        Instant now = Instant.now();
        folder.setDeletedAt(now);
        assertEquals(now, folder.getDeletedAt());
    }
}
