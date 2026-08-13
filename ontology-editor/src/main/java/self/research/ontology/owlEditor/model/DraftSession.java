package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "draft_sessions")
@CompoundIndexes({
    @CompoundIndex(name = "project_user_unique", def = "{'projectId': 1, 'userId': 1}", unique = true)
})
public class DraftSession {

    @Id
    private String id;

    private String projectId;
    private String userId;

    private long baselineMainRevision;
    private long baselineMainTripleCount;
    private LocalDateTime baselineAt;

    private String baselineSnapshotPath;

    private DraftCopyStatus copyStatus;

    public DraftSession() {
        this.baselineAt = LocalDateTime.now();
    }

    public DraftSession(String projectId, String userId, long baselineMainRevision, long baselineMainTripleCount) {
        this();
        this.projectId = projectId;
        this.userId = userId;
        this.baselineMainRevision = baselineMainRevision;
        this.baselineMainTripleCount = baselineMainTripleCount;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public long getBaselineMainRevision() { return baselineMainRevision; }
    public void setBaselineMainRevision(long baselineMainRevision) { this.baselineMainRevision = baselineMainRevision; }

    public long getBaselineMainTripleCount() { return baselineMainTripleCount; }
    public void setBaselineMainTripleCount(long baselineMainTripleCount) { this.baselineMainTripleCount = baselineMainTripleCount; }

    public LocalDateTime getBaselineAt() { return baselineAt; }
    public void setBaselineAt(LocalDateTime baselineAt) { this.baselineAt = baselineAt; }

    public String getBaselineSnapshotPath() { return baselineSnapshotPath; }
    public void setBaselineSnapshotPath(String baselineSnapshotPath) { this.baselineSnapshotPath = baselineSnapshotPath; }

    public DraftCopyStatus getCopyStatus() { return copyStatus; }
    public void setCopyStatus(DraftCopyStatus copyStatus) { this.copyStatus = copyStatus; }
}
