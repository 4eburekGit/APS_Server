package server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import server.repository.FileMetaRepo;
import server.repository.FolderRepo;

import io.r2dbc.spi.Readable;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageControllerTest {

    @Mock
    private FileMetaRepo fileMetaRepo;

    @Mock
    private FolderRepo folderRepo;

    @Mock
    private DatabaseClient databaseClient;

    @InjectMocks
    private StorageController storageController;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(storageController, "storagePath", tempDir.toString());
        ReflectionTestUtils.setField(storageController, "storageQuota", 10737418240L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UserEntity buildUser() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPassword("encoded");
        user.setRole("USER");
        return user;
    }

    private <T> Mono<T> withUser(Mono<T> mono, UserEntity user) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        return mono.contextWrite(
                ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl(auth)))
        );
    }

    /**
     * Mocks the chain: databaseClient.sql(anything).bind(...).filter(...).fetch().first()
     * used by saveFile (INSERT) and also silences the quota SELECT side-chain.
     * Uses lenient() so the quota SELECT sql call doesn't trigger strict-stubbing errors.
     */
    @SuppressWarnings("unchecked")
    private void mockDbInsertChain(Mono<Map<String, Object>> firstResult) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);
        RowsFetchSpec<Long> quotaRowsSpec = mock(RowsFetchSpec.class);

        // Use lenient to avoid strict-stubbing errors when quota SELECT fires
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.fetch()).thenReturn(fetchSpec);
        lenient().when(fetchSpec.first()).thenReturn(firstResult);
        // Stub the quota SELECT map(...).first() path AND invoke the lambda body for coverage
        lenient().when(spec.map(any(Function.class))).thenAnswer(inv -> {
            Function<Readable, Long> fn = inv.getArgument(0);
            Readable readable = mock(Readable.class);
            lenient().when(readable.get(0, Long.class)).thenReturn(0L);
            fn.apply(readable);
            return quotaRowsSpec;
        });
        lenient().when(quotaRowsSpec.first()).thenReturn(Mono.just(0L));
    }

    /**
     * Mocks the chain: databaseClient.sql(select).bind(...).map(row -> {...}).one()
     * used by deleteFile / purgeFile.
     * <p>
     * If a non-empty result is provided, the mock INVOKES the mapping function
     * with a fake Readable row, so the lambda body (lines 305-314 / 359-368) is
     * executed and counted by JaCoCo.
     */
    @SuppressWarnings("unchecked")
    private void mockDbSelectOneChain(Mono<FileMetaEntity> expectedResult) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<FileMetaEntity> rowsSpec = mock(RowsFetchSpec.class);

        when(databaseClient.sql(contains("SELECT"))).thenReturn(spec);
        when(spec.map(any(Function.class))).thenAnswer(invocation -> {
            Function<Readable, FileMetaEntity> fn = invocation.getArgument(0);
            // invoke the function with a mock Readable to cover the row-mapping code
            expectedResult.subscribe(meta -> {
                if (meta != null) {
                    Readable row = buildMockRow(meta);
                    fn.apply(row); // exercises lines 305-314 / 359-368
                }
            });
            return rowsSpec;
        });
        when(rowsSpec.one()).thenReturn(expectedResult);
    }

    /** Builds a mock Readable that returns field values from the given FileMetaEntity */
    private Readable buildMockRow(FileMetaEntity meta) {
        Readable row = mock(Readable.class);
        lenient().when(row.get("id", UUID.class)).thenReturn(meta.getId());
        lenient().when(row.get("filename", String.class)).thenReturn(meta.getFilename());
        lenient().when(row.get("content_type", String.class)).thenReturn(
                meta.getContentType() != null ? meta.getContentType() : "text/plain");
        lenient().when(row.get("size", Long.class)).thenReturn(
                meta.getSize() != null ? meta.getSize() : 0L);
        lenient().when(row.get("storage_path", String.class)).thenReturn(meta.getStoragePath());
        lenient().when(row.get("uploaded_at", Instant.class)).thenReturn(meta.getUploadedAt());
        lenient().when(row.get("owner_id", UUID.class)).thenReturn(meta.getOwnerId());
        lenient().when(row.get("folder_id", UUID.class)).thenReturn(meta.getFolderId());
        lenient().when(row.get("deleted_at", Instant.class)).thenReturn(meta.getDeletedAt());
        return row;
    }

    /**
     * Mocks the chain: databaseClient.sql(update).bind(...).fetch().rowsUpdated()
     */
    @SuppressWarnings("unchecked")
    private void mockDbUpdateChain() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

        when(databaseClient.sql(contains("UPDATE"))).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));
    }

    /**
     * Mocks the chain: databaseClient.sql(delete).bind(...).fetch().rowsUpdated()
     */
    @SuppressWarnings("unchecked")
    private void mockDbDeleteChain() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);

        when(databaseClient.sql(contains("DELETE"))).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));
    }

    private FilePart buildMockFilePart(String filename, String contentType) {
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));

        when(filePart.filename()).thenReturn(filename);
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "test-content");
            return Mono.empty();
        });
        // stub content() for the quota-check code path (lenient: may not be called if subscribe is fire-and-forget)
        var buf = new DefaultDataBufferFactory().wrap("test-content".getBytes());
        lenient().when(filePart.content()).thenReturn(Flux.just(buf));
        return filePart;
    }

    // ── getFileMetadata ───────────────────────────────────────────────────────

    @Test
    void getFileMetadata_shouldReturnEntity() {
        UserEntity user = buildUser();
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename("doc.pdf");

        when(fileMetaRepo.findByIdAndOwnerId(id, user.getId())).thenReturn(Mono.just(meta));

        StepVerifier.create(withUser(storageController.getFileMetadata(id), user))
                .expectNext(meta)
                .verifyComplete();
    }

    @Test
    void getFileMetadata_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID id = UUID.randomUUID();
        when(fileMetaRepo.findByIdAndOwnerId(id, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.getFileMetadata(id), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── getFilePath ───────────────────────────────────────────────────────────

    @Test
    void getFilePath_shouldReturnPathFromMeta() {
        UserEntity user = buildUser();
        UUID id = UUID.randomUUID();
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setStoragePath("/uploads/file.txt");

        when(fileMetaRepo.findByIdAndOwnerId(id, user.getId())).thenReturn(Mono.just(meta));

        StepVerifier.create(withUser(storageController.getFilePath(id), user))
                .expectNextMatches(p -> p.toString().equals("/uploads/file.txt"))
                .verifyComplete();
    }

    // ── saveFile — root folder (folderId == null) ─────────────────────────────

    @Test
    void saveFile_toRootFolder_shouldSaveAndReturnMeta() {
        UserEntity user = buildUser();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        FilePart filePart = buildMockFilePart("hello.txt", "text/plain");

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        mockDbInsertChain(Mono.just(Map.of("id", rootFolder.getId())));

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .expectNextMatches(m -> "hello.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    // ── saveFile — specific folder (folderId != null) ─────────────────────────

    @Test
    void saveFile_toSpecificFolder_shouldSaveAndReturnMeta() {
        UserEntity user = buildUser();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FilePart filePart = buildMockFilePart("report.pdf", "application/pdf");

        when(folderRepo.findByIdAndOwnerId(folder.getId(), user.getId()))
                .thenReturn(Mono.just(folder));
        mockDbInsertChain(Mono.just(Map.of("id", folder.getId())));

        StepVerifier.create(withUser(storageController.saveFile(filePart, folder.getId()), user))
                .expectNextMatches(m -> "report.pdf".equals(m.getFilename()))
                .verifyComplete();
    }

    // ── saveFile — nested folder (exercises getFolderPathSegments parent walk) ──

    @Test
    void saveFile_toNestedFolder_shouldBuildNestedPath() {
        UserEntity user = buildUser();

        UUID rootFolderId = UUID.randomUUID();
        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(rootFolderId);
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        FolderEntity subFolder = new FolderEntity();
        subFolder.setId(UUID.randomUUID());
        subFolder.setName("nested");
        subFolder.setOwnerId(user.getId());
        subFolder.setParentFolderId(rootFolderId);

        FilePart filePart = buildMockFilePart("data.csv", "text/csv");

        when(folderRepo.findByIdAndOwnerId(subFolder.getId(), user.getId()))
                .thenReturn(Mono.just(subFolder));
        when(folderRepo.findById(rootFolderId)).thenReturn(Mono.just(rootFolder));
        mockDbInsertChain(Mono.just(Map.of("id", subFolder.getId())));

        StepVerifier.create(withUser(storageController.saveFile(filePart, subFolder.getId()), user))
                .expectNextMatches(m -> "data.csv".equals(m.getFilename()))
                .verifyComplete();
    }

    // ── saveFile — root folder not found ─────────────────────────────────────

    @Test
    void saveFile_whenRootFolderNotFound_shouldError() {
        UserEntity user = buildUser();
        FilePart filePart = mock(FilePart.class);

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── saveFile — specific folder not found ─────────────────────────────────

    @Test
    void saveFile_whenFolderNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        FilePart filePart = mock(FilePart.class);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.saveFile(filePart, folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── saveFile — doOnSuccess(null) branch (DB returns empty) ───────────────

    @Test
    void saveFile_whenDbReturnsEmpty_shouldCompleteEmpty() {
        UserEntity user = buildUser();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FilePart filePart = buildMockFilePart("empty.txt", "text/plain");

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));
        mockDbInsertChain(Mono.empty());

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .verifyComplete();
    }

    // ── saveFile — quota exceeded (upfront check, lines 98-99) ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void saveFile_whenQuotaExceededUpfront_shouldError() {
        UserEntity user = buildUser();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        // FilePart with Content-Length set to 1 byte
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        headers.setContentLength(1L); // declaredSize = 1
        lenient().when(filePart.filename()).thenReturn("big.txt");
        lenient().when(filePart.headers()).thenReturn(headers);

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));

        // Quota check: used = storageQuota (10GB), declared = 1 → used + declared > storageQuota
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<Long> quotaRowsSpec = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.map(any(Function.class))).thenReturn(quotaRowsSpec);
        lenient().when(quotaRowsSpec.first()).thenReturn(Mono.just(10737418240L)); // used = quota

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    // ── saveFile — transfer fails (doOnError on line 104) ────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void saveFile_whenTransferFails_shouldPropagateError() {
        UserEntity user = buildUser();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        when(filePart.filename()).thenReturn("fail.txt");
        when(filePart.headers()).thenReturn(headers);
        // Make transferTo fail
        when(filePart.transferTo(any(Path.class))).thenReturn(
                Mono.error(new RuntimeException("Transfer failed")));

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<Long> quotaRowsSpec = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.map(any(Function.class))).thenReturn(quotaRowsSpec);
        lenient().when(quotaRowsSpec.first()).thenReturn(Mono.just(0L));

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── buildPhysicalPath(userId, null) ───────────────────────────────────────

    @Test
    void buildPhysicalPath_withNullFolder_shouldReturnUserRootPath() throws Exception {
        UUID userId = UUID.randomUUID();
        Method method = StorageController.class.getDeclaredMethod(
                "buildPhysicalPath", UUID.class, FolderEntity.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Path> result = (Mono<Path>) method.invoke(storageController, userId, null);

        StepVerifier.create(result)
                .expectNextMatches(p -> p.equals(tempDir.resolve(userId.toString())))
                .verifyComplete();
    }

    // ── deleteFile — file not found ───────────────────────────────────────────

    @Test
    void deleteFile_whenFileNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        mockDbSelectOneChain(Mono.empty());

        StepVerifier.create(withUser(storageController.deleteFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── deleteFile — already deleted (deletedAt != null) ─────────────────────

    @Test
    void deleteFile_whenAlreadyDeleted_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "gone.txt", Instant.now());

        mockDbSelectOneChain(Mono.just(meta));

        StepVerifier.create(withUser(storageController.deleteFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    // ── deleteFile — success ──────────────────────────────────────────────────

    @Test
    void deleteFile_shouldMoveFileToTrash() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        // Create actual file structure
        Path userDir = tempDir.resolve(user.getId().toString());
        Path binDir = userDir.resolve("bin_" + user.getId());
        Files.createDirectories(userDir);
        Files.createDirectories(binDir);
        Path filePath = userDir.resolve("doc.txt");
        Files.writeString(filePath, "content");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "doc.txt", null);
        meta.setStoragePath(filePath.toString());

        // The new deleteFile resolves the user's bin folder so the metadata
        // row's folder_id can be repointed at it (otherwise the file would
        // stay attached to its original folder and never appear in the trash
        // listing). Stub the bin lookup so the flow can complete.
        FolderEntity bin = new FolderEntity();
        bin.setId(UUID.randomUUID());
        bin.setName("bin_" + user.getId());
        bin.setOwnerId(user.getId());
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));

        mockDbSelectOneChain(Mono.just(meta));
        mockDbUpdateChain();

        StepVerifier.create(withUser(storageController.deleteFile(fileId), user))
                .verifyComplete();
    }

    @Test
    void deleteFile_whenBinFolderMissing_shouldError() {
        // If the bin row was never created (corrupted state), deleteFile
        // refuses to silently accept the orphan and bubbles a 404 up to the
        // caller. Drives the switchIfEmpty branch in the new flow.
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "doc.txt", null);
        meta.setStoragePath(tempDir.resolve("doc.txt").toString());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.empty());

        mockDbSelectOneChain(Mono.just(meta));

        StepVerifier.create(withUser(storageController.deleteFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── purgeFile — file not found ────────────────────────────────────────────

    @Test
    void purgeFile_whenFileNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        mockDbSelectOneChain(Mono.empty());

        StepVerifier.create(withUser(storageController.purgeFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── purgeFile — not yet deleted (deletedAt == null) ───────────────────────

    @Test
    void purgeFile_whenNotDeleted_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "active.txt", null); // deletedAt = null

        mockDbSelectOneChain(Mono.just(meta));

        StepVerifier.create(withUser(storageController.purgeFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    // ── purgeFile — success ───────────────────────────────────────────────────

    @Test
    void purgeFile_shouldDeleteFileFromDisk() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        Path binDir = tempDir.resolve(user.getId().toString()).resolve("bin_" + user.getId());
        Files.createDirectories(binDir);
        Path filePath = binDir.resolve("trashed.txt");
        Files.writeString(filePath, "trash");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "trashed.txt", Instant.now());
        meta.setStoragePath(filePath.toString());

        mockDbSelectOneChain(Mono.just(meta));
        mockDbDeleteChain();

        StepVerifier.create(withUser(storageController.purgeFile(fileId), user))
                .verifyComplete();
    }

    // ── updateFile ────────────────────────────────────────────────────────────

    /**
     * Mocks the two DB calls inside updateFile:
     *   1. SELECT COALESCE(sum(size), 0) ... → quota check (returns usedBytes)
     *   2. UPDATE metadata SET ...           → rowsUpdated()
     */
    @SuppressWarnings("unchecked")
    private void mockDbUpdateFileChain() {
        // Shared spec for both SQL strings (lenient, caught by anyString())
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);

        // quota SELECT: spec.map(row -> ...).first().defaultIfEmpty(0L)
        // Invoke the lambda body for line-coverage of `row.get(0, Long.class)`.
        RowsFetchSpec<Long> quotaRows = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(Function.class))).thenAnswer(inv -> {
            Function<Readable, Long> fn = inv.getArgument(0);
            Readable readable = mock(Readable.class);
            lenient().when(readable.get(0, Long.class)).thenReturn(0L);
            fn.apply(readable);
            return quotaRows;
        });
        lenient().when(quotaRows.first()).thenReturn(Mono.just(0L));

        // UPDATE metadata: spec.fetch().rowsUpdated()
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);
        lenient().when(spec.fetch()).thenReturn(fetchSpec);
        lenient().when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));
    }

    @Test
    void updateFile_toRootFolder_shouldUpdateAndReturnMeta() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(null); // root folder path

        FilePart filePart = buildMockFilePart("new.txt", "text/plain");

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        mockDbUpdateFileChain();

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectNextMatches(m -> "new.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void updateFile_toSpecificFolder_shouldUpdateAndReturnMeta() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.pdf", null);
        existingFile.setFolderId(folderId);

        FilePart filePart = buildMockFilePart("new.pdf", "application/pdf");

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        mockDbUpdateFileChain();

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectNextMatches(m -> "new.pdf".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void updateFile_whenFileNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        FilePart filePart = mock(FilePart.class);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void updateFile_whenFolderNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        FilePart filePart = mock(FilePart.class);
        // Sanitizer rejects empty filenames before we reach the folder lookup,
        // so provide a valid name to exercise the not-found path.
        when(filePart.filename()).thenReturn("new.txt");

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(folderId);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── updateFile — transfer fails (doOnError on line 212) ─────────────────

    @Test
    @SuppressWarnings("unchecked")
    void updateFile_whenTransferFails_shouldPropagateError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(null);

        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        when(filePart.filename()).thenReturn("new.txt");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenReturn(
                Mono.error(new RuntimeException("Transfer failed")));

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        RowsFetchSpec<Long> quotaRows = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(Function.class))).thenReturn(quotaRows);
        lenient().when(quotaRows.first()).thenReturn(Mono.just(0L));

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── updateFile — quota exceeded upfront (lines 206-207) ─────────────────

    @Test
    @SuppressWarnings("unchecked")
    void updateFile_whenQuotaExceededUpfront_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(null);

        // FilePart with Content-Length = 1 byte
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        headers.setContentLength(1L);
        lenient().when(filePart.filename()).thenReturn("new.txt");
        lenient().when(filePart.headers()).thenReturn(headers);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));

        // Quota SELECT returns storageQuota (10GB) → used + 1 > quota
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        RowsFetchSpec<Long> quotaRows = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(Function.class))).thenReturn(quotaRows);
        lenient().when(quotaRows.first()).thenReturn(Mono.just(10737418240L)); // used = quota

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    // ── updateFile — quota exceeded after transfer (lines 217-222) ────────────

    @Test
    @SuppressWarnings("unchecked")
    void updateFile_whenQuotaExceededAfterTransfer_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(null);

        // Use a very small quota (< 12 bytes = "test-content".length)
        ReflectionTestUtils.setField(storageController, "storageQuota", 5L);

        // FilePart with NO Content-Length (so upfront check is skipped)
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        lenient().when(filePart.filename()).thenReturn("new.txt");
        lenient().when(filePart.headers()).thenReturn(headers);
        lenient().when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "test-content"); // 12 bytes > quota 5
            return Mono.empty();
        });

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));

        // Quota SELECT returns 0 → upfront: -1 > 0 is false → skipped; post: 0 + 12 > 5 → error
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        RowsFetchSpec<Long> quotaRows = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(Function.class))).thenReturn(quotaRows);
        lenient().when(quotaRows.first()).thenReturn(Mono.just(0L));

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();

        // Restore quota
        ReflectionTestUtils.setField(storageController, "storageQuota", 10737418240L);
    }

    // ── saveFile — quota exceeded after transfer (lines 109-114) ─────────────

    @Test
    void saveFile_whenQuotaExceededAfterTransfer_shouldError() {
        UserEntity user = buildUser();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        // Use a very small quota (< 12 bytes = "test-content".length)
        ReflectionTestUtils.setField(storageController, "storageQuota", 5L);

        // FilePart with NO Content-Length (so upfront check is skipped, but actual transfer triggers post-check)
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        // Don't set Content-Length → declaredSize = -1 → upfront check skipped
        lenient().when(filePart.filename()).thenReturn("big.txt");
        lenient().when(filePart.headers()).thenReturn(headers);
        lenient().when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "test-content"); // 12 bytes > quota 5
            return Mono.empty();
        });

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));

        // Quota SELECT returns 0 → upfront: -1 > 0 is false → skipped; post: 0 + 12 > 5 → error
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<Long> quotaRowsSpec = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.map(any(Function.class))).thenReturn(quotaRowsSpec);
        lenient().when(quotaRowsSpec.first()).thenReturn(Mono.just(0L));

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();

        // Restore quota for other tests
        ReflectionTestUtils.setField(storageController, "storageQuota", 10737418240L);
    }

    // ── deleteFile — IOException when Files.move fails (lines 303-305) ────────

    @Test
    void deleteFile_whenFileMoveToTrashFails_shouldError() throws Exception {
        // Force an IOException by making the bin destination a directory of
        // the same name as the file we want to move into it. Files.move then
        // refuses to overwrite the directory with a regular file.
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        Path userDir = tempDir.resolve(user.getId().toString());
        Files.createDirectories(userDir);
        Path filePath = userDir.resolve("todelete.txt");
        Files.writeString(filePath, "content");

        Path binDir = userDir.resolve("bin_" + user.getId());
        Files.createDirectories(binDir);
        // Create a non-empty subdirectory at the destination filename — Files.move
        // can't replace a non-empty directory, so REPLACE_EXISTING fails.
        Path collidingDir = binDir.resolve("todelete.txt");
        Files.createDirectories(collidingDir);
        Files.writeString(collidingDir.resolve("blocker.txt"), "blocks");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "todelete.txt", null);
        meta.setStoragePath(filePath.toString());

        FolderEntity bin = new FolderEntity();
        bin.setId(UUID.randomUUID());
        bin.setName("bin_" + user.getId());
        bin.setOwnerId(user.getId());
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));

        mockDbSelectOneChain(Mono.just(meta));

        StepVerifier.create(withUser(storageController.deleteFile(fileId), user))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── purgeFile — IOException when Files.deleteIfExists fails (lines 352-354) ─

    @Test
    void purgeFile_whenDeleteFails_shouldError() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        // Create a non-empty directory at the bin file path so deleteIfExists throws DirectoryNotEmptyException
        Path binDir = tempDir.resolve(user.getId().toString()).resolve("bin_" + user.getId());
        Files.createDirectories(binDir);
        // Create a directory named like the file (instead of a file) — and make it non-empty
        Path fakeFilePath = binDir.resolve("stuck.txt");
        Files.createDirectories(fakeFilePath); // directory instead of file
        Files.writeString(fakeFilePath.resolve("child.txt"), "content"); // non-empty

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "stuck.txt", Instant.now());
        meta.setStoragePath(tempDir.resolve("stuck.txt").toString());

        mockDbSelectOneChain(Mono.just(meta));
        // Must stub DELETE so the .then(databaseClient.sql("DELETE...").bind(...)) assembly doesn't NPE
        mockDbDeleteChain();

        StepVerifier.create(withUser(storageController.purgeFile(fileId), user))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── renameFile ────────────────────────────────────────────────────────────

    @Test
    void renameFile_shouldRenameAndSaveMeta() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        // Create the source file on disk
        Path srcDir = tempDir.resolve(user.getId().toString()).resolve("docs");
        Files.createDirectories(srcDir);
        Path srcFile = srcDir.resolve("old.txt");
        Files.writeString(srcFile, "content");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "old.txt", null);
        meta.setFolderId(folderId);
        meta.setStoragePath(srcFile.toString());

        FileMetaEntity savedMeta = buildFileMeta(fileId, user.getId(), "new.txt", null);
        savedMeta.setFolderId(folderId);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(savedMeta));

        StepVerifier.create(withUser(storageController.renameFile(fileId, "new.txt"), user))
                .expectNextMatches(m -> "new.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void renameFile_whenFileNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.renameFile(fileId, "new.txt"), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── moveFile ──────────────────────────────────────────────────────────────

    @Test
    void moveFile_shouldMoveAndUpdateFolderId() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID srcFolderId = UUID.randomUUID();
        UUID dstFolderId = UUID.randomUUID();

        FolderEntity srcFolder = new FolderEntity();
        srcFolder.setId(srcFolderId);
        srcFolder.setName("src");
        srcFolder.setOwnerId(user.getId());
        srcFolder.setParentFolderId(null);

        FolderEntity dstFolder = new FolderEntity();
        dstFolder.setId(dstFolderId);
        dstFolder.setName("dst");
        dstFolder.setOwnerId(user.getId());
        dstFolder.setParentFolderId(null);

        Path srcDir = tempDir.resolve(user.getId().toString()).resolve("src");
        Files.createDirectories(srcDir);
        Path srcFile = srcDir.resolve("file.txt");
        Files.writeString(srcFile, "content");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "file.txt", null);
        meta.setFolderId(srcFolderId);
        meta.setStoragePath(srcFile.toString());

        FileMetaEntity savedMeta = buildFileMeta(fileId, user.getId(), "file.txt", null);
        savedMeta.setFolderId(dstFolderId);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByIdAndOwnerId(srcFolderId, user.getId())).thenReturn(Mono.just(srcFolder));
        when(folderRepo.findByIdAndOwnerId(dstFolderId, user.getId())).thenReturn(Mono.just(dstFolder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(savedMeta));

        StepVerifier.create(withUser(storageController.moveFile(fileId, dstFolderId), user))
                .expectNextMatches(m -> dstFolderId.equals(m.getFolderId()))
                .verifyComplete();
    }

    @Test
    void moveFile_whenFileNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.moveFile(fileId, UUID.randomUUID()), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── copyFile ──────────────────────────────────────────────────────────────

    @Test
    void copyFile_shouldCopyAndCreateNewMeta() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID srcFolderId = UUID.randomUUID();
        UUID dstFolderId = UUID.randomUUID();

        FolderEntity srcFolder = new FolderEntity();
        srcFolder.setId(srcFolderId);
        srcFolder.setName("src");
        srcFolder.setOwnerId(user.getId());
        srcFolder.setParentFolderId(null);

        FolderEntity dstFolder = new FolderEntity();
        dstFolder.setId(dstFolderId);
        dstFolder.setName("dst");
        dstFolder.setOwnerId(user.getId());
        dstFolder.setParentFolderId(null);

        Path srcDir = tempDir.resolve(user.getId().toString()).resolve("src");
        Files.createDirectories(srcDir);
        Path srcFile = srcDir.resolve("photo.jpg");
        Files.writeString(srcFile, "image");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "photo.jpg", null);
        meta.setFolderId(srcFolderId);
        meta.setStoragePath(srcFile.toString());

        FileMetaEntity copiedMeta = buildFileMeta(UUID.randomUUID(), user.getId(), "photo.jpg", null);
        copiedMeta.setFolderId(dstFolderId);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByIdAndOwnerId(srcFolderId, user.getId())).thenReturn(Mono.just(srcFolder));
        when(folderRepo.findByIdAndOwnerId(dstFolderId, user.getId())).thenReturn(Mono.just(dstFolder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(copiedMeta));

        StepVerifier.create(withUser(storageController.copyFile(fileId, dstFolderId), user))
                .expectNextMatches(m -> dstFolderId.equals(m.getFolderId()))
                .verifyComplete();
    }

    @Test
    void copyFile_whenFileNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.copyFile(fileId, UUID.randomUUID()), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── restoreFile ───────────────────────────────────────────────────────────

    @Test
    void restoreFile_shouldMoveFromBinToRootAndClearDeletedAt() throws Exception {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        // Create the bin file on disk
        Path binDir = tempDir.resolve(user.getId().toString()).resolve("bin_" + user.getId());
        Files.createDirectories(binDir);
        Path binFile = binDir.resolve("deleted.txt");
        Files.writeString(binFile, "deleted content");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "deleted.txt", Instant.now());
        meta.setStoragePath(binFile.toString());

        FileMetaEntity restoredMeta = buildFileMeta(fileId, user.getId(), "deleted.txt", null);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(restoredMeta));

        StepVerifier.create(withUser(storageController.restoreFile(fileId), user))
                .expectNextMatches(m -> m.getDeletedAt() == null)
                .verifyComplete();
    }

    @Test
    void restoreFile_whenFileNotDeleted_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "file.txt", null);
        meta.setDeletedAt(null); // not deleted

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));

        StepVerifier.create(withUser(storageController.restoreFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void restoreFile_whenFileNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.restoreFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── resolveOwnedFolder ────────────────────────────────────────────────────

    @Test
    void renameFile_withNullFolderId_shouldUseRootFolder() throws Exception {
        // Tests resolveOwnedFolder with null folderId (root folder path)
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        Path rootDir = tempDir.resolve(user.getId().toString()).resolve("root_" + user.getId());
        Files.createDirectories(rootDir);
        Path srcFile = rootDir.resolve("old.txt");
        Files.writeString(srcFile, "content");

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "old.txt", null);
        meta.setFolderId(null); // null folderId → resolveOwnedFolder returns root folder

        FileMetaEntity saved = buildFileMeta(fileId, user.getId(), "new.txt", null);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(withUser(storageController.renameFile(fileId, "new.txt"), user))
                .expectNextMatches(m -> "new.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    // ── extra branch coverage ────────────────────────────────────────────────

    @Test
    void saveFile_whenContentLengthSetAndQuotaOk_shouldSucceed() {
        // Covers the branch `declaredSize > 0 && (used + declaredSize > quota) == false`
        UserEntity user = buildUser();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentLength(12L); // declaredSize > 0, well under quota
        when(filePart.filename()).thenReturn("hello.txt");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "test-content");
            return Mono.empty();
        });

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        mockDbInsertChain(Mono.just(Map.of("id", rootFolder.getId())));

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .expectNextMatches(m -> "hello.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void updateFile_whenContentLengthSetAndQuotaOk_shouldSucceed() throws Exception {
        // Covers the branch `declaredSize > 0 && (used + declaredSize > quota) == false` in updateFile
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(null);

        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentLength(12L);
        when(filePart.filename()).thenReturn("new.txt");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "test-content");
            return Mono.empty();
        });

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        mockDbUpdateFileChain();

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectNextMatches(m -> "new.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void updateFile_whenNoContentTypeHeader_shouldSkipContentTypeUpdate() throws Exception {
        // Covers the branch `filePart.headers().getContentType() != null` == false at L232
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(null);

        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders(); // intentionally NO content type
        when(filePart.filename()).thenReturn("new.txt");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "test-content");
            return Mono.empty();
        });

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        mockDbUpdateFileChain();

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectNextMatches(m -> "new.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void deleteFile_whenSourceFileMissing_shouldStillUpdateMetadata() throws Exception {
        // Covers the "src does not exist on disk" branch — the DB row still
        // gets repointed at bin so the file-not-on-disk case is recoverable
        // by purging the row.
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        // bin dir must exist so Files.createDirectories inside the runnable doesn't fail
        Path binDir = tempDir.resolve(user.getId().toString()).resolve("bin_" + user.getId());
        Files.createDirectories(binDir);

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "phantom.txt", null);
        meta.setStoragePath(tempDir.resolve("does-not-exist.txt").toString());

        FolderEntity bin = new FolderEntity();
        bin.setId(UUID.randomUUID());
        bin.setName("bin_" + user.getId());
        bin.setOwnerId(user.getId());
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));

        mockDbSelectOneChain(Mono.just(meta));
        mockDbUpdateChain();

        StepVerifier.create(withUser(storageController.deleteFile(fileId), user))
                .verifyComplete();
    }

    @Test
    void renameFile_whenSourceFileMissing_shouldStillSaveMeta() {
        // Covers the branch `Files.exists(src) == false` at L418
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "ghost.txt", null);
        meta.setFolderId(folderId);

        FileMetaEntity savedMeta = buildFileMeta(fileId, user.getId(), "renamed.txt", null);
        savedMeta.setFolderId(folderId);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(savedMeta));

        StepVerifier.create(withUser(storageController.renameFile(fileId, "renamed.txt"), user))
                .expectNextMatches(m -> "renamed.txt".equals(m.getFilename()))
                .verifyComplete();
    }

    @Test
    void moveFile_whenSourceFileMissing_shouldStillUpdateMeta() {
        // Covers the branch `Files.exists(src) == false` at L448
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID srcFolderId = UUID.randomUUID();
        UUID dstFolderId = UUID.randomUUID();

        FolderEntity srcFolder = new FolderEntity();
        srcFolder.setId(srcFolderId);
        srcFolder.setName("src");
        srcFolder.setOwnerId(user.getId());
        srcFolder.setParentFolderId(null);

        FolderEntity dstFolder = new FolderEntity();
        dstFolder.setId(dstFolderId);
        dstFolder.setName("dst");
        dstFolder.setOwnerId(user.getId());
        dstFolder.setParentFolderId(null);

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "ghost.txt", null);
        meta.setFolderId(srcFolderId);

        FileMetaEntity saved = buildFileMeta(fileId, user.getId(), "ghost.txt", null);
        saved.setFolderId(dstFolderId);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByIdAndOwnerId(srcFolderId, user.getId())).thenReturn(Mono.just(srcFolder));
        when(folderRepo.findByIdAndOwnerId(dstFolderId, user.getId())).thenReturn(Mono.just(dstFolder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(withUser(storageController.moveFile(fileId, dstFolderId), user))
                .expectNextMatches(m -> dstFolderId.equals(m.getFolderId()))
                .verifyComplete();
    }

    @Test
    void copyFile_whenSourceFileMissing_shouldStillCreateMeta() {
        // Covers the branch `Files.exists(src) == false` at L478
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();
        UUID srcFolderId = UUID.randomUUID();
        UUID dstFolderId = UUID.randomUUID();

        FolderEntity srcFolder = new FolderEntity();
        srcFolder.setId(srcFolderId);
        srcFolder.setName("src");
        srcFolder.setOwnerId(user.getId());
        srcFolder.setParentFolderId(null);

        FolderEntity dstFolder = new FolderEntity();
        dstFolder.setId(dstFolderId);
        dstFolder.setName("dst");
        dstFolder.setOwnerId(user.getId());
        dstFolder.setParentFolderId(null);

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "ghost.jpg", null);
        meta.setFolderId(srcFolderId);

        FileMetaEntity copied = buildFileMeta(UUID.randomUUID(), user.getId(), "ghost.jpg", null);
        copied.setFolderId(dstFolderId);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByIdAndOwnerId(srcFolderId, user.getId())).thenReturn(Mono.just(srcFolder));
        when(folderRepo.findByIdAndOwnerId(dstFolderId, user.getId())).thenReturn(Mono.just(dstFolder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(copied));

        StepVerifier.create(withUser(storageController.copyFile(fileId, dstFolderId), user))
                .expectNextMatches(m -> dstFolderId.equals(m.getFolderId()))
                .verifyComplete();
    }

    @Test
    void restoreFile_whenBinFileMissing_shouldStillSaveMeta() {
        // Covers the branch `Files.exists(binPath) == false` at L517
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        // No bin file on disk → Files.exists(binPath) is false
        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "ghost.txt", Instant.now());
        FileMetaEntity restored = buildFileMeta(fileId, user.getId(), "ghost.txt", null);

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(restored));

        StepVerifier.create(withUser(storageController.restoreFile(fileId), user))
                .expectNextMatches(m -> m.getDeletedAt() == null)
                .verifyComplete();
    }

    @Test
    void restoreFile_whenRootFolderNotFound_shouldError() {
        // Covers the switchIfEmpty for root folder lookup at L510
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FileMetaEntity meta = buildFileMeta(fileId, user.getId(), "x.txt", Instant.now());

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(meta));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.empty());

        StepVerifier.create(withUser(storageController.restoreFile(fileId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void saveFile_whenRollbackDeleteFails_shouldStillEmitQuotaError() throws Exception {
        // Triggers post-transfer quota check, then makes Files.deleteIfExists throw
        // (DirectoryNotEmptyException) — covers the empty `catch (IOException)` at L112.
        UserEntity user = buildUser();

        FolderEntity folder = new FolderEntity();
        folder.setId(UUID.randomUUID());
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        ReflectionTestUtils.setField(storageController, "storageQuota", 1L);

        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        when(filePart.filename()).thenReturn("dir-collision.txt");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.createDirectories(target); // target is a directory ...
            Files.writeString(target.resolve("child.txt"), "content"); // ... and non-empty
            return Mono.empty();
        });

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(folder));

        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<Long> quotaRowsSpec = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.map(any(Function.class))).thenReturn(quotaRowsSpec);
        lenient().when(quotaRowsSpec.first()).thenReturn(Mono.just(0L));

        StepVerifier.create(withUser(storageController.saveFile(filePart, null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();

        ReflectionTestUtils.setField(storageController, "storageQuota", 10737418240L);
    }

    @Test
    void updateFile_whenRollbackDeleteFails_shouldStillEmitQuotaError() throws Exception {
        // Same as saveFile rollback test but for updateFile — covers L220 catch.
        UserEntity user = buildUser();
        UUID fileId = UUID.randomUUID();

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(UUID.randomUUID());
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        FileMetaEntity existingFile = buildFileMeta(fileId, user.getId(), "old.txt", null);
        existingFile.setFolderId(null);

        ReflectionTestUtils.setField(storageController, "storageQuota", 1L);

        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        when(filePart.filename()).thenReturn("old.txt");
        when(filePart.headers()).thenReturn(headers);
        when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(0);
            Files.createDirectories(target.getParent());
            Files.createDirectories(target);
            Files.writeString(target.resolve("child.txt"), "content");
            return Mono.empty();
        });

        when(fileMetaRepo.findByIdAndOwnerId(fileId, user.getId())).thenReturn(Mono.just(existingFile));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(rootFolder));
        mockDbUpdateFileChain();

        StepVerifier.create(withUser(storageController.updateFile(fileId, filePart), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();

        ReflectionTestUtils.setField(storageController, "storageQuota", 10737418240L);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private FileMetaEntity buildFileMeta(UUID id, UUID ownerId, String filename, Instant deletedAt) {
        FileMetaEntity meta = new FileMetaEntity();
        meta.setId(id);
        meta.setFilename(filename);
        meta.setContentType("text/plain");
        meta.setSize(100L);
        meta.setStoragePath(tempDir.resolve(filename).toString());
        meta.setUploadedAt(Instant.now());
        meta.setOwnerId(ownerId);
        meta.setFolderId(null);
        meta.setDeletedAt(deletedAt);
        return meta;
    }
}
