package server;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileMetaEntityTest {

    private FileMetaEntity buildFileMeta() {
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(UUID.randomUUID());
        meta.setFilename("test.pdf");
        meta.setContentType("application/pdf");
        meta.setSize(1024L);
        meta.setStoragePath("/uploads/test.pdf");
        meta.setUploadedAt(Instant.now());
        meta.setOwnerId(UUID.randomUUID());
        meta.setFolderId(UUID.randomUUID());
        meta.setDeletedAt(null);
        meta.setUpdatedAt(null);
        return meta;
    }

    @Test
    void gettersAndSetters_shouldWorkCorrectly() {
        FileMetaEntity meta = buildFileMeta();
        assertNotNull(meta.getId());
        assertEquals("test.pdf", meta.getFilename());
        assertEquals("application/pdf", meta.getContentType());
        assertEquals(1024L, meta.getSize());
        assertEquals("/uploads/test.pdf", meta.getStoragePath());
        assertNotNull(meta.getUploadedAt());
        assertNotNull(meta.getOwnerId());
        assertNotNull(meta.getFolderId());
        assertNull(meta.getDeletedAt());
        assertNull(meta.getUpdatedAt());
    }

    @Test
    void dataAnnotation_shouldGenerateEqualsAndToString() {
        FileMetaEntity a = buildFileMeta();
        FileMetaEntity b = new FileMetaEntity();
        b.setId(a.getId());
        b.setFilename(a.getFilename());
        b.setContentType(a.getContentType());
        b.setSize(a.getSize());
        b.setStoragePath(a.getStoragePath());
        b.setUploadedAt(a.getUploadedAt());
        b.setOwnerId(a.getOwnerId());
        b.setFolderId(a.getFolderId());
        b.setDeletedAt(a.getDeletedAt());
        b.setUpdatedAt(a.getUpdatedAt());
        assertEquals(a, b);
        assertNotNull(a.toString());
    }

    @Test
    void deletedAt_canBeSet() {
        FileMetaEntity meta = buildFileMeta();
        Instant now = Instant.now();
        meta.setDeletedAt(now);
        assertEquals(now, meta.getDeletedAt());
    }
}
