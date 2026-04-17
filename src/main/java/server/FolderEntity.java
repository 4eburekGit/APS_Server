package server;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;
import java.util.UUID;

@Data
@Table("folders")
public class FolderEntity {
    @Id
    private UUID id;
    private String name;
    private UUID parentFolderId;   // null if in user's root folder
    private UUID ownerId;
    private Instant createdAt;
}
