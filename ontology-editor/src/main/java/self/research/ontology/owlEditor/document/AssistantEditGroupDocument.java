package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "assistant_edit_group")
public class AssistantEditGroupDocument {

    @Id
    private String id;

    private String sessionId;
    private String projectId;
    private String userEmail;
    private String clientGroupId;
    private String targetPath;

    private List<EditEntry> edits;

    private AssistantEditGroupStatus status;

    private Long publicGraphVersionAtPropose;
    private Long appliedRevision;

    private String staleReason;

    private Instant createdAt;
    private Instant updatedAt;

    @Indexed(name = "expiresAt_ttl", expireAfterSeconds = 0)
    private Instant expiresAt;

    private Instant appliedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EditEntry {
        private long startLine;
        private int lineCount;
        private String originalText;
        private String newText;
        private int lineDelta;
    }

    public enum AssistantEditGroupStatus {
        PENDING, APPLIED, STALE, CONFLICT, VALIDATION_FAILED, DISCARDED
    }
}
