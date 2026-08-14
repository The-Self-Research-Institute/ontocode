package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Represents a draft change in the ontology that hasn't been saved yet.
 * Draft changes are tracked separately from committed changes.
 */
@Document(collection = "draft_changes")
@CompoundIndexes({
    @CompoundIndex(name = "project_timestamp_idx", def = "{'projectId': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "project_user_idx", def = "{'projectId': 1, 'userId': 1}")
})
public class DraftChange {
    
    @Id
    private String id;
    
    private String projectId;
    private String userId;
    private String username;
    
    private String operationType; // createClass, deleteClass, addAnnotation, etc.
    private Map<String, Object> operationData; // The mutation operation details
    
    private LocalDateTime timestamp;
    private String sessionId;
    
    private boolean applied; // Whether this draft has been applied to GraphDB
    
    public DraftChange() {
        this.timestamp = LocalDateTime.now();
        this.applied = false;
    }
    
    public DraftChange(String projectId, String userId, String username, 
                      String operationType, Map<String, Object> operationData) {
        this();
        this.projectId = projectId;
        this.userId = userId;
        this.username = username;
        this.operationType = operationType;
        this.operationData = operationData;
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

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public Map<String, Object> getOperationData() {
        return operationData;
    }

    public void setOperationData(Map<String, Object> operationData) {
        this.operationData = operationData;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isApplied() {
        return applied;
    }

    public void setApplied(boolean applied) {
        this.applied = applied;
    }
}
