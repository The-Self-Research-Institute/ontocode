package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Audit trail entry for a rollback action — either a whole-entry (primary)
 * rollback or a granular rollback of a single sub-change.
 */
@Document(collection = "rollback_audit")
public class RollbackAudit {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String historyChangeId;

    // Null when this audit entry is for the primary/whole-entry rollback.
    private String subChangeId;

    private String entityIRI;
    private String entityLabel;
    private String predicate;
    private String action;

    private String revertedByUserId;
    private String revertedByUsername;
    private LocalDateTime revertedAt;

    // True when a sub-change was reverted as a side effect of a primary rollback.
    private boolean cascaded = false;

    // Only populated on a primary-rollback audit doc: ids of sub-changes cascaded through.
    private List<String> cascadedSubChangeIds = new ArrayList<>();

    @Indexed
    private String changeSetId;
    private String direction = "UNDO";
    private List<String> changeIds = new ArrayList<>();
    private int skippedCount;

    public String getChangeSetId() { return changeSetId; }
    public void setChangeSetId(String changeSetId) { this.changeSetId = changeSetId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public List<String> getChangeIds() { return changeIds; }
    public void setChangeIds(List<String> changeIds) { this.changeIds = changeIds != null ? changeIds : new ArrayList<>(); }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }

    public RollbackAudit() {
        this.revertedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getHistoryChangeId() {
        return historyChangeId;
    }

    public void setHistoryChangeId(String historyChangeId) {
        this.historyChangeId = historyChangeId;
    }

    public String getSubChangeId() {
        return subChangeId;
    }

    public void setSubChangeId(String subChangeId) {
        this.subChangeId = subChangeId;
    }

    public String getEntityIRI() {
        return entityIRI;
    }

    public void setEntityIRI(String entityIRI) {
        this.entityIRI = entityIRI;
    }

    public String getEntityLabel() {
        return entityLabel;
    }

    public void setEntityLabel(String entityLabel) {
        this.entityLabel = entityLabel;
    }

    public String getPredicate() {
        return predicate;
    }

    public void setPredicate(String predicate) {
        this.predicate = predicate;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getRevertedByUserId() {
        return revertedByUserId;
    }

    public void setRevertedByUserId(String revertedByUserId) {
        this.revertedByUserId = revertedByUserId;
    }

    public String getRevertedByUsername() {
        return revertedByUsername;
    }

    public void setRevertedByUsername(String revertedByUsername) {
        this.revertedByUsername = revertedByUsername;
    }

    public LocalDateTime getRevertedAt() {
        return revertedAt;
    }

    public void setRevertedAt(LocalDateTime revertedAt) {
        this.revertedAt = revertedAt;
    }

    public boolean isCascaded() {
        return cascaded;
    }

    public void setCascaded(boolean cascaded) {
        this.cascaded = cascaded;
    }

    public List<String> getCascadedSubChangeIds() {
        return cascadedSubChangeIds;
    }

    public void setCascadedSubChangeIds(List<String> cascadedSubChangeIds) {
        this.cascadedSubChangeIds = cascadedSubChangeIds != null ? cascadedSubChangeIds : new ArrayList<>();
    }
}
