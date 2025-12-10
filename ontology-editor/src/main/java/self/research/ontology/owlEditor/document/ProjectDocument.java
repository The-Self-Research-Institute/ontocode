package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "projects")
public class ProjectDocument {
    
    @Id
    private String id;
    private String name;
    private String filename;
    private String ownerEmail;
    private String gridfsFileId;  // GridFS file ID mapping
    private String status;
    private String statusMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> metadata;
    
    public ProjectDocument() {
    }
    
    public ProjectDocument(String id, String name, String filename) {
        this.id = id;
        this.name = name;
        this.filename = filename;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.status = "UPLOADED";
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getFilename() {
        return filename;
    }
    
    public void setFilename(String filename) {
        this.filename = filename;
    }
    
    public String getOwnerEmail() {
        return ownerEmail;
    }
    
    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }
    
    public String getGridfsFileId() {
        return gridfsFileId;
    }
    
    public void setGridfsFileId(String gridfsFileId) {
        this.gridfsFileId = gridfsFileId;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getStatusMessage() {
        return statusMessage;
    }
    
    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
