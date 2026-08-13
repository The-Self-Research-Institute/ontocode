package self.research.ontology.auth.dto;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.Length;

import java.util.List;

public class ProjectRequests {

    public static class CreateProjectRequest {

        @NotBlank(message = "Workspace ID is required")
        @Pattern(
            regexp = "^[a-zA-Z0-9_-]+$",
            message = "Invalid workspace ID format"
        )
        private String workspaceId;

        @NotBlank(message = "Project name is required")
        @Length(min = 1, max = 255, message = "Project name must be between 1 and 255 characters")
        @Pattern(
            regexp = "^[^<>/\\\\:*?\"'|]*$",
            message = "Project name cannot contain special characters: < > / \\ : * ? \" ' |"
        )
        private String name;

        @Length(max = 1000, message = "Description cannot exceed 1000 characters")
        @Pattern(
            regexp = "^[^<>]*$",
            message = "Description cannot contain < or > characters"
        )
        private String description;

        @Pattern(
            regexp = "^(all|specific)$",
            message = "shareWith must be 'all' or 'specific'"
        )
        private String shareWith;

        @Size(max = 100, message = "Cannot share with more than 100 members at once")
        private List<@Email(message = "Invalid email format") String> memberEmails;

        public String getWorkspaceId() {
            return workspaceId != null ? workspaceId.trim() : null;
        }

        public void setWorkspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        public String getName() {
            return name != null ? name.trim() : null;
        }

        public void setName(String name) {

            if (name != null && (name.contains("..") || name.contains("/") || name.contains("\\"))) {
                throw new IllegalArgumentException("Project name cannot contain path traversal characters");
            }
            this.name = name;
        }

        public String getDescription() {
            return description != null ? description.trim() : null;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getShareWith() {
            return shareWith;
        }

        public void setShareWith(String shareWith) {
            this.shareWith = shareWith;
        }

        public List<String> getMemberEmails() {
            return memberEmails;
        }

        public void setMemberEmails(List<String> memberEmails) {
            this.memberEmails = memberEmails;
        }
    }

    public static class UpdateProjectRequest {

        @Length(min = 1, max = 255, message = "Project name must be between 1 and 255 characters")
        @Pattern(
            regexp = "^[^<>/\\\\:*?\"'|]*$",
            message = "Project name cannot contain special characters"
        )
        private String name;

        @Length(max = 1000, message = "Description cannot exceed 1000 characters")
        @Pattern(
            regexp = "^[^<>]*$",
            message = "Description cannot contain < or > characters"
        )
        private String description;

        public String getName() {
            return name != null ? name.trim() : null;
        }

        public void setName(String name) {
            if (name != null && (name.contains("..") || name.contains("/") || name.contains("\\"))) {
                throw new IllegalArgumentException("Project name cannot contain path traversal characters");
            }
            this.name = name;
        }

        public String getDescription() {
            return description != null ? description.trim() : null;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class AddMemberRequest {

        @NotBlank(message = "Email is required")
        private String email;

        @NotBlank(message = "Role is required")
        @Pattern(
            regexp = "^(ADMIN|EDITOR|DRAFT_EDITOR|VIEWER)$",
            message = "Invalid role. Must be ADMIN, EDITOR, DRAFT_EDITOR, or VIEWER"
        )
        private String role;

        public String getEmail() {
            return email != null ? email.trim().toLowerCase() : null;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getRole() {
            return role != null ? role.toUpperCase() : null;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    public static class UploadFileRequest {

        @NotBlank(message = "File name is required")
        @Pattern(
            regexp = "^[^<>/\\\\:*?\"'|]+\\.(owl|rdf|ttl|n3|nt|jsonld)$",
            message = "Invalid file name or extension. Allowed: .owl, .rdf, .ttl, .n3, .nt, .jsonld"
        )
        private String fileName;

        @NotNull(message = "File content is required")
        @Size(min = 1, max = 10485760, message = "File size must be between 1 byte and 10MB")
        private byte[] fileContent;

        public String getFileName() {

            if (fileName != null && (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\"))) {
                throw new IllegalArgumentException("File name cannot contain path traversal characters");
            }
            return fileName != null ? fileName.trim() : null;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public byte[] getFileContent() {
            return fileContent;
        }

        public void setFileContent(byte[] fileContent) {
            this.fileContent = fileContent;
        }
    }
}
