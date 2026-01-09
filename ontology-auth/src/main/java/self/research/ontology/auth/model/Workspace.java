package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Document(collection = "workspaces")
public class Workspace {

    @Id
    private String id;

    @NotBlank(message = "Workspace ID is required")
    @Indexed(unique = true)
    private String workspaceId;

    @NotBlank(message = "Owner ID is required")
    private String ownerId;

    @NotBlank(message = "Workspace name is required")
    private String name;

    private String description;

    // Members with their roles
    private Set<WorkspaceMember> members = new HashSet<>();

    // Subscription plan information
    private String subscriptionPlan;
    private LocalDateTime subscriptionStartDate;
    private LocalDateTime subscriptionEndDate;
    private Integer maxWorkspaces;
    private Integer maxMembers;
    private Boolean collaborationEnabled = false;

    // Audit fields
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Workspace() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Inner class for workspace members
    public static class WorkspaceMember {
        private String userId;
        private String username;
        private String email;
        private WorkspaceRole role;
        private LocalDateTime joinedAt;

        public WorkspaceMember() {
            this.joinedAt = LocalDateTime.now();
        }

        public WorkspaceMember(String userId, String username, String email, WorkspaceRole role) {
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

        public WorkspaceRole getRole() { return role; }
        public void setRole(WorkspaceRole role) { this.role = role; }

        public LocalDateTime getJoinedAt() { return joinedAt; }
        public void setJoinedAt(LocalDateTime joinedAt) { this.joinedAt = joinedAt; }
    }

    // Workspace roles
    public enum WorkspaceRole {
        OWNER,
        ADMIN,
        MEMBER,
        VIEWER
    }

    // Helper methods
    public boolean isMember(String userId) {
        return members.stream().anyMatch(m -> m.getUserId().equals(userId));
    }

    public WorkspaceMember getMember(String userId) {
        return members.stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElse(null);
    }

    public void addMember(String userId, String username, String email, WorkspaceRole role) {
        if (!isMember(userId)) {
            members.add(new WorkspaceMember(userId, username, email, role));
            this.updatedAt = LocalDateTime.now();
        }
    }

    public void removeMember(String userId) {
        members.removeIf(m -> m.getUserId().equals(userId));
        this.updatedAt = LocalDateTime.now();
    }

    public void updateMemberRole(String userId, WorkspaceRole role) {
        WorkspaceMember member = getMember(userId);
        if (member != null) {
            member.setRole(role);
            this.updatedAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

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

    public Set<WorkspaceMember> getMembers() { return members; }
    public void setMembers(Set<WorkspaceMember> members) { this.members = members; }

    public String getSubscriptionPlan() { return subscriptionPlan; }
    public void setSubscriptionPlan(String subscriptionPlan) { this.subscriptionPlan = subscriptionPlan; }

    public LocalDateTime getSubscriptionStartDate() { return subscriptionStartDate; }
    public void setSubscriptionStartDate(LocalDateTime subscriptionStartDate) { 
        this.subscriptionStartDate = subscriptionStartDate; 
    }

    public LocalDateTime getSubscriptionEndDate() { return subscriptionEndDate; }
    public void setSubscriptionEndDate(LocalDateTime subscriptionEndDate) { 
        this.subscriptionEndDate = subscriptionEndDate; 
    }

    public Integer getMaxWorkspaces() { return maxWorkspaces; }
    public void setMaxWorkspaces(Integer maxWorkspaces) { this.maxWorkspaces = maxWorkspaces; }

    public Integer getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Integer maxMembers) { this.maxMembers = maxMembers; }

    public Boolean getCollaborationEnabled() { return collaborationEnabled; }
    public void setCollaborationEnabled(Boolean collaborationEnabled) { 
        this.collaborationEnabled = collaborationEnabled; 
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
