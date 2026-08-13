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

    private Set<WorkspaceMember> members = new HashSet<>();

    private String subscriptionPlan;
    private String billingStatus = "ACTIVE";
    private String billingInterval = "monthly";
    private LocalDateTime subscriptionStartDate;
    private LocalDateTime subscriptionEndDate;
    private LocalDateTime subscriptionCurrentPeriodEnd;
    private Integer maxWorkspaces;
    private Integer maxMembers;
    private Boolean collaborationEnabled = false;

    private String stripeSubscriptionId;
    private String pendingCheckoutSessionId;
    private LocalDateTime pendingCheckoutCreatedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Boolean isDeleted = false;
    private LocalDateTime deletedAt;
    private String deletedBy;

    public Workspace() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.isDeleted = false;
    }

    public static class WorkspaceMember {
        private String userId;
        private String username;
        private String email;
        private WorkspaceRole role;
        private MemberStatus status;
        private String invitationToken;
        private LocalDateTime joinedAt;

        public WorkspaceMember() {
            this.joinedAt = LocalDateTime.now();
            this.status = MemberStatus.ACTIVE;
        }

        public WorkspaceMember(String userId, String username, String email, WorkspaceRole role) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.role = role;
            this.status = MemberStatus.ACTIVE;
            this.joinedAt = LocalDateTime.now();
        }

        public WorkspaceMember(String email, WorkspaceRole role, String invitationToken) {
            this.email = email;
            this.username = email.split("@")[0];
            this.role = role;
            this.status = MemberStatus.PENDING;
            this.invitationToken = invitationToken;
            this.joinedAt = LocalDateTime.now();
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public WorkspaceRole getRole() { return role; }
        public void setRole(WorkspaceRole role) { this.role = role; }

        public MemberStatus getStatus() { return status; }
        public void setStatus(MemberStatus status) { this.status = status; }

        public String getInvitationToken() { return invitationToken; }
        public void setInvitationToken(String invitationToken) { this.invitationToken = invitationToken; }

        public LocalDateTime getJoinedAt() { return joinedAt; }
        public void setJoinedAt(LocalDateTime joinedAt) { this.joinedAt = joinedAt; }
    }

    public enum MemberStatus {
        PENDING,
        ACTIVE
    }

    public enum WorkspaceRole {
        OWNER,
        ADMIN,
        MEMBER,
        VIEWER
    }

    public boolean isMember(String userId) {
        return members.stream().anyMatch(m -> m.getUserId() != null && m.getUserId().equals(userId));
    }

    public boolean isMemberByEmail(String email) {
        return members.stream().anyMatch(m -> m.getEmail() != null && m.getEmail().equalsIgnoreCase(email));
    }

    public boolean hasPendingInvitation(String email) {
        return members.stream().anyMatch(m ->
            m.getEmail() != null &&
            m.getEmail().equalsIgnoreCase(email) &&
            m.getStatus() == MemberStatus.PENDING
        );
    }

    public WorkspaceMember getMember(String userId) {
        return members.stream()
                .filter(m -> m.getUserId() != null && m.getUserId().equals(userId))
                .findFirst()
                .orElse(null);
    }

    public WorkspaceMember getMemberByEmail(String email) {
        return members.stream()
                .filter(m -> m.getEmail() != null && m.getEmail().equalsIgnoreCase(email))
                .findFirst()
                .orElse(null);
    }

    public WorkspaceMember getPendingMemberByToken(String token) {
        return members.stream()
                .filter(m -> token != null && token.equals(m.getInvitationToken()) && m.getStatus() == MemberStatus.PENDING)
                .findFirst()
                .orElse(null);
    }

    public void addMember(String userId, String username, String email, WorkspaceRole role) {

        WorkspaceMember existingMember = getMemberByEmail(email);
        if (existingMember != null) {
            existingMember.setUserId(userId);
            existingMember.setUsername(username);
            existingMember.setRole(role);
            existingMember.setStatus(MemberStatus.ACTIVE);
            existingMember.setInvitationToken(null);
            existingMember.setJoinedAt(java.time.LocalDateTime.now());
            this.updatedAt = java.time.LocalDateTime.now();
            return;
        }

        if (!isMember(userId)) {
            members.add(new WorkspaceMember(userId, username, email, role));
            this.updatedAt = java.time.LocalDateTime.now();
        }
    }

    public void addPendingMember(String email, WorkspaceRole role, String invitationToken) {
        members.removeIf(m -> m.getEmail() != null &&
                              m.getEmail().equalsIgnoreCase(email) &&
                              m.getStatus() == MemberStatus.PENDING);
        members.add(new WorkspaceMember(email, role, invitationToken));
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public void activatePendingMember(String email, String userId, String username) {
        WorkspaceMember member = getMemberByEmail(email);
        if (member != null && member.getStatus() == MemberStatus.PENDING) {
            member.setUserId(userId);
            member.setUsername(username);
            member.setStatus(MemberStatus.ACTIVE);
            member.setInvitationToken(null);
            member.setJoinedAt(java.time.LocalDateTime.now());
            this.updatedAt = java.time.LocalDateTime.now();
        }
    }

    public void cancelPendingMember(String invitationToken) {
        members.removeIf(m -> invitationToken != null &&
                              invitationToken.equals(m.getInvitationToken()) &&
                              m.getStatus() == MemberStatus.PENDING);
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public void removeMember(String userId) {
        members.removeIf(m -> m.getUserId() != null && m.getUserId().equals(userId));
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public void removeMemberByEmail(String email) {
        members.removeIf(m -> m.getEmail() != null && m.getEmail().equalsIgnoreCase(email));
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public boolean removeMemberByIdOrEmail(String identifier) {

        boolean removed = members.removeIf(m -> m.getUserId() != null && m.getUserId().equals(identifier));

        if (!removed) {

            String email = identifier.startsWith("pending-") ? identifier.substring(8) : identifier;
            removed = members.removeIf(m -> m.getEmail() != null && m.getEmail().equalsIgnoreCase(email));
        }

        if (removed) {
            this.updatedAt = java.time.LocalDateTime.now();
        }
        return removed;
    }

    public void updateMemberRole(String userId, WorkspaceRole role) {
        WorkspaceMember member = getMember(userId);
        if (member != null) {
            member.setRole(role);
            this.updatedAt = java.time.LocalDateTime.now();
        }
    }

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

    public String getBillingStatus() { return billingStatus; }
    public void setBillingStatus(String billingStatus) { this.billingStatus = billingStatus; }

    public String getBillingInterval() { return billingInterval; }
    public void setBillingInterval(String billingInterval) { this.billingInterval = billingInterval; }

    public LocalDateTime getSubscriptionStartDate() { return subscriptionStartDate; }
    public void setSubscriptionStartDate(LocalDateTime subscriptionStartDate) {
        this.subscriptionStartDate = subscriptionStartDate;
    }

    public LocalDateTime getSubscriptionEndDate() { return subscriptionEndDate; }
    public void setSubscriptionEndDate(LocalDateTime subscriptionEndDate) {
        this.subscriptionEndDate = subscriptionEndDate;
    }

    public LocalDateTime getSubscriptionCurrentPeriodEnd() { return subscriptionCurrentPeriodEnd; }
    public void setSubscriptionCurrentPeriodEnd(LocalDateTime subscriptionCurrentPeriodEnd) {
        this.subscriptionCurrentPeriodEnd = subscriptionCurrentPeriodEnd;
    }

    public Integer getMaxWorkspaces() { return maxWorkspaces; }
    public void setMaxWorkspaces(Integer maxWorkspaces) { this.maxWorkspaces = maxWorkspaces; }

    public Integer getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Integer maxMembers) { this.maxMembers = maxMembers; }

    public Boolean getCollaborationEnabled() { return collaborationEnabled; }
    public void setCollaborationEnabled(Boolean collaborationEnabled) {
        this.collaborationEnabled = collaborationEnabled;
    }

    public boolean isCollaborationEnabled() {
        return collaborationEnabled != null && collaborationEnabled;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public String getPendingCheckoutSessionId() { return pendingCheckoutSessionId; }
    public void setPendingCheckoutSessionId(String pendingCheckoutSessionId) { this.pendingCheckoutSessionId = pendingCheckoutSessionId; }

    public LocalDateTime getPendingCheckoutCreatedAt() { return pendingCheckoutCreatedAt; }
    public void setPendingCheckoutCreatedAt(LocalDateTime pendingCheckoutCreatedAt) { this.pendingCheckoutCreatedAt = pendingCheckoutCreatedAt; }
}
