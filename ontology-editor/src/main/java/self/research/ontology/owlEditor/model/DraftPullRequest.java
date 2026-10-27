package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "draft_pull_requests")
@CompoundIndexes({
    @CompoundIndex(name = "project_status_idx", def = "{'projectId': 1, 'status': 1}"),
    @CompoundIndex(name = "project_author_idx", def = "{'projectId': 1, 'authorId': 1}")
})
public class DraftPullRequest {

    public enum Status { OPEN, APPROVED, REJECTED, REVERTED }

    @Id
    private String id;

    private String projectId;
    private String authorId;
    private String authorUsername;
    private String title;
    private String description;
    private Status status;
    private int changeCount;
    private java.util.List<String> submittedChangeIds;
    private String snapshotKey;
    private java.util.List<String> publishedChangeSetIds;
    @org.springframework.data.annotation.Transient
    private Boolean draftChanged;
    @org.springframework.data.annotation.Transient
    private Integer currentChangeCount;
    @org.springframework.data.annotation.Transient
    private Boolean needsCopy;
    private Instant revertedAt;
    private String revertedBy;
    private String revertReason;
    private Instant restoredAt;
    private Instant revertStartedAt;

    private Instant createdAt;
    private Instant reviewedAt;
    private String reviewerId;
    private String reviewNote;

    public DraftPullRequest() {
        this.status = Status.OPEN;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public String getAuthorUsername() { return authorUsername; }
    public void setAuthorUsername(String authorUsername) { this.authorUsername = authorUsername; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getChangeCount() { return changeCount; }
    public void setChangeCount(int changeCount) { this.changeCount = changeCount; }

    public Boolean getDraftChanged() { return draftChanged; }
    public void setDraftChanged(Boolean draftChanged) { this.draftChanged = draftChanged; }

    public Boolean getNeedsCopy() { return needsCopy; }
    public void setNeedsCopy(Boolean needsCopy) { this.needsCopy = needsCopy; }
    public Integer getCurrentChangeCount() { return currentChangeCount; }
    public void setCurrentChangeCount(Integer currentChangeCount) { this.currentChangeCount = currentChangeCount; }

    public Instant getRevertedAt() { return revertedAt; }
    public void setRevertedAt(Instant revertedAt) { this.revertedAt = revertedAt; }

    public String getRevertReason() { return revertReason; }
    public void setRevertReason(String revertReason) { this.revertReason = revertReason; }

    public String getRevertedBy() { return revertedBy; }
    public void setRevertedBy(String revertedBy) { this.revertedBy = revertedBy; }

    public Instant getRevertStartedAt() { return revertStartedAt; }
    public void setRevertStartedAt(Instant revertStartedAt) { this.revertStartedAt = revertStartedAt; }
    public Instant getRestoredAt() { return restoredAt; }
    public void setRestoredAt(Instant restoredAt) { this.restoredAt = restoredAt; }

    public java.util.List<String> getPublishedChangeSetIds() { return publishedChangeSetIds; }
    public void setPublishedChangeSetIds(java.util.List<String> publishedChangeSetIds) { this.publishedChangeSetIds = publishedChangeSetIds; }

    public String getSnapshotKey() { return snapshotKey; }
    public void setSnapshotKey(String snapshotKey) { this.snapshotKey = snapshotKey; }

    public java.util.List<String> getSubmittedChangeIds() { return submittedChangeIds; }
    public void setSubmittedChangeIds(java.util.List<String> submittedChangeIds) { this.submittedChangeIds = submittedChangeIds; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewerId() { return reviewerId; }
    public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
