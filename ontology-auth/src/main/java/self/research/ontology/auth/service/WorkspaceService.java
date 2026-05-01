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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final FileMetadataRepository fileMetadataRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository, 
                           UserRepository userRepository,
                           ProjectRepository projectRepository,
                           FileMetadataRepository fileMetadataRepository) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.fileMetadataRepository = fileMetadataRepository;
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

        // Set default plan
        workspace.setSubscriptionPlan("FREE");
        workspace.setBillingStatus("ACTIVE");
        workspace.setMaxWorkspaces(3);
        workspace.setMaxMembers(10);
        workspace.setCollaborationEnabled(false);
        workspace.setSubscriptionStartDate(LocalDateTime.now());

        return workspaceRepository.save(workspace);
    }

    /**
     * Get all active workspaces for a user (owned or member) - excludes soft-deleted ones
     */
    public List<Workspace> getUserWorkspaces(String userId) {
        return workspaceRepository.findAllActiveUserWorkspaces(userId);
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
