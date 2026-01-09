package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * FileMetadata entity - stores information about uploaded files
 */
@Document(collection = "file_metadata")
public class FileMetadata {

    @Id
    private String id;
    
    private String fileId; // Unique file identifier
    private String fileName;
    private String projectId;
    private String workspaceId;
    
    // File details
    private Long fileSize;
    private String fileType; // MIME type
    private String extension; // owl, rdf, ttl, n3
    private String base64Data; // Base64 encoded file content (temporary storage)
    private String storageLocation; // Future: path to file in storage system
    
    // Upload metadata
    private String uploadedBy; // User ID
    private String uploaderEmail;
    private String uploaderUsername;
    private LocalDateTime uploadedAt;
    
    // Status
    private String status; // ACTIVE, DELETED
    
    // Constructors
    public FileMetadata() {
        this.uploadedAt = LocalDateTime.now();
        this.status = "ACTIVE";
    }
    
    public FileMetadata(String fileId, String fileName, String projectId, String workspaceId) {
        this();
        this.fileId = fileId;
        this.fileName = fileName;
        this.projectId = projectId;
        this.workspaceId = workspaceId;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    
    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }
    
    public String getBase64Data() { return base64Data; }
    public void setBase64Data(String base64Data) { this.base64Data = base64Data; }
    
    public String getStorageLocation() { return storageLocation; }
    public void setStorageLocation(String storageLocation) { this.storageLocation = storageLocation; }
    
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    
    public String getUploaderEmail() { return uploaderEmail; }
    public void setUploaderEmail(String uploaderEmail) { this.uploaderEmail = uploaderEmail; }
    
    public String getUploaderUsername() { return uploaderUsername; }
    public void setUploaderUsername(String uploaderUsername) { this.uploaderUsername = uploaderUsername; }
    
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
