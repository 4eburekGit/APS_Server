package server;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Table("tags")
public class TagEntity {
    @Id
    private UUID id;
    private UUID ownerId;
    private String name;
    private String color;        // optional colour hex like "#A0C4FF"; nullable
    private Instant createdAt;
}
