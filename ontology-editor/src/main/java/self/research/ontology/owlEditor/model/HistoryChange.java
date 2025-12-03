package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a change synced from GraphDB history to MongoDB.
 * This enables full feature support (approve, reject, comments) for GraphDB changes.
 */
@Document(collection = "history_changes")
public class HistoryChange {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String editId; // The GraphDB edit IRI for deduplication

    @Indexed
    private String userId;
    
    private String username;

    @Indexed
    private LocalDateTime timestamp;

    private String operationType; // ADD, REMOVE, MODIFY
    
    private String entityType; // CLASS, PROPERTY, INDIVIDUAL, ANNOTATION, AXIOM
    
    private String entityIRI;
    
    private String entityLabel;
    
    // Change details from GraphDB
    private String oldValue;
    private String newValue;
    
    private String description;
    
    // Collaboration features
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String rejectedBy;
    private LocalDateTime rejectedAt;
    
    // Comments
    private Map<String, CommentEntry> comments = new HashMap<>();
    
    // Conflict resolution
    private boolean hasConflict = false;
    private String conflictResolution;
    private String resolvedBy;
    private LocalDateTime resolvedAt;
    
    // Metadata from GraphDB
    private Map<String, String> metadata = new HashMap<>();
    
    // Sync tracking
    private LocalDateTime syncedAt;
    
    public static class CommentEntry {
        private String userId;
        private String username;
        private String text;
        private LocalDateTime timestamp;
        
        public CommentEntry() {
            this.timestamp = LocalDateTime.now();
        }
        
        public CommentEntry(String userId, String username, String text) {
            this();
            this.userId = userId;
            this.username = username;
            this.text = text;
        }

        // Getters and Setters
        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }
    }

    // Constructors
    public HistoryChange() {
        this.timestamp = LocalDateTime.now();
        this.syncedAt = LocalDateTime.now();
    }

    public HistoryChange(String projectId, String editId, String userId, String username) {
        this();
        this.projectId = projectId;
        this.editId = editId;
        this.userId = userId;
        this.username = username;
    }

    // Builder pattern
    public static class Builder {
        private HistoryChange change;

        public Builder(String projectId, String editId, String userId, String username) {
            change = new HistoryChange(projectId, editId, userId, username);
        }

        public Builder operationType(String operationType) {
            change.operationType = operationType;
            return this;
        }

        public Builder entityType(String entityType) {
            change.entityType = entityType;
            return this;
        }

        public Builder entityIRI(String iri) {
            change.entityIRI = iri;
            return this;
        }

        public Builder entityLabel(String label) {
            change.entityLabel = label;
            return this;
        }

        public Builder oldValue(String oldValue) {
            change.oldValue = oldValue;
            return this;
        }

        public Builder newValue(String newValue) {
            change.newValue = newValue;
            return this;
        }

        public Builder description(String description) {
            change.description = description;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            change.timestamp = timestamp;
            return this;
        }

        public Builder metadata(String key, String value) {
            change.metadata.put(key, value);
            return this;
        }

        public HistoryChange build() {
            return change;
        }
    }

    // Getters and Setters
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

    public String getEditId() {
        return editId;
    }

    public void setEditId(String editId) {
        this.editId = editId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
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

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public void setRejectedBy(String rejectedBy) {
        this.rejectedBy = rejectedBy;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(LocalDateTime rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public Map<String, CommentEntry> getComments() {
        return comments;
    }

    public void setComments(Map<String, CommentEntry> comments) {
        this.comments = comments;
    }

    public boolean isHasConflict() {
        return hasConflict;
    }

    public void setHasConflict(boolean hasConflict) {
        this.hasConflict = hasConflict;
    }

    public String getConflictResolution() {
        return conflictResolution;
    }

    public void setConflictResolution(String conflictResolution) {
        this.conflictResolution = conflictResolution;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(LocalDateTime syncedAt) {
        this.syncedAt = syncedAt;
    }
}
