package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "projects")
public class Project {

    public static final String WS_EDITOR_LINK_OWNER = "WORKSPACE_OWNER";

    public static final String WS_EDITOR_LINK_ADMIN = "WORKSPACE_ADMIN";

    @Id
    private String id;

    private String projectId;
    private String name;
    private String description;
    private String workspaceId;

    private String ownerId;
    private List<ProjectMember> members = new ArrayList<>();

    private String status;
    private List<String> tags = new ArrayList<>();

    private List<String> fileIds = new ArrayList<>();
    private List<FileMetadataInfo> files = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String visibility;

    private Boolean isDeleted = false;
    private LocalDateTime deletedAt;
    private String deletedBy;

    public static class FileMetadataInfo {
        private String fileId;
        private String fileName;
        private Long fileSize;
        private String fileType;
        private String extension;
        private String uploadedBy;
        private String uploaderUsername;
        private String uploaderEmail;
        private LocalDateTime uploadedAt;
        private String status;

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

    public static class ProjectMember {
        private String userId;
        private String username;
        private String email;
        private String role;
        private LocalDateTime joinedAt;

        private String workspaceEditorLink;

        public ProjectMember() {}

        public ProjectMember(String userId, String username, String email, String role) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.role = role;
            this.joinedAt = LocalDateTime.now();
        }

        public ProjectMember(String userId, String username, String email, String role, String workspaceEditorLink) {
            this(userId, username, email, role);
            this.workspaceEditorLink = workspaceEditorLink;
        }

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

        public String getWorkspaceEditorLink() { return workspaceEditorLink; }
        public void setWorkspaceEditorLink(String workspaceEditorLink) { this.workspaceEditorLink = workspaceEditorLink; }
    }

    public Project() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "ACTIVE";
    }

    public void addMember(String userId, String username, String email, String role) {
        addMember(userId, username, email, role, null);
    }

    public void addMember(String userId, String username, String email, String role, String workspaceEditorLink) {
        ProjectMember member = new ProjectMember(userId, username, email, role, workspaceEditorLink);
        this.members.add(member);
        this.updatedAt = LocalDateTime.now();
    }

    public void removeMember(String userId) {
        this.members.removeIf(m -> userId != null && userId.equals(m.getUserId()));
        this.updatedAt = LocalDateTime.now();
    }

    public boolean hasMember(String userId) {
        return this.members.stream().anyMatch(m -> userId != null && userId.equals(m.getUserId()));
    }

    public ProjectMember getMember(String userId) {
        return this.members.stream()
            .filter(m -> userId != null && userId.equals(m.getUserId()))
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

        if (!this.fileIds.contains(fileMetadata.getFileId())) {
            this.fileIds.add(fileMetadata.getFileId());
        }

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
            .filter(f -> {
                if (!f.getFileId().equals(fileId)) {
                    return false;
                }

                String status = f.getStatus();
                return !"DELETED".equals(status);
            })
            .findFirst()
            .orElse(null);
    }

    public List<FileMetadataInfo> getActiveFiles() {
        return this.files.stream()
            .filter(f -> {

                String status = f.getStatus();
                return status == null || status.isEmpty() || "ACTIVE".equals(status);
            })
            .toList();
    }

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

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
}
