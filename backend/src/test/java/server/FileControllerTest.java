package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import server.repository.FileMetaRepo;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private StorageController storageController;

    @Mock
    private FolderController folderController;

    @Mock
    private FileMetaRepo metadataRepository;

    @InjectMocks
    private FileController fileController;

    // ── downloadMetadata ──────────────────────────────────────────────────────

    @Test
    void downloadMetadata_shouldReturnFileMeta() {
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("report.pdf");

        when(storageController.getFileMetadata(id)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.downloadMetadata(id))
                .expectNextMatches(m -> "report.pdf".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void downloadMetadata_shouldPropagateErrorWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(storageController.getFileMetadata(id))
                .thenReturn(Mono.error(new RuntimeException("File not found")));

        StepVerifier.create(fileController.downloadMetadata(id))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── downloadFile ──────────────────────────────────────────────────────────

    @Test
    void downloadFile_shouldReturnResponseEntityWithResource() {
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("image.png");
        meta.setContentType("image/png");
        meta.setStoragePath("/tmp/image.png");

        when(storageController.getFileMetadata(id)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.downloadFile(id))
                .expectNextMatches(resp ->
                        resp.getStatusCode() == HttpStatus.OK &&
                        "image/png".equals(resp.getHeaders().getContentType().toString()))
                .verifyComplete();
    }

    @Test
    void downloadFile_shouldPropagateErrorWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(storageController.getFileMetadata(id))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "File not found")));

        StepVerifier.create(fileController.downloadFile(id))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── uploadFileToRoot ──────────────────────────────────────────────────────

    @Test
    void uploadFileToRoot_shouldDelegateToStorageController() {
        FilePart filePart = mock(FilePart.class);
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(UUID.randomUUID());
        meta.setFilename("upload.txt");

        when(storageController.saveFile(filePart, null)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.uploadFileToRoot(Mono.just(filePart)))
                .expectNext(meta)
                .verifyComplete();
    }

    // ── uploadFileTo ──────────────────────────────────────────────────────────

    @Test
    void uploadFileTo_shouldDelegateToStorageController() {
        FilePart filePart = mock(FilePart.class);
        UUID folderId = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(UUID.randomUUID());
        meta.setFilename("report.pdf");

        when(storageController.saveFile(filePart, folderId)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.uploadFileTo(Mono.just(filePart), folderId))
                .expectNext(meta)
                .verifyComplete();
    }

    // ── updateFile ────────────────────────────────────────────────────────────

    @Test
    void updateFile_shouldDelegateToStorageController() {
        FilePart filePart = mock(FilePart.class);
        UUID fileId = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(fileId);
        meta.setFilename("updated.txt");

        when(storageController.updateFile(fileId, filePart)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.updateFile(Mono.just(filePart), fileId))
                .expectNext(meta)
                .verifyComplete();
    }

    // ── renameFile ────────────────────────────────────────────────────────────

    @Test
    void renameFile_shouldDelegateToStorageController() {
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("new.txt");

        when(storageController.renameFile(id, "new.txt")).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.renameFile(id, "new.txt"))
                .expectNext(meta)
                .verifyComplete();
    }

    @Test
    void renameFile_shouldPropagateError() {
        UUID id = UUID.randomUUID();
        when(storageController.renameFile(eq(id), anyString()))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "File not found")));

        StepVerifier.create(fileController.renameFile(id, "new.txt"))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── moveFile ──────────────────────────────────────────────────────────────

    @Test
    void moveFile_shouldDelegateToStorageController() {
        UUID id = UUID.randomUUID();
        UUID targetFolderId = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("doc.txt");

        when(storageController.moveFile(id, targetFolderId)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.moveFile(id, targetFolderId.toString()))
                .expectNext(meta)
                .verifyComplete();
    }

    @Test
    void moveFile_shouldPropagateError() {
        UUID id = UUID.randomUUID();
        UUID targetFolderId = UUID.randomUUID();
        when(storageController.moveFile(id, targetFolderId))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "File not found")));

        StepVerifier.create(fileController.moveFile(id, targetFolderId.toString()))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── copyFile ──────────────────────────────────────────────────────────────

    @Test
    void copyFile_shouldDelegateToStorageController() {
        UUID id = UUID.randomUUID();
        UUID targetFolderId = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("copy.txt");

        when(storageController.copyFile(id, targetFolderId)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.copyFile(id, targetFolderId.toString()))
                .expectNext(meta)
                .verifyComplete();
    }

    @Test
    void copyFile_shouldPropagateError() {
        UUID id = UUID.randomUUID();
        UUID targetFolderId = UUID.randomUUID();
        when(storageController.copyFile(id, targetFolderId))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Quota exceeded")));

        StepVerifier.create(fileController.copyFile(id, targetFolderId.toString()))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── restoreFile ───────────────────────────────────────────────────────────

    @Test
    void restoreFile_shouldDelegateToStorageController() {
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("restored.zip");

        when(storageController.restoreFile(id)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.restoreFile(id))
                .expectNext(meta)
                .verifyComplete();
    }

    @Test
    void restoreFile_shouldPropagateError() {
        UUID id = UUID.randomUUID();
        when(storageController.restoreFile(id))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN, "File not in bin")));

        StepVerifier.create(fileController.restoreFile(id))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── deleteFile ────────────────────────────────────────────────────────────

    @Test
    void deleteFile_shouldReturnOkResponse() {
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("todelete.txt");

        when(storageController.getFileMetadata(id)).thenReturn(Mono.just(meta));
        when(storageController.deleteFile(id)).thenReturn(Mono.empty());

        StepVerifier.create(fileController.deleteFile(id))
                .expectNextMatches(resp ->
                        resp.getStatusCode() == HttpStatus.OK &&
                        "Successfully deleted file".equals(resp.getBody()))
                .verifyComplete();
    }

    @Test
    void deleteFile_shouldPropagateErrorWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(storageController.getFileMetadata(id))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "File not found")));

        StepVerifier.create(fileController.deleteFile(id))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── purgeFile ─────────────────────────────────────────────────────────────

    @Test
    void purgeFile_shouldReturnOkResponse() {
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("topurge.txt");

        when(storageController.getFileMetadata(id)).thenReturn(Mono.just(meta));
        when(storageController.purgeFile(id)).thenReturn(Mono.empty());

        // purgeFile uses .then(Mono.just(...)) so it properly emits the ResponseEntity
        StepVerifier.create(fileController.purgeFile(id))
                .expectNextMatches(resp ->
                        resp.getStatusCode() == HttpStatus.OK &&
                        "Successfully purged file".equals(resp.getBody()))
                .verifyComplete();
    }

    @Test
    void purgeFile_shouldPropagateErrorWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(storageController.getFileMetadata(id))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "File not found")));

        StepVerifier.create(fileController.purgeFile(id))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── createFolder ──────────────────────────────────────────────────────────

    @Test
    void createFolder_shouldReturnCreatedFolder() {
        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("images");

        when(folderController.createFolder(anyString(), any())).thenReturn(Mono.just(folder));

        StepVerifier.create(fileController.createFolder(new FileController.CreateFolderRequest("images", null)))
                .expectNextMatches(f -> "images".equals(f.getName()))
                .verifyComplete();
    }

    // ── getRootContent ────────────────────────────────────────────────────────

    @Test
    void getRootContent_shouldReturnFolderContent() {
        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root");
        FolderController.FolderContent content = new FolderController.FolderContent(root, List.of(), List.of());

        when(folderController.getRootContent()).thenReturn(Mono.just(content));

        StepVerifier.create(fileController.getRootContent())
                .expectNextMatches(c -> c.subFolders().isEmpty() && c.files().isEmpty())
                .verifyComplete();
    }

    // ── getRootMeta ───────────────────────────────────────────────────────────

    @Test
    void getRootMeta_shouldReturnFolderMeta() {
        FolderController.FolderMeta meta = new FolderController.FolderMeta(
                UUID.randomUUID(), "root", null, UUID.randomUUID(), null, null);

        when(folderController.getFolderMeta(null)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.getRootMeta())
                .expectNext(meta)
                .verifyComplete();
    }

    // ── getFolderContent ──────────────────────────────────────────────────────

    @Test
    void getFolderContent_shouldReturnContent() {
        UUID folderId = UUID.randomUUID();
        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        FolderController.FolderContent content = new FolderController.FolderContent(folder, List.of(), List.of());

        when(folderController.getFolderContent(folderId)).thenReturn(Mono.just(content));

        StepVerifier.create(fileController.getFolderContent(folderId))
                .expectNext(content)
                .verifyComplete();
    }

    // ── getFolderMeta ─────────────────────────────────────────────────────────

    @Test
    void getFolderMeta_shouldReturnMeta() {
        UUID folderId = UUID.randomUUID();
        FolderController.FolderMeta meta = new FolderController.FolderMeta(
                folderId, "docs", null, UUID.randomUUID(), null, null);

        when(folderController.getFolderMeta(folderId)).thenReturn(Mono.just(meta));

        StepVerifier.create(fileController.getFolderMeta(folderId))
                .expectNext(meta)
                .verifyComplete();
    }

    // ── deleteFolder ──────────────────────────────────────────────────────────

    @Test
    void deleteFolder_shouldReturnOkResponse() {
        UUID folderId = UUID.randomUUID();
        when(folderController.deleteFolder(folderId)).thenReturn(Mono.empty());

        StepVerifier.create(fileController.deleteFolder(folderId))
                .expectNextMatches(resp ->
                        resp.getStatusCode() == HttpStatus.OK &&
                        "Successfully deleted folder".equals(resp.getBody()))
                .verifyComplete();
    }

    @Test
    void deleteFolder_shouldPropagateError() {
        UUID folderId = UUID.randomUUID();
        when(folderController.deleteFolder(folderId))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Folder not found")));

        StepVerifier.create(fileController.deleteFolder(folderId))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── purgeFolder ───────────────────────────────────────────────────────────

    @Test
    void purgeFolder_shouldReturnOkResponse() {
        UUID folderId = UUID.randomUUID();
        when(folderController.purgeFolder(folderId)).thenReturn(Mono.empty());

        StepVerifier.create(fileController.purgeFolder(folderId))
                .expectNextMatches(resp ->
                        resp.getStatusCode() == HttpStatus.OK &&
                        "Successfully purged folder".equals(resp.getBody()))
                .verifyComplete();
    }

    @Test
    void purgeFolder_shouldPropagateError() {
        UUID folderId = UUID.randomUUID();
        when(folderController.purgeFolder(folderId))
                .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Folder not found")));

        StepVerifier.create(fileController.purgeFolder(folderId))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    // ── getBinContent ─────────────────────────────────────────────────────────

    @Test
    void getBinContent_shouldReturnBinFolderContent() {
        FolderEntity bin = new FolderEntity();
        bin.setId(UUID.randomUUID());
        bin.setName("bin");
        FolderController.FolderContent content = new FolderController.FolderContent(bin, List.of(), List.of());

        when(folderController.getBinContent()).thenReturn(Mono.just(content));

        StepVerifier.create(fileController.getBinContent())
                .expectNext(content)
                .verifyComplete();
    }

    // ── getBinMeta ────────────────────────────────────────────────────────────

    @Test
    void getBinMeta_shouldReturnBinMeta() {
        UUID binId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        FolderEntity bin = new FolderEntity();
        bin.setId(binId);
        bin.setName("bin_" + ownerId);
        bin.setParentFolderId(null);
        bin.setOwnerId(ownerId);
        bin.setCreatedAt(null);
        bin.setDeletedAt(null);

        when(folderController.getOrCreateBinFolder()).thenReturn(Mono.just(bin));

        StepVerifier.create(fileController.getBinMeta())
                .expectNextMatches(m -> binId.equals(m.folderId())
                        && ownerId.equals(m.ownerId())
                        && m.name().startsWith("bin_"))
                .verifyComplete();
    }

    // ── restoreFolder ─────────────────────────────────────────────────────────

    @Test
    void restoreFolder_shouldDelegateToFolderController() {
        UUID folderId = UUID.randomUUID();
        FolderEntity restored = new FolderEntity();
        restored.setId(folderId);
        restored.setName("restored");

        when(folderController.restoreFolder(folderId)).thenReturn(Mono.just(restored));

        StepVerifier.create(fileController.restoreFolder(folderId))
                .expectNext(restored)
                .verifyComplete();
    }

    // ── moveFolder ────────────────────────────────────────────────────────────

    @Test
    void moveFolder_shouldDelegateToFolderController() {
        UUID folderId = UUID.randomUUID();
        UUID newParentId = UUID.randomUUID();
        FolderEntity moved = new FolderEntity();
        moved.setId(folderId);
        moved.setName("docs");

        when(folderController.moveFolder(folderId, newParentId)).thenReturn(Mono.just(moved));

        StepVerifier.create(fileController.moveFolder(folderId, newParentId.toString()))
                .expectNext(moved)
                .verifyComplete();
    }

    @Test
    void moveFolder_withNullBody_shouldUseRootAsTarget() {
        UUID folderId = UUID.randomUUID();
        FolderEntity moved = new FolderEntity();
        moved.setId(folderId);
        moved.setName("docs");

        // parseNullableUuid returns null for null/empty/blank/"null"
        when(folderController.moveFolder(folderId, null)).thenReturn(Mono.just(moved));

        StepVerifier.create(fileController.moveFolder(folderId, null))
                .expectNext(moved)
                .verifyComplete();
        StepVerifier.create(fileController.moveFolder(folderId, "  "))
                .expectNext(moved)
                .verifyComplete();
        StepVerifier.create(fileController.moveFolder(folderId, "null"))
                .expectNext(moved)
                .verifyComplete();
    }

    @Test
    void moveFolder_withInvalidUuid_shouldError() {
        // parseNullableUuid throws ResponseStatusException(BAD_REQUEST) on bad UUIDs
        UUID folderId = UUID.randomUUID();
        try {
            fileController.moveFolder(folderId, "not-a-uuid");
            org.junit.jupiter.api.Assertions.fail("Expected ResponseStatusException");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
    }

    // ── copyFolder ────────────────────────────────────────────────────────────

    @Test
    void copyFolder_shouldDelegateToFolderController() {
        UUID folderId = UUID.randomUUID();
        UUID newParentId = UUID.randomUUID();
        FolderEntity copied = new FolderEntity();
        copied.setId(UUID.randomUUID());
        copied.setName("docs");

        when(folderController.copyFolder(folderId, newParentId)).thenReturn(Mono.just(copied));

        StepVerifier.create(fileController.copyFolder(folderId, newParentId.toString()))
                .expectNext(copied)
                .verifyComplete();
    }

    // ── renameFolder ──────────────────────────────────────────────────────────

    @Test
    void renameFolder_shouldDelegateToFolderController() {
        UUID folderId = UUID.randomUUID();
        FolderEntity renamed = new FolderEntity();
        renamed.setId(folderId);
        renamed.setName("newname");

        when(folderController.renameFolder(folderId, "newname")).thenReturn(Mono.just(renamed));

        StepVerifier.create(fileController.renameFolder(folderId, "newname"))
                .expectNextMatches(f -> "newname".equals(f.getName()))
                .verifyComplete();
    }
}
