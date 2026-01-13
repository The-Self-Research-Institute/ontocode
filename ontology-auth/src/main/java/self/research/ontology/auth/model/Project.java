package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Project entity - represents a project within a workspace
 * Hierarchy: Workspace -> Project -> Files
 */
@Document(collection = "projects")
public class Project {

    @Id
    private String id;
    
    private String projectId; // Human-readable ID
    private String name;
    private String description;
    private String workspaceId; // Parent workspace
    
    // Owner and permissions
    private String ownerId;
    private List<ProjectMember> members = new ArrayList<>();
    
    // Project metadata
    private String status; // ACTIVE, ARCHIVED, DELETED
    private List<String> tags = new ArrayList<>();
    
    // File management
    private List<String> fileIds = new ArrayList<>(); // References to ontology files (deprecated - kept for backward compatibility)
    private List<FileMetadataInfo> files = new ArrayList<>(); // File metadata stored in project
    
    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Nested class for file metadata within project
    public static class FileMetadataInfo {
        private String fileId;
        private String fileName;
        private Long fileSize;
        private String fileType; // MIME type
        private String extension; // owl, rdf, ttl, n3
        private String uploadedBy; // User ID
        private String uploaderUsername;
        private String uploaderEmail;
        private LocalDateTime uploadedAt;
        private String status; // ACTIVE, DELETED
        
        // Constructors
        public FileMetadataInfo() {
            this.uploadedAt = LocalDateTime.now();
            this.status = "ACTIVE";
        }
        
        public FileMetadataInfo(String fileId, String fileName, Long fileSize, String fileType, String extension) {
            this();
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fileType = fileType;
            this.extension = extension;
        }
        
        // Getters and Setters
        public String getFileId() { return fileId; }
        public void setFileId(String fileId) { this.fileId = fileId; }
        
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        
        public Long getFileSize() { return fileSize; }
        public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
        
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        
        public String getExtension() { return extension; }
        public void setExtension(String extension) { this.extension = extension; }
        
        public String getUploadedBy() { return uploadedBy; }
        public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
        
        public String getUploaderUsername() { return uploaderUsername; }
        public void setUploaderUsername(String uploaderUsername) { this.uploaderUsername = uploaderUsername; }
        
        public String getUploaderEmail() { return uploaderEmail; }
        public void setUploaderEmail(String uploaderEmail) { this.uploaderEmail = uploaderEmail; }
        
        public LocalDateTime getUploadedAt() { return uploadedAt; }
        public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
    
    // Nested class for project members
    public static class ProjectMember {
        private String userId;
        private String username;
        private String email;
        private String role; // OWNER, EDITOR, VIEWER
        private LocalDateTime joinedAt;
        
        // Constructors
        public ProjectMember() {}
        
        public ProjectMember(String userId, String username, String email, String role) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.role = role;
            this.joinedAt = LocalDateTime.now();
        }
        
        // Getters and Setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        
        public LocalDateTime getJoinedAt() { return joinedAt; }
        public void setJoinedAt(LocalDateTime joinedAt) { this.joinedAt = joinedAt; }
    }
    
    // Constructors
    public Project() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "ACTIVE";
    }
    
    // Helper methods
    public void addMember(String userId, String username, String email, String role) {
        ProjectMember member = new ProjectMember(userId, username, email, role);
        this.members.add(member);
        this.updatedAt = LocalDateTime.now();
    }
    
    public void removeMember(String userId) {
        this.members.removeIf(m -> m.getUserId().equals(userId));
        this.updatedAt = LocalDateTime.now();
    }
    
    public boolean hasMember(String userId) {
        return this.members.stream().anyMatch(m -> m.getUserId().equals(userId));
    }
    
    public ProjectMember getMember(String userId) {
        return this.members.stream()
            .filter(m -> m.getUserId().equals(userId))
            .findFirst()
            .orElse(null);
    }
    
    public void addFile(String fileId) {
        if (!this.fileIds.contains(fileId)) {
            this.fileIds.add(fileId);
            this.updatedAt = LocalDateTime.now();
        }
    }
    
    public void addFileMetadata(FileMetadataInfo fileMetadata) {
        // Also add to fileIds for backward compatibility
        if (!this.fileIds.contains(fileMetadata.getFileId())) {
            this.fileIds.add(fileMetadata.getFileId());
        }
        // Remove any existing file with same ID
        this.files.removeIf(f -> f.getFileId().equals(fileMetadata.getFileId()));
        this.files.add(fileMetadata);
        this.updatedAt = LocalDateTime.now();
    }
    
    public void removeFile(String fileId) {
        this.fileIds.remove(fileId);
        this.files.removeIf(f -> f.getFileId().equals(fileId));
        this.updatedAt = LocalDateTime.now();
    }
    
    public FileMetadataInfo getFile(String fileId) {
        return this.files.stream()
            .filter(f -> f.getFileId().equals(fileId) && !"DELETED".equals(f.getStatus()))
            .findFirst()
            .orElse(null);
    }
    
    public List<FileMetadataInfo> getActiveFiles() {
        return this.files.stream()
            .filter(f -> "ACTIVE".equals(f.getStatus()))
            .toList();
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    
    public String getName() { return name; }
    public void setName(String name) { 
        this.name = name;
        this.updatedAt = LocalDateTime.now();
    }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { 
        this.description = description;
        this.updatedAt = LocalDateTime.now();
    }
    
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    
    public List<ProjectMember> getMembers() { return members; }
    public void setMembers(List<ProjectMember> members) { this.members = members; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { 
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
    
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    
    public List<String> getFileIds() { return fileIds; }
    public void setFileIds(List<String> fileIds) { this.fileIds = fileIds; }
    
    public List<FileMetadataInfo> getFiles() { return files; }
    public void setFiles(List<FileMetadataInfo> files) { this.files = files; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
