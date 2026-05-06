package server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolderControllerTest {

    @Mock
    private FolderRepo folderRepo;

    @Mock
    private FileMetaRepo fileMetaRepo;

    @Mock
    private StorageController storageCtl;

    @Mock
    private DatabaseClient databaseClient;

    @InjectMocks
    private FolderController folderController;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(folderController, "storagePath", tempDir.toString());
    }

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
        var ctx = new SecurityContextImpl(auth);
        return mono.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx)));
    }

    // ── createRootFolder ──────────────────────────────────────────────────────

    @Test
    void createRootFolder_shouldSaveAndReturnFolder() {
        UUID userId = UUID.randomUUID();
        FolderEntity saved = new FolderEntity();
        saved.setId(UUID.randomUUID());
        saved.setName("root_" + userId);
        saved.setOwnerId(userId);

        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(folderController.createRootFolder(userId))
                .expectNextMatches(f -> f.getName().startsWith("root_"))
                .verifyComplete();
    }

    // ── createBinFolder ───────────────────────────────────────────────────────

    @Test
    void createBinFolder_shouldSaveAndReturnFolder() {
        UUID userId = UUID.randomUUID();
        FolderEntity saved = new FolderEntity();
        saved.setId(UUID.randomUUID());
        saved.setName("bin_" + userId);
        saved.setOwnerId(userId);

        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(folderController.createBinFolder(userId))
                .expectNextMatches(f -> f.getName().startsWith("bin_"))
                .verifyComplete();
    }

    // ── getOrCreateRootFolder ─────────────────────────────────────────────────

    @Test
    void getOrCreateRootFolder_whenExists_shouldReturnExisting() {
        UserEntity user = buildUser();
        FolderEntity existing = new FolderEntity();
        existing.setId(UUID.randomUUID());
        existing.setName("root_" + user.getId());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.just(existing));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.getOrCreateRootFolder(), user))
                .expectNextMatches(f -> f.getName().startsWith("root_"))
                .verifyComplete();
    }

    @Test
    void getOrCreateRootFolder_whenNotExists_shouldCreate() {
        UserEntity user = buildUser();
        FolderEntity created = new FolderEntity();
        created.setId(UUID.randomUUID());
        created.setName("root_" + user.getId());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.empty());
        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(created));

        StepVerifier.create(withUser(folderController.getOrCreateRootFolder(), user))
                .expectNextMatches(f -> f.getName().startsWith("root_"))
                .verifyComplete();
    }

    // ── getOrCreateBinFolder ──────────────────────────────────────────────────

    @Test
    void getOrCreateBinFolder_whenExists_shouldReturnExisting() {
        UserEntity user = buildUser();
        FolderEntity existing = new FolderEntity();
        existing.setId(UUID.randomUUID());
        existing.setName("bin_" + user.getId());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.just(existing));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.getOrCreateBinFolder(), user))
                .expectNextMatches(f -> f.getName().startsWith("bin_"))
                .verifyComplete();
    }

    @Test
    void getOrCreateBinFolder_whenNotExists_shouldCreate() {
        UserEntity user = buildUser();
        FolderEntity created = new FolderEntity();
        created.setId(UUID.randomUUID());
        created.setName("bin_" + user.getId());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.empty());
        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(created));

        StepVerifier.create(withUser(folderController.getOrCreateBinFolder(), user))
                .expectNextMatches(f -> f.getName().startsWith("bin_"))
                .verifyComplete();
    }

    // ── createFolder (parentFolderId != null) ─────────────────────────────────

    @Test
    void createFolder_withParent_shouldSucceed() throws IOException {
        UserEntity user = buildUser();
        UUID parentId = UUID.randomUUID();

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("root_" + user.getId()); // must be non-null for path building
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null); // root-level parent → no further parent lookup

        FolderEntity newFolder = new FolderEntity();
        newFolder.setId(UUID.randomUUID());
        newFolder.setName("docs");
        newFolder.setOwnerId(user.getId());
        newFolder.setParentFolderId(parentId);

        // Create the parent directory on disk so Files.createDirectories resolves correctly
        Files.createDirectories(tempDir.resolve(user.getId().toString()).resolve(parent.getName()));

        when(folderRepo.findByIdAndOwnerId(parentId, user.getId())).thenReturn(Mono.just(parent));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "docs"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(newFolder));

        StepVerifier.create(withUser(folderController.createFolder("docs", parentId), user))
                .expectNextMatches(f -> "docs".equals(f.getName()))
                .verifyComplete();
    }

    @Test
    void createFolder_whenParentNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID parentId = UUID.randomUUID();

        when(folderRepo.findByIdAndOwnerId(parentId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.createFolder("docs", parentId), user))
                .expectErrorMatches(e -> e instanceof RuntimeException &&
                        e.getMessage().contains("Parent folder not found or access denied"))
                .verify();
    }

    @Test
    void createFolder_whenAlreadyExists_shouldError() {
        UserEntity user = buildUser();
        UUID parentId = UUID.randomUUID();

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setOwnerId(user.getId());

        when(folderRepo.findByIdAndOwnerId(parentId, user.getId())).thenReturn(Mono.just(parent));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "docs"))
                .thenReturn(Mono.just(true));

        StepVerifier.create(withUser(folderController.createFolder("docs", parentId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    // ── createFolder (parentFolderId == null) ─────────────────────────────────

    @Test
    void createFolder_withNullParent_shouldSucceed() {
        UserEntity user = buildUser();

        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root_" + user.getId());
        root.setOwnerId(user.getId());
        root.setParentFolderId(null);

        FolderEntity newFolder = new FolderEntity();
        newFolder.setId(UUID.randomUUID());
        newFolder.setName("photos");

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(root));
        when(folderRepo.existsByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "photos"))
                .thenReturn(Mono.just(false));
        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(newFolder));

        StepVerifier.create(withUser(folderController.createFolder("photos", null), user))
                .expectNextMatches(f -> "photos".equals(f.getName()))
                .verifyComplete();
    }

    @Test
    void createFolder_withNullParent_whenFolderExists_shouldError() {
        UserEntity user = buildUser();

        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root_" + user.getId());
        root.setOwnerId(user.getId());
        root.setParentFolderId(null);

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.just(root));
        // switchIfEmpty(createRootFolder(userId)) evaluates eagerly — must stub save() to avoid NPE
        lenient().when(folderRepo.save(any())).thenReturn(Mono.just(root));
        when(folderRepo.existsByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(withUser(folderController.createFolder("photos", null), user))
                .expectErrorMatches(e -> e instanceof RuntimeException &&
                        e.getMessage().contains("Folder already exists"))
                .verify();
    }

    // ── getFolderContent ──────────────────────────────────────────────────────

    @Test
    void getFolderContent_shouldReturnContent() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setOwnerId(user.getId());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.empty());

        StepVerifier.create(withUser(folderController.getFolderContent(folderId), user))
                .expectNextMatches(c -> c.currentFolder().equals(folder)
                        && c.subFolders().isEmpty()
                        && c.files().isEmpty())
                .verifyComplete();
    }

    @Test
    void getFolderContent_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.getFolderContent(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── getRootContent ────────────────────────────────────────────────────────

    @Test
    void getRootContent_shouldReturnRootFolderContent() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity root = new FolderEntity();
        root.setId(folderId);
        root.setOwnerId(user.getId());
        root.setName("root_" + user.getId());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.just(root));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.empty());
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(root));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.empty());

        StepVerifier.create(withUser(folderController.getRootContent(), user))
                .expectNextMatches(c -> c.currentFolder().equals(root))
                .verifyComplete();
    }

    // ── getBinContent ─────────────────────────────────────────────────────────

    @Test
    void getBinContent_shouldReturnBinFolderContent() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity bin = new FolderEntity();
        bin.setId(folderId);
        bin.setOwnerId(user.getId());
        bin.setName("bin_" + user.getId());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(any(UUID.class), anyString()))
                .thenReturn(Mono.just(bin));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.empty());
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(bin));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.empty());

        StepVerifier.create(withUser(folderController.getBinContent(), user))
                .expectNextMatches(c -> c.currentFolder().equals(bin))
                .verifyComplete();
    }

    // ── getFolderMeta (folderId != null) ──────────────────────────────────────

    @Test
    void getFolderMeta_withId_shouldReturnMeta() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);
        folder.setCreatedAt(Instant.now());
        folder.setDeletedAt(null);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.getFolderMeta(folderId), user))
                .expectNextMatches(m -> folderId.equals(m.folderId()) && "docs".equals(m.name()))
                .verifyComplete();
    }

    @Test
    void getFolderMeta_withId_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.getFolderMeta(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── getFolderMeta (folderId == null → root) ───────────────────────────────

    @Test
    void getFolderMeta_withNullId_shouldReturnRootMeta() {
        UserEntity user = buildUser();

        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root_" + user.getId());
        root.setOwnerId(user.getId());
        root.setParentFolderId(null);
        root.setCreatedAt(Instant.now());

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(root));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.getFolderMeta(null), user))
                .expectNextMatches(m -> m.name().startsWith("root_"))
                .verifyComplete();
    }

    // ── deleteFolder ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mockDbUpdateChain() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));
    }

    /**
     * Helpers for the new delete-folder flow: deleteFolder now moves the folder
     * as a single unit into bin_<uid>, so any test that exercises it has to
     * stub the bin-lookup, the unique-name probe, and the parent walk used
     * to compute physical paths.
     */
    private FolderEntity buildBin(UUID userId) {
        FolderEntity bin = new FolderEntity();
        bin.setId(UUID.randomUUID());
        bin.setName("bin_" + userId);
        bin.setOwnerId(userId);
        bin.setParentFolderId(null);
        bin.setCreatedAt(Instant.now());
        return bin;
    }

    @Test
    void deleteFolder_shouldMarkFolderDeleted() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId); // not a system folder

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("root_" + user.getId());
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null);

        FolderEntity bin = buildBin(user.getId());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), bin.getId(), "docs"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.deleteFolder(folderId), user))
                .verifyComplete();
    }

    @Test
    void deleteFolder_withSubfolders_shouldDeleteAll() {
        // After the rewrite, deleteFolder doesn't recurse: it moves the whole
        // subtree as one unit. So this test just verifies a folder with a
        // subfolder still bins cleanly without ever touching the child rows.
        UserEntity user = buildUser();
        UUID parentId = UUID.randomUUID();
        UUID parentParentId = UUID.randomUUID();

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("parent");
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(parentParentId);

        FolderEntity grand = new FolderEntity();
        grand.setId(parentParentId);
        grand.setName("root_" + user.getId());
        grand.setOwnerId(user.getId());
        grand.setParentFolderId(null);

        FolderEntity bin = buildBin(user.getId());

        when(folderRepo.findByIdAndOwnerId(parentId, user.getId())).thenReturn(Mono.just(parent));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), bin.getId(), "parent"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentParentId)).thenReturn(Mono.just(grand));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.deleteFolder(parentId), user))
                .verifyComplete();
    }

    @Test
    void deleteFolder_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.deleteFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void deleteFolder_systemFolder_shouldError() {
        // root_/bin_ folders have parent_folder_id = null. The new deleteFolder
        // refuses to bin them outright (they belong to the system, not the user).
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.deleteFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void deleteFolder_alreadyDeleted_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);
        folder.setDeletedAt(Instant.now()); // already trashed

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.deleteFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void deleteFolder_whenAlreadyUnderBin_shouldError() {
        // Edge case: caller passes a folder that already lives under bin.
        // We refuse to bin it again (it would be a no-op + collision risk).
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        FolderEntity bin = buildBin(user.getId());

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(bin.getId());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));

        StepVerifier.create(withUser(folderController.deleteFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void deleteFolder_withNameCollisionInBin_shouldUniquify() {
        // Two folders named "docs" trashed in sequence — the second must get
        // a "(копия)" suffix on entry to bin so the unique constraint
        // (name, parent_folder_id, owner_id) doesn't trip.
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("root_" + user.getId());
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null);

        FolderEntity bin = buildBin(user.getId());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), bin.getId(), "docs"))
                .thenReturn(Mono.just(true));   // collision
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), bin.getId(), "docs (копия)"))
                .thenReturn(Mono.just(false));  // free
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.deleteFolder(folderId), user))
                .verifyComplete();
    }

    // ── purgeFolder ───────────────────────────────────────────────────────────

    @Test
    void purgeFolder_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.purgeFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void purgeFolder_emptyFolder_shouldDeleteFolderFromRepo() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("empty");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        // Create the directory structure so Files.deleteIfExists works
        Path folderPath = tempDir.resolve(user.getId().toString()).resolve("empty");
        Files.createDirectories(folderPath);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(folderRepo.deleteById(folderId)).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.purgeFolder(folderId), user))
                .verifyComplete();
    }

    @Test
    void purgeFolder_withFiles_shouldPurgeFilesAndDeleteFolder() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("bin");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        // Create the directory structure
        Path folderPath = tempDir.resolve(user.getId().toString()).resolve("bin");
        Files.createDirectories(folderPath);

        FileMetaEntity fileMeta = new FileMetaEntity();
        fileMeta.setId(fileId);
        fileMeta.setFilename("file.txt");

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.just(fileMeta));
        when(storageCtl.purgeFile(fileId)).thenReturn(Mono.empty());
        when(folderRepo.deleteById(folderId)).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.purgeFolder(folderId), user))
                .verifyComplete();
    }

    @Test
    void purgeFolder_whenDeleteIfExistsFails_shouldError() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("nonemptydir");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        // Create the directory with a file inside so Files.deleteIfExists throws DirectoryNotEmptyException
        Path folderPath = tempDir.resolve(user.getId().toString()).resolve("nonemptydir");
        Files.createDirectories(folderPath);
        Files.writeString(folderPath.resolve("file.txt"), "content"); // makes dir non-empty

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        // .then(folderRepository.deleteById(...)) evaluates eagerly as argument — stub to avoid NPE
        lenient().when(folderRepo.deleteById(any(UUID.class))).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.purgeFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    // ── IOException error paths ───────────────────────────────────────────────

    @Test
    void createRootFolder_whenDirectoryCreationFails_shouldError() throws IOException {
        UUID userId = UUID.randomUUID();
        // Make the userId directory a regular FILE so createDirectories fails when trying to
        // create a subdirectory under it
        Path userDir = tempDir.resolve(userId.toString());
        Files.writeString(userDir, "blocking-file"); // file where a dir should be

        StepVerifier.create(folderController.createRootFolder(userId))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void createBinFolder_whenDirectoryCreationFails_shouldError() throws IOException {
        UUID userId = UUID.randomUUID();
        Path userDir = tempDir.resolve(userId.toString());
        Files.writeString(userDir, "blocking-file");

        StepVerifier.create(folderController.createBinFolder(userId))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void createFolder_withNullParent_whenIOExceptionOccurs_shouldError() throws IOException {
        UserEntity user = buildUser();

        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root_" + user.getId());
        root.setOwnerId(user.getId());
        root.setParentFolderId(null);

        // Create a file at the path where the folder sub-directory would be created
        // storagePath/userId/root_userId is a file → createDirectories("photos") will fail
        Path rootPath = tempDir.resolve(user.getId().toString()).resolve("root_" + user.getId());
        Files.createDirectories(tempDir.resolve(user.getId().toString()));
        Files.writeString(rootPath, "blocking-file"); // file where a dir should be

        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(root));
        when(folderRepo.existsByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "new_photos"))
                .thenReturn(Mono.just(false));

        StepVerifier.create(withUser(folderController.createFolder("new_photos", null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void createFolder_withParent_whenIOExceptionOccurs_shouldError() throws IOException {
        UserEntity user = buildUser();
        UUID parentId = UUID.randomUUID();

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("parent_dir");
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null);

        // The path would be storagePath/userId/parent_dir/new_child
        // Make "parent_dir" a file → createDirectories will fail
        Path parentPath = tempDir.resolve(user.getId().toString()).resolve("parent_dir");
        Files.createDirectories(tempDir.resolve(user.getId().toString()));
        Files.writeString(parentPath, "blocking-file");

        when(folderRepo.findByIdAndOwnerId(parentId, user.getId())).thenReturn(Mono.just(parent));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "new_child"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        // .then(folderRepository.save(newFolder)) evaluates save() eagerly as argument → must stub
        lenient().when(folderRepo.save(any())).thenReturn(Mono.just(parent));

        StepVerifier.create(withUser(folderController.createFolder("new_child", parentId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    // ── getAllDescendantFolders — null id branch (line 180) ────────────────────

    @Test
    void getAllDescendantFolders_withNullIdChild_shortCircuits() {
        // Covers the null-id branch in getAllDescendantFolders (the .expandDeep
        // step short-circuits to Mono.empty() when id is null). After the
        // deleteFolder rewrite this branch is reached via purgeFolder instead;
        // we exercise it here by handing purgeFolder a tree where one
        // descendant carries a null id (legacy data).
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null); // system-like, but purge accepts it

        FolderEntity nullIdChild = new FolderEntity();
        nullIdChild.setId(null);
        nullIdChild.setOwnerId(user.getId());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId))
                .thenReturn(Flux.just(nullIdChild));

        // The null-id child causes a NullPointerException downstream (purgeFilesInFolder
        // calls folderId.toString()), proving the .expand branch was traversed.
        StepVerifier.create(withUser(folderController.purgeFolder(folderId), user))
                .expectError(NullPointerException.class)
                .verify();
    }

    // ── getFolderPathSegments — non-null parentFolderId (line 321) ─────────────

    @Test
    void createFolder_withNestedParent_shouldBuildDeepPath() throws IOException {
        // Tests getFolderPathSegments when parent folder itself has a non-null parentFolderId
        // This causes folderRepository.findById() to be called (line 321)
        UserEntity user = buildUser();

        UUID grandParentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity grandParent = new FolderEntity();
        grandParent.setId(grandParentId);
        grandParent.setName("root_" + user.getId());
        grandParent.setOwnerId(user.getId());
        grandParent.setParentFolderId(null);

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("level1");
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(grandParentId); // non-null → triggers findById at line 321

        FolderEntity newFolder = new FolderEntity();
        newFolder.setId(UUID.randomUUID());
        newFolder.setName("level2");
        newFolder.setOwnerId(user.getId());
        newFolder.setParentFolderId(parentId);

        // Create the nested directory on disk
        Path nestedDir = tempDir.resolve(user.getId().toString())
                .resolve("root_" + user.getId()).resolve("level1");
        Files.createDirectories(nestedDir);

        when(folderRepo.findByIdAndOwnerId(parentId, user.getId())).thenReturn(Mono.just(parent));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "level2"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        // Line 321: findById(grandParentId) when expanding grandParent's parent chain
        when(folderRepo.findById(grandParentId)).thenReturn(Mono.just(grandParent));
        lenient().when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(newFolder));

        StepVerifier.create(withUser(folderController.createFolder("level2", parentId), user))
                .expectNextMatches(f -> "level2".equals(f.getName()))
                .verifyComplete();
    }

    // ── buildPhysicalPath with null folder (line 303) ─────────────────────────

    @Test
    void buildPhysicalPath_withNullFolder_shouldReturnUserDir() throws Exception {
        java.lang.reflect.Method method = FolderController.class.getDeclaredMethod(
                "buildPhysicalPath", UUID.class, FolderEntity.class);
        method.setAccessible(true);

        UUID userId = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        reactor.core.publisher.Mono<java.nio.file.Path> result =
                (reactor.core.publisher.Mono<java.nio.file.Path>) method.invoke(folderController, userId, null);

        StepVerifier.create(result)
                .expectNextMatches(p -> p.equals(java.nio.file.Paths.get(tempDir.toString(), userId.toString())))
                .verifyComplete();
    }

    // ── getCurrentUserId — admin principal rejection ──────────────────────────

    @Test
    void getOrCreateRootFolder_whenAdminPrincipal_shouldError() {
        // Personal storage is user-only; admins should bounce out with 403.
        AdminEntity admin = new AdminEntity();
        admin.setId(UUID.randomUUID());
        admin.setUsername("adminUser");
        admin.setPassword("encoded");
        admin.setRole("ADMIN");

        var auth = UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities());
        var ctx = new SecurityContextImpl(auth);

        StepVerifier.create(folderController.getOrCreateRootFolder()
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    // ── renameFolder ──────────────────────────────────────────────────────────

    @Test
    void renameFolder_whenNameNull_shouldError() {
        UserEntity user = buildUser();
        StepVerifier.create(withUser(folderController.renameFolder(UUID.randomUUID(), null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void renameFolder_whenNameBlank_shouldError() {
        UserEntity user = buildUser();
        StepVerifier.create(withUser(folderController.renameFolder(UUID.randomUUID(), "   "), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void renameFolder_whenNameContainsSlash_shouldError() {
        UserEntity user = buildUser();
        StepVerifier.create(withUser(folderController.renameFolder(UUID.randomUUID(), "foo/bar"), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
        StepVerifier.create(withUser(folderController.renameFolder(UUID.randomUUID(), "foo\\bar"), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void renameFolder_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.renameFolder(folderId, "newname"), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void renameFolder_systemFolder_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null); // system

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.renameFolder(folderId, "new"), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void renameFolder_sameName_shouldReturnFolderUnchanged() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.renameFolder(folderId, "docs"), user))
                .expectNextMatches(f -> "docs".equals(f.getName()))
                .verifyComplete();
    }

    @Test
    void renameFolder_whenCollision_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "newname"))
                .thenReturn(Mono.just(true));

        StepVerifier.create(withUser(folderController.renameFolder(folderId, "newname"), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.CONFLICT)
                .verify();
    }

    @Test
    void renameFolder_happyPath_shouldRenameAndReturnFolder() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("root_" + user.getId());
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null);

        FolderEntity renamed = new FolderEntity();
        renamed.setId(folderId);
        renamed.setName("newname");
        renamed.setOwnerId(user.getId());
        renamed.setParentFolderId(parentId);

        // Make on-disk source exist so Files.move runs
        Path src = tempDir.resolve(user.getId().toString())
                .resolve("root_" + user.getId()).resolve("docs");
        Files.createDirectories(src);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId()))
                .thenReturn(Mono.just(folder), Mono.just(renamed));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "newname"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.renameFolder(folderId, "newname"), user))
                .expectNextMatches(f -> "newname".equals(f.getName()))
                .verifyComplete();
    }

    // ── restoreFolder ─────────────────────────────────────────────────────────

    @Test
    void restoreFolder_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.restoreFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void restoreFolder_whenNotDeleted_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(UUID.randomUUID());
        folder.setDeletedAt(null); // not deleted

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.restoreFolder(folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.CONFLICT)
                .verify();
    }

    @Test
    void restoreFolder_happyPath_shouldRestoreFolder() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity bin = buildBin(user.getId());
        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root_" + user.getId());
        root.setOwnerId(user.getId());
        root.setParentFolderId(null);

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(bin.getId());
        folder.setDeletedAt(Instant.now());

        FolderEntity restored = new FolderEntity();
        restored.setId(folderId);
        restored.setName("docs");
        restored.setOwnerId(user.getId());
        restored.setParentFolderId(root.getId());
        restored.setDeletedAt(null);

        Path src = tempDir.resolve(user.getId().toString())
                .resolve("bin_" + user.getId()).resolve("docs");
        Files.createDirectories(src);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId()))
                .thenReturn(Mono.just(folder), Mono.just(restored));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(root));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.just(root));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), root.getId(), "docs"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(bin.getId())).thenReturn(Mono.just(bin));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.restoreFolder(folderId), user))
                .expectNextMatches(f -> "docs".equals(f.getName()) && f.getDeletedAt() == null)
                .verifyComplete();
    }

    @Test
    void restoreFolder_withCollision_shouldUniquify() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity bin = buildBin(user.getId());
        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root_" + user.getId());
        root.setOwnerId(user.getId());
        root.setParentFolderId(null);

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(bin.getId());
        folder.setDeletedAt(Instant.now());

        FolderEntity restored = new FolderEntity();
        restored.setId(folderId);
        restored.setName("docs (копия)");
        restored.setOwnerId(user.getId());
        restored.setParentFolderId(root.getId());
        restored.setDeletedAt(null);

        Path src = tempDir.resolve(user.getId().toString())
                .resolve("bin_" + user.getId()).resolve("docs");
        Files.createDirectories(src);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId()))
                .thenReturn(Mono.just(folder), Mono.just(restored));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(root));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.just(root));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), root.getId(), "docs"))
                .thenReturn(Mono.just(true));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), root.getId(), "docs (копия)"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(bin.getId())).thenReturn(Mono.just(bin));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.restoreFolder(folderId), user))
                .expectNextMatches(f -> "docs (копия)".equals(f.getName()))
                .verifyComplete();
    }

    // ── moveFolder ────────────────────────────────────────────────────────────

    @Test
    void moveFolder_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.moveFolder(folderId, UUID.randomUUID()), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void moveFolder_systemFolder_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null); // system

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.moveFolder(folderId, UUID.randomUUID()), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void moveFolder_targetParentNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(UUID.randomUUID());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByIdAndOwnerId(targetId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.moveFolder(folderId, targetId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void moveFolder_intoItself_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(UUID.randomUUID());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.moveFolder(folderId, folderId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void moveFolder_intoDescendant_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID descId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(UUID.randomUUID());

        FolderEntity desc = new FolderEntity();
        desc.setId(descId);
        desc.setName("child");
        desc.setOwnerId(user.getId());
        desc.setParentFolderId(folderId);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByIdAndOwnerId(descId, user.getId())).thenReturn(Mono.just(desc));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId))
                .thenReturn(Flux.just(desc));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), descId))
                .thenReturn(Flux.empty());

        StepVerifier.create(withUser(folderController.moveFolder(folderId, descId), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void moveFolder_toRoot_shouldSucceed() throws IOException {
        // newParentId = null → resolveTargetParent returns getOrCreateRootFolder
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID oldParentId = UUID.randomUUID();

        FolderEntity oldParent = new FolderEntity();
        oldParent.setId(oldParentId);
        oldParent.setName("root_" + user.getId()); // the old parent IS root by name (irrelevant)
        oldParent.setOwnerId(user.getId());
        oldParent.setParentFolderId(null);

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(oldParentId);

        FolderEntity root = new FolderEntity();
        root.setId(UUID.randomUUID());
        root.setName("root_" + user.getId());
        root.setOwnerId(user.getId());
        root.setParentFolderId(null);

        Path src = tempDir.resolve(user.getId().toString())
                .resolve(oldParent.getName()).resolve("docs");
        Files.createDirectories(src);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId()))
                .thenReturn(Mono.just(folder), Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(root));
        lenient().when(folderRepo.save(any())).thenReturn(Mono.just(root));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId))
                .thenReturn(Flux.empty()); // no descendants
        when(folderRepo.findById(oldParentId)).thenReturn(Mono.just(oldParent));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.moveFolder(folderId, null), user))
                .expectNextMatches(f -> "docs".equals(f.getName()))
                .verifyComplete();
    }

    // ── copyFolder ────────────────────────────────────────────────────────────

    @Test
    void copyFolder_whenNotFound_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.empty());

        StepVerifier.create(withUser(folderController.copyFolder(folderId, null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void copyFolder_systemFolder_shouldError() {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("root_" + user.getId());
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(null);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));

        StepVerifier.create(withUser(folderController.copyFolder(folderId, null), user))
                .expectErrorMatches(e -> e instanceof ResponseStatusException &&
                        ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void copyFolder_happyPath_shouldRecursivelyCopy() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("root_" + user.getId());
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null);

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);

        // A file inside the source folder
        Path srcDir = tempDir.resolve(user.getId().toString())
                .resolve("root_" + user.getId()).resolve("docs");
        Files.createDirectories(srcDir);
        Path srcFile = srcDir.resolve("a.txt");
        Files.writeString(srcFile, "content");

        FileMetaEntity fileMeta = new FileMetaEntity();
        fileMeta.setId(UUID.randomUUID());
        fileMeta.setFilename("a.txt");
        fileMeta.setContentType("text/plain");
        fileMeta.setSize(7L);
        fileMeta.setStoragePath(srcFile.toString());
        fileMeta.setOwnerId(user.getId());
        fileMeta.setFolderId(folderId);
        fileMeta.setUploadedAt(Instant.now());
        fileMeta.setDeletedAt(null);

        FolderEntity savedCopy = new FolderEntity();
        savedCopy.setId(UUID.randomUUID());
        savedCopy.setName("docs");
        savedCopy.setOwnerId(user.getId());
        savedCopy.setParentFolderId(parentId);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        // resolveTargetParent: null parent → getOrCreateRootFolder
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(parent));
        // uniqueFolderName collision probe
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "docs"))
                .thenReturn(Mono.just(false));
        // copyFolderRecursive needs path lookup of src.parent
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        // The new folder is saved
        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(savedCopy));
        // Files in the source folder
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.just(fileMeta));
        // FileMetaEntity copy is saved
        when(fileMetaRepo.save(any(FileMetaEntity.class))).thenReturn(Mono.just(fileMeta));
        // No subfolders
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());

        StepVerifier.create(withUser(folderController.copyFolder(folderId, null), user))
                .expectNextMatches(f -> "docs".equals(f.getName()))
                .verifyComplete();
    }

    // ── deleteFolder — physical-move branch (src exists on disk) ──────────────

    @Test
    void deleteFolder_whenSrcExistsOnDisk_shouldPhysicallyMoveIntoBin() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("root_" + user.getId());
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null);

        FolderEntity bin = buildBin(user.getId());

        // Make on-disk folder exist so Files.move actually runs
        Path src = tempDir.resolve(user.getId().toString())
                .resolve("root_" + user.getId()).resolve("docs");
        Files.createDirectories(src);
        // Create bin parent dir so Files.createDirectories(dst.getParent()) is a no-op
        Files.createDirectories(tempDir.resolve(user.getId().toString())
                .resolve("bin_" + user.getId()));

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "bin_" + user.getId()))
                .thenReturn(Mono.just(bin));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), bin.getId(), "docs"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        mockDbUpdateChain();

        StepVerifier.create(withUser(folderController.deleteFolder(folderId), user))
                .verifyComplete();
    }

    // ── copyFolderRecursive — sub-folder recursion branch ─────────────────────

    @Test
    void copyFolder_withSubFolders_shouldRecursivelyCopy() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        FolderEntity parent = new FolderEntity();
        parent.setId(parentId);
        parent.setName("root_" + user.getId());
        parent.setOwnerId(user.getId());
        parent.setParentFolderId(null);

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(parentId);

        FolderEntity sub = new FolderEntity();
        sub.setId(subId);
        sub.setName("inner");
        sub.setOwnerId(user.getId());
        sub.setParentFolderId(folderId);
        sub.setDeletedAt(null); // must be non-deleted to pass filter

        // Make source layout
        Path srcDir = tempDir.resolve(user.getId().toString())
                .resolve("root_" + user.getId()).resolve("docs");
        Files.createDirectories(srcDir);
        Files.createDirectories(srcDir.resolve("inner"));

        FolderEntity savedTop = new FolderEntity();
        savedTop.setId(UUID.randomUUID());
        savedTop.setName("docs");
        savedTop.setOwnerId(user.getId());
        savedTop.setParentFolderId(parentId);

        FolderEntity savedSub = new FolderEntity();
        savedSub.setId(UUID.randomUUID());
        savedSub.setName("inner");
        savedSub.setOwnerId(user.getId());
        savedSub.setParentFolderId(savedTop.getId());

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByOwnerIdAndParentFolderIdIsNullAndName(user.getId(), "root_" + user.getId()))
                .thenReturn(Mono.just(parent));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), parentId, "docs"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(parentId)).thenReturn(Mono.just(parent));
        // recursive call: path lookup for sub walks via findById(folderId)
        when(folderRepo.findById(folderId)).thenReturn(Mono.just(folder));
        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(savedTop), Mono.just(savedSub));
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), subId)).thenReturn(Flux.empty());
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.just(sub));
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), subId)).thenReturn(Flux.empty());

        StepVerifier.create(withUser(folderController.copyFolder(folderId, null), user))
                .expectNextMatches(f -> "docs".equals(f.getName()))
                .verifyComplete();
    }

    @Test
    void copyFolder_withCollision_shouldUniquify() throws IOException {
        UserEntity user = buildUser();
        UUID folderId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();

        FolderEntity target = new FolderEntity();
        target.setId(targetId);
        target.setName("targetFolder");
        target.setOwnerId(user.getId());
        target.setParentFolderId(rootId);

        FolderEntity rootFolder = new FolderEntity();
        rootFolder.setId(rootId);
        rootFolder.setName("root_" + user.getId());
        rootFolder.setOwnerId(user.getId());
        rootFolder.setParentFolderId(null);

        UUID srcParent = UUID.randomUUID();
        FolderEntity srcParentFolder = new FolderEntity();
        srcParentFolder.setId(srcParent);
        srcParentFolder.setName("root_" + user.getId());
        srcParentFolder.setOwnerId(user.getId());
        srcParentFolder.setParentFolderId(null);

        FolderEntity folder = new FolderEntity();
        folder.setId(folderId);
        folder.setName("docs");
        folder.setOwnerId(user.getId());
        folder.setParentFolderId(srcParent);

        // make src dir exist (no files)
        Path srcDir = tempDir.resolve(user.getId().toString())
                .resolve("root_" + user.getId()).resolve("targetFolder");
        Files.createDirectories(srcDir);
        Path origDir = tempDir.resolve(user.getId().toString())
                .resolve("root_" + user.getId()).resolve("docs");
        Files.createDirectories(origDir);

        FolderEntity savedCopy = new FolderEntity();
        savedCopy.setId(UUID.randomUUID());
        savedCopy.setName("docs (копия)");
        savedCopy.setOwnerId(user.getId());
        savedCopy.setParentFolderId(targetId);

        when(folderRepo.findByIdAndOwnerId(folderId, user.getId())).thenReturn(Mono.just(folder));
        when(folderRepo.findByIdAndOwnerId(targetId, user.getId())).thenReturn(Mono.just(target));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), targetId, "docs"))
                .thenReturn(Mono.just(true));
        when(folderRepo.existsByOwnerIdAndParentFolderIdAndName(user.getId(), targetId, "docs (копия)"))
                .thenReturn(Mono.just(false));
        when(folderRepo.findById(srcParent)).thenReturn(Mono.just(srcParentFolder));
        when(folderRepo.findById(rootId)).thenReturn(Mono.just(rootFolder));
        when(folderRepo.save(any(FolderEntity.class))).thenReturn(Mono.just(savedCopy));
        when(fileMetaRepo.findByOwnerIdAndFolderId(user.getId(), folderId)).thenReturn(Flux.empty());
        when(folderRepo.findByOwnerIdAndParentFolderId(user.getId(), folderId)).thenReturn(Flux.empty());

        StepVerifier.create(withUser(folderController.copyFolder(folderId, targetId), user))
                .expectNextMatches(f -> "docs (копия)".equals(f.getName()))
                .verifyComplete();
    }
}
