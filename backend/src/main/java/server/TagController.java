package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import server.repository.TagRepo;
import server.repository.FileMetaRepo;

import java.time.Instant;
import java.util.UUID;

/**
 * FR#20 (tags) + NFT#9 (≤300 ms tag operations).
 *
 * Tag CRUD scoped per-owner (each user has own tag namespace; uniqueness on
 * (owner_id, name)). File ↔ tag links live in `file_tags` join table; we
 * enforce that the file belongs to the same owner as the tag before linking
 * (prevents cross-account tagging).
 */
@RestController
@Slf4j
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagRepo tagRepo;
    private final FileMetaRepo fileMetaRepo;
    private final DatabaseClient databaseClient;

    private Mono<UUID> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getPrincipal())
                .flatMap(p -> p instanceof UserEntity u
                        ? Mono.just(u.getId())
                        : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Administrator accounts have no personal tags")));
    }

    @GetMapping
    public Flux<TagEntity> list() {
        return currentUserId().flatMapMany(tagRepo::findByOwnerId);
    }

    @PostMapping
    public Mono<TagEntity> create(@RequestBody CreateTagRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag name required"));
        }
        if (req.name().length() > 64) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag name too long"));
        }
        return currentUserId().flatMap(uid ->
                tagRepo.existsByOwnerIdAndName(uid, req.name())
                        .flatMap(exists -> {
                            if (Boolean.TRUE.equals(exists)) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Tag with this name already exists"));
                            }
                            TagEntity t = new TagEntity();
                            t.setOwnerId(uid);
                            t.setName(req.name().trim());
                            t.setColor(req.color());
                            t.setCreatedAt(Instant.now());
                            return tagRepo.save(t);
                        }));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<String>> delete(@PathVariable UUID id) {
        return currentUserId().flatMap(uid ->
                tagRepo.findByIdAndOwnerId(id, uid)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Tag not found")))
                        .flatMap(t -> tagRepo.deleteById(t.getId()))
                        .thenReturn(ResponseEntity.ok("Tag deleted")));
    }

    /** Attach tag to file. Both must belong to current user. */
    @PostMapping("/{tagId}/files/{fileId}")
    public Mono<ResponseEntity<String>> attach(
            @PathVariable UUID tagId, @PathVariable UUID fileId) {
        return currentUserId().flatMap(uid -> assertOwnership(uid, tagId, fileId)
                .then(databaseClient.sql(
                        "INSERT INTO file_tags(file_id, tag_id) VALUES (:f, :t) " +
                        "ON CONFLICT DO NOTHING")
                        .bind("f", fileId)
                        .bind("t", tagId)
                        .fetch().rowsUpdated()))
                .thenReturn(ResponseEntity.ok("Tag attached"));
    }

    /** Detach tag from file. */
    @DeleteMapping("/{tagId}/files/{fileId}")
    public Mono<ResponseEntity<String>> detach(
            @PathVariable UUID tagId, @PathVariable UUID fileId) {
        return currentUserId().flatMap(uid -> assertOwnership(uid, tagId, fileId)
                .then(databaseClient.sql(
                        "DELETE FROM file_tags WHERE file_id = :f AND tag_id = :t")
                        .bind("f", fileId)
                        .bind("t", tagId)
                        .fetch().rowsUpdated()))
                .thenReturn(ResponseEntity.ok("Tag detached"));
    }

    /** List file IDs that carry a given tag. Used by the filter UI. */
    @GetMapping("/{tagId}/files")
    public Flux<FileMetaEntity> filesForTag(@PathVariable UUID tagId) {
        return currentUserId().flatMapMany(uid ->
                tagRepo.findByIdAndOwnerId(tagId, uid)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Tag not found")))
                        .flatMapMany(t -> databaseClient.sql(
                                "SELECT m.id, m.filename, m.content_type, m.size, m.storage_path, " +
                                "       m.uploaded_at, m.owner_id, m.folder_id, m.deleted_at, m.updated_at " +
                                "FROM metadata m JOIN file_tags ft ON ft.file_id = m.id " +
                                "WHERE ft.tag_id = :t AND m.owner_id = :u AND m.deleted_at IS NULL " +
                                "ORDER BY m.filename")
                                .bind("t", tagId)
                                .bind("u", uid)
                                .map(row -> {
                                    FileMetaEntity f = new FileMetaEntity();
                                    f.setId(row.get("id", UUID.class));
                                    f.setFilename(row.get("filename", String.class));
                                    f.setContentType(row.get("content_type", String.class));
                                    f.setSize(row.get("size", Long.class));
                                    f.setStoragePath(row.get("storage_path", String.class));
                                    f.setUploadedAt(row.get("uploaded_at", Instant.class));
                                    f.setOwnerId(row.get("owner_id", UUID.class));
                                    f.setFolderId(row.get("folder_id", UUID.class));
                                    f.setDeletedAt(row.get("deleted_at", Instant.class));
                                    f.setUpdatedAt(row.get("updated_at", Instant.class));
                                    return f;
                                })
                                .all()));
    }

    /** List tag IDs attached to a file. */
    @GetMapping("/files/{fileId}")
    public Flux<TagEntity> tagsForFile(@PathVariable UUID fileId) {
        return currentUserId().flatMapMany(uid ->
                fileMetaRepo.findByIdAndOwnerId(fileId, uid)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found")))
                        .flatMapMany(f -> databaseClient.sql(
                                "SELECT t.id, t.owner_id, t.name, t.color, t.created_at " +
                                "FROM tags t JOIN file_tags ft ON ft.tag_id = t.id " +
                                "WHERE ft.file_id = :f AND t.owner_id = :u")
                                .bind("f", fileId)
                                .bind("u", uid)
                                .map(row -> {
                                    TagEntity t = new TagEntity();
                                    t.setId(row.get("id", UUID.class));
                                    t.setOwnerId(row.get("owner_id", UUID.class));
                                    t.setName(row.get("name", String.class));
                                    t.setColor(row.get("color", String.class));
                                    t.setCreatedAt(row.get("created_at", Instant.class));
                                    return t;
                                })
                                .all()));
    }

    private Mono<Void> assertOwnership(UUID uid, UUID tagId, UUID fileId) {
        Mono<TagEntity> tag = tagRepo.findByIdAndOwnerId(tagId, uid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found")));
        Mono<FileMetaEntity> file = fileMetaRepo.findByIdAndOwnerId(fileId, uid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")));
        return Mono.zip(tag, file).then();
    }

    public record CreateTagRequest(String name, String color) {}
}
