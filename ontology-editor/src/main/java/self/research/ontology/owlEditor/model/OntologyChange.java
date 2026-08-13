package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Document(collection = "ontology_changes")
public class OntologyChange {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String userId;

    private String username;

    @Indexed
    private LocalDateTime timestamp;

    private ChangeType changeType;

    private String changeCategory;

    private String entityIRI;

    private String entityLabel;

    private String oldValue;
    private String newValue;

    private String axiomBefore;
    private String axiomAfter;

    private String description;

    private String comment;

    private Map<String, String> metadata = new HashMap<>();

    private boolean reverted = false;
    private String revertedBy;
    private LocalDateTime revertedAt;

    private String sessionId;
    private String ipAddress;

    public enum ChangeType {
        ADD_CLASS,
        REMOVE_CLASS,
        RENAME_CLASS,
        ADD_SUBCLASS,
        REMOVE_SUBCLASS,
         CLASS_CREATED,
        CLASS_DELETED,
        CLASS_MODIFIED,
        PROPERTY_CREATED,
        PROPERTY_DELETED,
        PROPERTY_MODIFIED,
        ANNOTATION_ADDED,
        ANNOTATION_DELETED,
        INDIVIDUAL_CREATED,
        INDIVIDUAL_DELETED,
        ADD_OBJECT_PROPERTY,
        REMOVE_OBJECT_PROPERTY,
        ADD_DATA_PROPERTY,
        REMOVE_DATA_PROPERTY,
        ADD_ANNOTATION_PROPERTY,
        REMOVE_ANNOTATION_PROPERTY,

        ADD_INDIVIDUAL,
        REMOVE_INDIVIDUAL,

        ADD_AXIOM,
        REMOVE_AXIOM,
        MODIFY_AXIOM,

        ADD_ANNOTATION,
        REMOVE_ANNOTATION,
        MODIFY_ANNOTATION,

        ADD_DOMAIN,
        REMOVE_DOMAIN,
        ADD_RANGE,
        REMOVE_RANGE,

        ADD_INVERSE,
        REMOVE_INVERSE,

        IMPORT_ONTOLOGY,
        REMOVE_IMPORT,

        OTHER
    }

    public OntologyChange() {
        this.timestamp = LocalDateTime.now();
    }

    public OntologyChange(String projectId, String userId, String username, ChangeType changeType) {
        this();
        this.projectId = projectId;
        this.userId = userId;
        this.username = username;
        this.changeType = changeType;
    }

    public static class Builder {
        private OntologyChange change;

        public Builder(String projectId, String userId, String username, ChangeType changeType) {
            change = new OntologyChange(projectId, userId, username, changeType);
        }

        public Builder changeCategory(String category) {
            change.changeCategory = category;
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

        public Builder axiomBefore(String axiom) {
            change.axiomBefore = axiom;
            return this;
        }

        public Builder axiomAfter(String axiom) {
            change.axiomAfter = axiom;
            return this;
        }

        public Builder description(String description) {
            change.description = description;
            return this;
        }

        public Builder comment(String comment) {
            change.comment = comment;
            return this;
        }

        public Builder sessionId(String sessionId) {
            change.sessionId = sessionId;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            change.ipAddress = ipAddress;
            return this;
        }

        public Builder metadata(String key, String value) {
            change.metadata.put(key, value);
            return this;
        }

        public OntologyChange build() {
            return change;
        }
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

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public String getChangeCategory() {
        return changeCategory;
    }

    public void setChangeCategory(String changeCategory) {
        this.changeCategory = changeCategory;
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

    public String getAxiomBefore() {
        return axiomBefore;
    }

    public void setAxiomBefore(String axiomBefore) {
        this.axiomBefore = axiomBefore;
    }

    public String getAxiomAfter() {
        return axiomAfter;
    }

    public void setAxiomAfter(String axiomAfter) {
        this.axiomAfter = axiomAfter;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public boolean isReverted() {
        return reverted;
    }

    public void setReverted(boolean reverted) {
        this.reverted = reverted;
    }

    public String getRevertedBy() {
        return revertedBy;
    }

    public void setRevertedBy(String revertedBy) {
        this.revertedBy = revertedBy;
    }

    public LocalDateTime getRevertedAt() {
        return revertedAt;
    }

    public void setRevertedAt(LocalDateTime revertedAt) {
        this.revertedAt = revertedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
}