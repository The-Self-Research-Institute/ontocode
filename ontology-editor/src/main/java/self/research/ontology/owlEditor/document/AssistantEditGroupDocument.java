package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantEditGroupDocument {

    private String id;

    private String sessionId;
    private String projectId;
    private String userEmail;
    private String clientGroupId;
    private String targetPath;

    private boolean draft;
    private String draftUserId;

    private List<EditEntry> edits;

    private AssistantEditGroupStatus status;

    private Long publicGraphVersionAtPropose;
    private Long positionsVerifiedAtVersion;
    private Long appliedRevision;

    private String staleReason;

    private Instant createdAt;
    private Instant updatedAt;

    private Instant expiresAt;

    private Instant appliedAt;

    private ConsistencyCheckRecord consistencyCheck;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsistencyCheckRecord {
        private String jobId;
        private ConsistencyCheckState state;
        private String detail;
        private Instant resolvedAt;
    }

    public enum ConsistencyCheckState {
        PENDING, PASSED, FAILED, ERROR, TIMED_OUT
    }

    public enum AssistantEditGroupStatus {
        PENDING, APPLIED, STALE, CONFLICT, VALIDATION_FAILED, DISCARDED, RECOVERY_REQUIRED
    }
}
