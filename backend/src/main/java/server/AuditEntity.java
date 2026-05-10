package server;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Table("audit_log")
public class AuditEntity {
    @Id
    private UUID id;
    private UUID userId;
    private String action;        // login, upload, download, delete-file, delete-folder, ...
    private String targetType;    // "file" | "folder" | "user"
    private UUID targetId;
    private String ip;
    private Instant ts;
}
