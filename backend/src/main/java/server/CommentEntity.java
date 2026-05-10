package server;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Table("comments")
public class CommentEntity {
    @Id
    private UUID id;
    private UUID fileId;
    private UUID authorId;
    private String body;
    private Instant createdAt;
    private Instant editedAt;
}
