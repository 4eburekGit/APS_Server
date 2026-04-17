package server;

import lombok.Data;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;
import java.util.UUID;

@Data
@Table("metadata")
public class FileMetaEntity {
    @Id
    private UUID id;
    private String filename;
    private String contentType;
    private Long size;
    private String storagePath;
    private Instant uploadedAt;
    private UUID ownerId;   // owner UID
    private UUID folderId; // null if it's user's root folder
}