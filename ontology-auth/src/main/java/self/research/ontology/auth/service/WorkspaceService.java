package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.model.Workspace.WorkspaceMember;
import self.research.ontology.auth.model.Workspace.WorkspaceRole;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository, UserRepository userRepository) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
    }

    /**
     * Create a new workspace for a user
     */
    @Transactional
    public Workspace createWorkspace(String userId, String name, String description) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("User not found");
        }

        User user = userOpt.get();
        
        // Generate unique workspace ID
        String workspaceId = generateWorkspaceId(name);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setOwnerId(userId);
        workspace.setName(name);
        workspace.setDescription(description);

        // Add owner as first member
        workspace.addMember(userId, user.getUsername(), user.getEmail(), WorkspaceRole.OWNER);

        // Set default plan
        workspace.setSubscriptionPlan("FREE");
        workspace.setMaxWorkspaces(3);
        workspace.setMaxMembers(10);
        workspace.setCollaborationEnabled(false);
        workspace.setSubscriptionStartDate(LocalDateTime.now());

        return workspaceRepository.save(workspace);
    }

    /**
     * Get all workspaces for a user (owned or member)
     */
    public List<Workspace> getUserWorkspaces(String userId) {
        return workspaceRepository.findAllUserWorkspaces(userId);
    }

    /**
     * Get workspace by ID
     */
    public Optional<Workspace> getWorkspace(String workspaceId) {
        return workspaceRepository.findByWorkspaceId(workspaceId);
    }

    /**
     * Add a member to workspace
     */
    @Transactional
    public void addMember(String workspaceId, String userId, WorkspaceRole role) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        workspace.addMember(userId, user.getUsername(), user.getEmail(), role);
        workspaceRepository.save(workspace);
    }

    /**
     * Remove a member from workspace (by userId or email)
     */
    @Transactional
    public void removeMember(String workspaceId, String memberIdentifier) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        // Can't remove owner
        if (workspace.getOwnerId().equals(memberIdentifier)) {
            throw new IllegalArgumentException("Cannot remove workspace owner");
        }

        // Try to remove by userId first, then by email
        boolean removed = workspace.removeMemberByIdOrEmail(memberIdentifier);
        if (!removed) {
            throw new IllegalArgumentException("Member not found in workspace");
        }
        
        workspaceRepository.save(workspace);
    }

    /**
     * Update workspace details
     */
    @Transactional
    public Workspace updateWorkspace(String workspaceId, String name, String description) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        if (name != null && !name.isBlank()) {
            workspace.setName(name);
        }
        if (description != null) {
            workspace.setDescription(description);
        }

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
        Optional<Workspace> workspace = workspaceRepository.findByWorkspaceId(workspaceId);
        return workspace.map(w -> w.isMember(userId)).orElse(false);
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
        return member != null ? member.getRole() : null;
    }

    /**
     * Delete a workspace
     */
    @Transactional
    public void deleteWorkspace(String workspaceId) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));

        workspaceRepository.delete(workspace);
        log.info("Deleted workspace: {}", workspaceId);
    }
}
