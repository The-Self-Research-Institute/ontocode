package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.model.Workspace.WorkspaceMember;
import self.research.ontology.auth.model.Workspace.WorkspaceRole;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import self.research.ontology.auth.model.PlanFeatureConfig;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final PlanFeatureConfigService planFeatureConfigService;
    private final SystemSettingsService systemSettingsService;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                           UserRepository userRepository,
                           ProjectRepository projectRepository,
                           FileMetadataRepository fileMetadataRepository,
                           PlanFeatureConfigService planFeatureConfigService,
                           SystemSettingsService systemSettingsService) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.fileMetadataRepository = fileMetadataRepository;
        this.planFeatureConfigService = planFeatureConfigService;
        this.systemSettingsService = systemSettingsService;
    }

    /**
     * Create a new workspace for a user
     */
    @Transactional
    public Workspace createWorkspace(String userId, String name, String description) {
        // Validate inputs
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name is required");
        }
        
        if (name.trim().length() > 255) {
            throw new IllegalArgumentException("Workspace name cannot exceed 255 characters");
        }
        
        // XSS Prevention
        if (name.contains("<") || name.contains(">")) {
            throw new IllegalArgumentException("Workspace name cannot contain < or > characters");
        }
        
        if (description != null && description.length() > 1000) {
            throw new IllegalArgumentException("Description cannot exceed 1000 characters");
        }
        
        if (description != null && (description.contains("<") || description.contains(">"))) {
            throw new IllegalArgumentException("Description cannot contain < or > characters");
        }
        
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("User not found");
        }

        User user = userOpt.get();
        
        // Trim whitespace
        name = name.trim();
        if (description != null) {
            description = description.trim();
        }
        
        // Generate unique workspace ID
        String workspaceId = generateWorkspaceId(name);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setOwnerId(userId);
        workspace.setName(name);
        workspace.setDescription(description);

        // Add owner as first member
        workspace.addMember(userId, user.getUsername(), user.getEmail(), WorkspaceRole.OWNER);

        // Resolve owner's current plan and standing (Model C: Inherit from account)
        String ownerPlan = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName().toUpperCase() : "FREE";
        // Enterprise domain bypass overrides FREE — the user's DB plan may not yet reflect the bypass
        if ("FREE".equals(ownerPlan) && systemSettingsService.isEnterpriseDomain(user.getEmail())) {
            ownerPlan = "ENTERPRISE";
            log.info("Enterprise domain bypass: creating workspace as ENTERPRISE for {}", user.getEmail());
        }
        String status = user.getSubscriptionStatus() != null ? user.getSubscriptionStatus().toLowerCase() : "active";
        
        boolean isPaidPlan = "PRO".equals(ownerPlan) || "ENTERPRISE".equals(ownerPlan);
        boolean isStatusGood = "active".equals(status) || "trialing".equals(status);
        boolean collaborationEnabled = isPaidPlan && isStatusGood;
        
        workspace.setSubscriptionPlan(ownerPlan);
        workspace.setBillingStatus(status.toUpperCase());
        workspace.setBillingInterval(user.getBillingInterval() != null ? user.getBillingInterval() : "monthly");
        workspace.setStripeSubscriptionId(user.getStripeSubscriptionId());
        workspace.setSubscriptionCurrentPeriodEnd(user.getSubscriptionCurrentPeriodEnd());
        
        // Limits based on plan
        if ("PRO".equals(ownerPlan)) {
            workspace.setMaxMembers(10);
        } else if ("ENTERPRISE".equals(ownerPlan)) {
            workspace.setMaxMembers(Integer.MAX_VALUE);
        } else {
            workspace.setMaxMembers(3);
        }
        workspace.setCollaborationEnabled(collaborationEnabled);
        workspace.setSubscriptionStartDate(LocalDateTime.now());

        return workspaceRepository.save(workspace);
    }

    /**
     * Synchronize all workspaces owned by a user with their current account plan details.
     * This follows "Model B" (Account-Level Billing) where the user's account status is the 
     * source of truth and workspaces dynamically inherit premium features.
     */
    public void syncWorkspacesToOwnerPlan(User owner) {
        if (owner == null) return;
        
        String planName = owner.getSubscriptionPlanName() != null ? owner.getSubscriptionPlanName().toUpperCase() : "FREE";
        String status = owner.getSubscriptionStatus() != null ? owner.getSubscriptionStatus().toLowerCase() : "active";
        
        // Collaboration is only enabled for paid plans that are in good standing
        boolean isPaidPlan = "PRO".equals(planName) || "ENTERPRISE".equals(planName);
        boolean isStatusGood = "active".equals(status) || "trialing".equals(status);
        boolean collaborationEnabled = isPaidPlan && isStatusGood;
        
        Map<String, PlanFeatureConfig> configs = planFeatureConfigService.getAllByPlanId();
        PlanFeatureConfig currentPlanConfig = configs.get(planName);
        int maxMembers = currentPlanConfig != null ? currentPlanConfig.getMaxMembers() : 3;
        
        workspaceRepository.findByOwnerId(owner.getId()).forEach(workspace -> {
            boolean dirty = false;
            
            if (!planName.equals(workspace.getSubscriptionPlan())) { 
                workspace.setSubscriptionPlan(planName); dirty = true; 
            }
            if (!status.equalsIgnoreCase(workspace.getBillingStatus())) { 
                workspace.setBillingStatus(status.toUpperCase()); dirty = true; 
            }
            if (!Objects.equals(owner.getBillingInterval(), workspace.getBillingInterval())) { 
                workspace.setBillingInterval(owner.getBillingInterval()); dirty = true; 
            }
            if (!Objects.equals(owner.getStripeSubscriptionId(), workspace.getStripeSubscriptionId())) { 
                workspace.setStripeSubscriptionId(owner.getStripeSubscriptionId()); dirty = true; 
            }
            if (!Objects.equals(owner.getSubscriptionCurrentPeriodEnd(), workspace.getSubscriptionCurrentPeriodEnd())) { 
                workspace.setSubscriptionCurrentPeriodEnd(owner.getSubscriptionCurrentPeriodEnd()); dirty = true; 
            }
            if (collaborationEnabled != workspace.isCollaborationEnabled()) { 
                workspace.setCollaborationEnabled(collaborationEnabled); dirty = true; 
            }
            if (workspace.getMaxMembers() == null || workspace.getMaxMembers() != maxMembers) { 
                workspace.setMaxMembers(maxMembers); dirty = true; 
            }
            
            if (dirty) {
                workspaceRepository.save(workspace);
                log.info("Workspace {} was out of sync — updated to match owner plan {} ({})", 
                    workspace.getWorkspaceId(), planName, status);
            }
        });
    }

    /**
     * Get all active workspaces for a user (owned or member) - excludes soft-deleted ones
     */
    public List<Workspace> getUserWorkspaces(String userId) {
        // Primary path: workspaces where this user is already linked by member.userId
        List<Workspace> workspaces = new ArrayList<>(workspaceRepository.findAllActiveUserWorkspaces(userId));

        // Self-heal: some legacy/broken rows may have members.email populated but missing members.userId,
        // which prevents the workspace from showing up for the member. We attempt to link the userId.
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return workspaces;
        }

        User user = userOpt.get();
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            return workspaces;
        }

        List<Workspace> byEmail = workspaceRepository.findActiveByMemberEmail(email);
        for (Workspace ws : byEmail) {
            boolean changed = linkMemberIdentityIfSafe(ws, email, user.getId(), user.getUsername());
            if (changed) {
                workspaceRepository.save(ws);
            }

            boolean alreadyIncluded = workspaces.stream().anyMatch(w -> Objects.equals(w.getWorkspaceId(), ws.getWorkspaceId()));
            if (!alreadyIncluded) {
                workspaces.add(ws);
            }
        }

        return workspaces;
    }

    /**
     * Link a member entry that matches the user's email to a concrete userId.
     *
     * Safety rules:
     * - Only link when the workspace member row has no userId yet, AND
     *   - it is already ACTIVE, OR
     *   - it has no invitationToken (meaning it's not an outstanding pending invite).
     *
     * This prevents granting access for truly pending invitations.
     */
    private boolean linkMemberIdentityIfSafe(Workspace ws, String email, String userId, String username) {
        if (ws == null || email == null || userId == null) return false;
        WorkspaceMember member = ws.getMemberByEmail(email);
        if (member == null) return false;

        if (member.getUserId() != null && !member.getUserId().isBlank()) {
            return false;
        }

        boolean isActive = member.getStatus() == Workspace.MemberStatus.ACTIVE;
        boolean hasNoInviteToken = member.getInvitationToken() == null || member.getInvitationToken().isBlank();
        if (!isActive && !hasNoInviteToken) {
            // Still pending with a token; do not auto-link.
            return false;
        }

        member.setUserId(userId);
        if (username != null && !username.isBlank()) {
            member.setUsername(username);
        }
        member.setStatus(Workspace.MemberStatus.ACTIVE);
        member.setInvitationToken(null);
        member.setJoinedAt(LocalDateTime.now());
        ws.setUpdatedAt(LocalDateTime.now());
        return true;
    }

    /**
     * Get workspaces owned by this user (excludes workspaces they are just members of).
     * Used for workspace creation limit checks — members-of do not count against the owner's quota.
     */
    public List<Workspace> getOwnedWorkspaces(String userId) {
        return workspaceRepository.findActiveByOwnerId(userId);
    }
    
    /**
     * Get all workspaces for a user including soft-deleted ones
     */
    public List<Workspace> getAllUserWorkspaces(String userId) {
        return workspaceRepository.findAllUserWorkspaces(userId);
    }
    
    /**
     * Get only soft-deleted workspaces for a user
     */
    public List<Workspace> getDeletedUserWorkspaces(String userId) {
        return workspaceRepository.findDeletedUserWorkspaces(userId);
    }

    /**
     * Get workspace by ID
     */
    public Optional<Workspace> getWorkspace(String workspaceId) {
        return workspaceRepository.findActiveByWorkspaceId(workspaceId);
    }
    
    /**
     * Get workspace by ID including soft-deleted ones
     */
    public Optional<Workspace> getWorkspaceIncludingDeleted(String workspaceId) {
        return workspaceRepository.findByWorkspaceId(workspaceId);
    }

    /**
     * Add a member to workspace
     */
    @Transactional
    public void addMember(String workspaceId, String userId, WorkspaceRole role) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        // Check member limit
        if (workspace.getMaxMembers() != null && 
            workspace.getMembers().size() >= workspace.getMaxMembers()) {
            throw new IllegalArgumentException("Workspace member limit reached for current subscription plan");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Check if already a member
        if (workspace.isMember(userId)) {
            throw new IllegalArgumentException("User is already a member of this workspace");
        }

        workspace.addMember(userId, user.getUsername(), user.getEmail(), role);
        workspaceRepository.save(workspace);
    }

    /**
     * Remove a member from workspace (by userId or email).
     * Also removes any WS_EDITOR_LINK_ADMIN project entries for the removed user
     * so they don't retain project access after being removed from the workspace.
     */
    @Transactional
    public void removeMember(String workspaceId, String memberIdentifier) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        // Can't remove owner
        if (workspace.getOwnerId().equals(memberIdentifier)) {
            throw new IllegalArgumentException("Cannot remove workspace owner");
        }

        // Capture userId before removal so we can clean up project editor links.
        String removedUserId = null;
        WorkspaceMember memberToRemove = workspace.getMember(memberIdentifier);
        if (memberToRemove != null) {
            removedUserId = memberToRemove.getUserId();
        } else {
            WorkspaceMember memberByEmail = workspace.getMemberByEmail(memberIdentifier);
            if (memberByEmail != null) {
                removedUserId = memberByEmail.getUserId();
            }
        }

        // Try to remove by userId first, then by email
        boolean removed = workspace.removeMemberByIdOrEmail(memberIdentifier);
        if (!removed) {
            throw new IllegalArgumentException("Member not found in workspace");
        }

        workspaceRepository.save(workspace);

        // Remove WS_EDITOR_LINK_ADMIN entries from projects in this workspace.
        // These entries are added automatically when the user is a workspace admin.
        // Without this cleanup the removed user retains project access via hasMember().
        if (removedUserId != null) {
            final String finalUserId = removedUserId;
            projectRepository.findByWorkspaceId(workspaceId).forEach(project -> {
                Project.ProjectMember pm = project.getMember(finalUserId);
                if (pm != null && Project.WS_EDITOR_LINK_ADMIN.equals(pm.getWorkspaceEditorLink())) {
                    project.removeMember(finalUserId);
                    projectRepository.save(project);
                    log.info("Removed WS_EDITOR_LINK_ADMIN entry for user {} from project {} after workspace removal",
                            finalUserId, project.getProjectId());
                }
            });
        }
    }

    /**
     * Syncs project memberships when a workspace member's role changes to or from ADMIN.
     *
     * - Promotion to ADMIN: backfills WS_EDITOR_LINK_ADMIN on all existing shared (non-private)
     *   projects so the newly promoted admin immediately has editor access everywhere they should.
     * - Demotion from ADMIN: removes WS_EDITOR_LINK_ADMIN entries from all projects so the
     *   former admin no longer has implicit project access.
     *
     * Private projects (single-member) are left untouched in both directions.
     */
    @Transactional
    public void syncAdminRoleChangeToProjects(Workspace workspace, String targetUserId,
                                              WorkspaceRole previousRole, WorkspaceRole newRole) {
        boolean promotedToAdmin  = newRole == WorkspaceRole.ADMIN && previousRole != WorkspaceRole.ADMIN;
        boolean demotedFromAdmin = previousRole == WorkspaceRole.ADMIN && newRole != WorkspaceRole.ADMIN;
        if (!promotedToAdmin && !demotedFromAdmin) return;

        List<Project> projects = projectRepository.findByWorkspaceId(workspace.getWorkspaceId());

        if (demotedFromAdmin) {
            projects.forEach(project -> {
                Project.ProjectMember pm = project.getMember(targetUserId);
                if (pm != null && Project.WS_EDITOR_LINK_ADMIN.equals(pm.getWorkspaceEditorLink())) {
                    project.removeMember(targetUserId);
                    projectRepository.save(project);
                    log.info("Removed WS_EDITOR_LINK_ADMIN for user {} from project {} after admin demotion",
                            targetUserId, project.getProjectId());
                }
            });
            return;
        }

        // Promoted to ADMIN — resolve display info for the new admin member record
        WorkspaceMember wm = workspace.getMember(targetUserId);
        String username = wm != null ? wm.getUsername() : null;
        String email    = wm != null ? wm.getEmail()    : null;
        if (username == null || email == null) {
            Optional<User> u = userRepository.findById(targetUserId);
            if (u.isPresent()) {
                if (username == null) username = u.get().getUsername();
                if (email    == null) email    = u.get().getEmail();
            }
        }
        if (username == null) username = "";
        if (email    == null) email    = "";

        final String finalUsername = username;
        final String finalEmail    = email;

        projects.stream()
                .filter(p -> p.getMembers().size() > 1 && !targetUserId.equals(p.getOwnerId()))
                .forEach(project -> {
                    Project.ProjectMember pm = project.getMember(targetUserId);
                    boolean dirty = false;
                    if (pm == null) {
                        project.addMember(targetUserId, finalUsername, finalEmail,
                                "EDITOR", Project.WS_EDITOR_LINK_ADMIN);
                        dirty = true;
                    } else {
                        // Only set link if not already protected by a higher-level link (OWNER)
                        if (pm.getWorkspaceEditorLink() == null
                                || Project.WS_EDITOR_LINK_ADMIN.equals(pm.getWorkspaceEditorLink())) {
                            if (!Project.WS_EDITOR_LINK_ADMIN.equals(pm.getWorkspaceEditorLink())) {
                                pm.setWorkspaceEditorLink(Project.WS_EDITOR_LINK_ADMIN);
                                dirty = true;
                            }
                        }
                        if ("VIEWER".equals(pm.getRole())) {
                            pm.setRole("EDITOR");
                            dirty = true;
                        }
                    }
                    if (dirty) {
                        project.setUpdatedAt(LocalDateTime.now());
                        projectRepository.save(project);
                        log.info("Applied WS_EDITOR_LINK_ADMIN for user {} on project {} after admin promotion",
                                targetUserId, project.getProjectId());
                    }
                });
    }

    /**
     * Update workspace details
     */
    @Transactional
    public Workspace updateWorkspace(String workspaceId, String name, String description) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        if (name != null && !name.isBlank()) {
            // Validate name
            name = name.trim();
            if (name.length() > 255) {
                throw new IllegalArgumentException("Workspace name cannot exceed 255 characters");
            }
            if (name.contains("<") || name.contains(">")) {
                throw new IllegalArgumentException("Workspace name cannot contain < or > characters");
            }
            workspace.setName(name);
        }
        
        if (description != null) {
            // Validate description
            description = description.trim();
            if (description.length() > 1000) {
                throw new IllegalArgumentException("Description cannot exceed 1000 characters");
            }
            if (description.contains("<") || description.contains(">")) {
                throw new IllegalArgumentException("Description cannot contain < or > characters");
            }
            workspace.setDescription(description);
        }

        workspace.setUpdatedAt(LocalDateTime.now());
        return workspaceRepository.save(workspace);
    }

    /**
     * Update workspace (full object)
     */
    @Transactional
    public Workspace updateWorkspace(Workspace workspace) {
        workspace.setUpdatedAt(LocalDateTime.now());
        return workspaceRepository.save(workspace);
    }

    /**
     * Check if user has access to workspace
     */
    public boolean hasAccess(String workspaceId, String userId) {
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        if (workspaceOpt.isEmpty()) return false;
        Workspace workspace = workspaceOpt.get();

        // Primary check: match by userId
        if (workspace.isMember(userId)) return true;

        // Fallback: match by email.
        // This covers cases where multiple users share the same display-name ("Soundhar")
        // causing findByUsername() to return the wrong User object. The member record may
        // have the correct email but the wrong userId stamped (a data-integrity issue
        // created during invitation acceptance). We also self-heal the record.
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            String email = userOpt.get().getEmail();
            if (email != null && workspace.isMemberByEmail(email)) {
                // Self-heal: fix the corrupted userId on the member record
                WorkspaceMember member = workspace.getMemberByEmail(email);
                if (member != null && !userId.equals(member.getUserId())) {
                    log.warn("[hasAccess] Self-healing member userId for email={} in workspace={}: {} -> {}",
                            email, workspaceId, member.getUserId(), userId);
                    member.setUserId(userId);
                    member.setUsername(userOpt.get().getUsername());
                    workspaceRepository.save(workspace);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Generate a unique workspace ID
     */
    private String generateWorkspaceId(String name) {
        String baseId = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        
        String workspaceId = baseId;
        int counter = 1;
        
        while (workspaceRepository.existsByWorkspaceId(workspaceId)) {
            workspaceId = baseId + "-" + counter;
            counter++;
        }
        
        return workspaceId;
    }

    /**
     * Get workspace member role
     */
    public WorkspaceRole getMemberRole(String workspaceId, String userId) {
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        if (workspaceOpt.isEmpty()) {
            return null;
        }

        Workspace workspace = workspaceOpt.get();
        WorkspaceMember member = workspace.getMember(userId);
        if (member != null) {
            return member.getRole();
        }

        // Fallback: try matching by email (handles corrupted userId from shared-username bug)
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            String email = userOpt.get().getEmail();
            if (email != null) {
                WorkspaceMember emailMember = workspace.getMemberByEmail(email);
                if (emailMember != null) {
                    return emailMember.getRole();
                }
            }
        }

        // Legacy workspaces may have the owner tracked only via ownerId, not in the members set
        if (userId != null && userId.equals(workspace.getOwnerId())) {
            return Workspace.WorkspaceRole.OWNER;
        }
        return null;
    }

    /**
     * Soft delete a workspace and cascade to all related projects and files
     */
    @Transactional
    public void deleteWorkspace(String workspaceId, String userId) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        // Soft delete the workspace
        workspace.setIsDeleted(true);
        workspace.setDeletedAt(LocalDateTime.now());
        workspace.setDeletedBy(userId);
        workspaceRepository.save(workspace);
        
        log.info("Soft deleted workspace: {} by user: {}", workspaceId, userId);
        
        // Cascade soft delete to all projects in this workspace
        List<Project> projects = projectRepository.findByWorkspaceId(workspaceId);
        for (Project project : projects) {
            if (!Boolean.TRUE.equals(project.getIsDeleted())) {
                project.setIsDeleted(true);
                project.setDeletedAt(LocalDateTime.now());
                project.setDeletedBy(userId);
                projectRepository.save(project);
                log.info("Cascade soft deleted project: {} in workspace: {}", project.getProjectId(), workspaceId);
            }
        }
        
        // Cascade soft delete to all files in this workspace
        List<FileMetadata> files = fileMetadataRepository.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE");
        for (FileMetadata file : files) {
            if (!Boolean.TRUE.equals(file.getIsDeleted())) {
                file.setIsDeleted(true);
                file.setDeletedAt(LocalDateTime.now());
                file.setDeletedBy(userId);
                fileMetadataRepository.save(file);
                log.info("Cascade soft deleted file: {} in workspace: {}", file.getFileName(), workspaceId);
            }
        }
    }
    
    /**
     * Restore a soft deleted workspace and optionally restore related projects and files
     */
    @Transactional
    public void restoreWorkspace(String workspaceId, boolean restoreProjects, boolean restoreFiles) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));
                
        if (!Boolean.TRUE.equals(workspace.getIsDeleted())) {
            throw new IllegalStateException("Workspace is not deleted");
        }

        // Restore the workspace
        workspace.setIsDeleted(false);
        workspace.setDeletedAt(null);
        workspace.setDeletedBy(null);
        workspaceRepository.save(workspace);
        
        log.info("Restored workspace: {}", workspaceId);
        
        if (restoreProjects) {
            // Restore all projects in this workspace
            List<Project> projects = projectRepository.findByWorkspaceId(workspaceId);
            for (Project project : projects) {
                if (Boolean.TRUE.equals(project.getIsDeleted())) {
                    project.setIsDeleted(false);
                    project.setDeletedAt(null);
                    project.setDeletedBy(null);
                    projectRepository.save(project);
                    log.info("Restored project: {} in workspace: {}", project.getProjectId(), workspaceId);
                }
            }
        }
        
        if (restoreFiles) {
            // Restore all files in this workspace
            List<FileMetadata> files = fileMetadataRepository.findByWorkspaceId(workspaceId);
            for (FileMetadata file : files) {
                if (Boolean.TRUE.equals(file.getIsDeleted())) {
                    file.setIsDeleted(false);
                    file.setDeletedAt(null);
                    file.setDeletedBy(null);
                    fileMetadataRepository.save(file);
                    log.info("Restored file: {} in workspace: {}", file.getFileName(), workspaceId);
                }
            }
        }
    }
}
