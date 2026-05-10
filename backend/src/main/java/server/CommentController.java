package server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import server.repository.CommentRepo;
import server.repository.FileMetaRepo;

import java.time.Instant;
import java.util.UUID;

/**
 * FR#14 (comments) + NFT#6 (≤500 ms).
 *
 * Per-file comment stream. Owner-only access for now (cross-account
 * commenting comes with sharing/ACL — out of scope for this batch).
 * Author may delete own comment; file owner may delete any comment on
 * their file.
 */
@RestController
@Slf4j
@RequestMapping("/api/files/{fileId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentRepo commentRepo;
    private final FileMetaRepo fileMetaRepo;

    private Mono<UUID> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getPrincipal())
                .flatMap(p -> p instanceof UserEntity u
                        ? Mono.just(u.getId())
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "Admins cannot comment on user files")));
    }

    @GetMapping
    public Flux<CommentEntity> list(@PathVariable UUID fileId) {
        return currentUserId().flatMapMany(uid ->
                fileMetaRepo.findByIdAndOwnerId(fileId, uid)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found")))
                        .flatMapMany(f -> commentRepo.findByFileIdOrderByCreatedAtDesc(fileId)));
    }

    @PostMapping
    public Mono<CommentEntity> add(@PathVariable UUID fileId,
                                   @RequestBody AddCommentRequest req) {
        if (req == null || req.body() == null || req.body().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body required"));
        }
        if (req.body().length() > 5000) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body too long"));
        }
        return currentUserId().flatMap(uid ->
                fileMetaRepo.findByIdAndOwnerId(fileId, uid)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found")))
                        .flatMap(f -> {
                            CommentEntity c = new CommentEntity();
                            c.setFileId(fileId);
                            c.setAuthorId(uid);
                            c.setBody(req.body().trim());
                            c.setCreatedAt(Instant.now());
                            return commentRepo.save(c);
                        }));
    }

    @DeleteMapping("/{commentId}")
    public Mono<ResponseEntity<String>> delete(
            @PathVariable UUID fileId, @PathVariable UUID commentId) {
        return currentUserId().flatMap(uid ->
                commentRepo.findById(commentId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Comment not found")))
                        .flatMap(c -> {
                            if (!c.getFileId().equals(fileId)) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Comment not found on this file"));
                            }
                            // Author or file-owner may delete.
                            if (uid.equals(c.getAuthorId())) {
                                return commentRepo.deleteById(commentId)
                                        .thenReturn(ResponseEntity.ok("Deleted"));
                            }
                            return fileMetaRepo.findByIdAndOwnerId(fileId, uid)
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                                            HttpStatus.FORBIDDEN, "Not allowed")))
                                    .flatMap(f -> commentRepo.deleteById(commentId)
                                            .thenReturn(ResponseEntity.ok("Deleted")));
                        }));
    }

    public record AddCommentRequest(String body) {}
}
