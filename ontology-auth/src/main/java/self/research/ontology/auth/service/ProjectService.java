package self.research.ontology.auth.service;

import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final WorkspaceRepository workspaceRepository;

    public ProjectService(ProjectRepository projectRepository, WorkspaceRepository workspaceRepository) {
        this.projectRepository = projectRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Create a new project within a workspace
     */
    public Project createProject(String workspaceId, String userId, String username, String email, String name, String description) {
        // Validate inputs
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name is required");
        }
        
        name = name.trim();
        
        if (name.length() > 255) {
            throw new IllegalArgumentException("Project name cannot exceed 255 characters");
        }
        
        // XSS Prevention
        if (name.contains("<") || name.contains(">")) {
            throw new IllegalArgumentException("Project name cannot contain < or > characters");
        }
        
        // Path traversal prevention
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Project name cannot contain path traversal characters");
        }
        
        // Validate special characters that are filesystem-unsafe
        if (name.matches(".*[<>/\\\\:*?\"'|].*")) {
            throw new IllegalArgumentException("Project name contains invalid characters");
        }
        
        if (description != null) {
            description = description.trim();
            if (description.length() > 1000) {
                throw new IllegalArgumentException("Description cannot exceed 1000 characters");
            }
            if (description.contains("<") || description.contains(">")) {
                throw new IllegalArgumentException("Description cannot contain < or > characters");
            }
        }
        
        Workspace workspace = getWorkspaceForUsage(workspaceId);
        
        // Check if user has access to workspace
        if (!workspace.isMember(userId)) {
            throw new SecurityException("User does not have access to this workspace");
        }

        // Create project
        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setName(name);
        project.setDescription(description);
        project.setWorkspaceId(workspaceId);
        project.setOwnerId(userId);
        project.setStatus("ACTIVE"); // Set initial status
        
        // Add creator as owner
        project.addMember(userId, username, email, "OWNER");
        
        return projectRepository.save(project);
    }

    /**
     * Get all projects in a workspace
     */
    public List<Project> getWorkspaceProjects(String workspaceId) {
        getWorkspaceForUsage(workspaceId);
        return projectRepository.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE");
    }

    /**
     * Get all projects for a user (across all workspaces)
     */
    public List<Project> getUserProjects(String userId) {
        return projectRepository.findByMembers_UserId(userId)
            .stream()
            .filter(p -> "ACTIVE".equals(p.getStatus()))
            .filter(p -> isWorkspaceAccessibleForUsage(p.getWorkspaceId()))
            .collect(Collectors.toList());
    }

    /**
     * Get all projects for a user in a specific workspace
     */
    public List<Project> getUserProjectsInWorkspace(String userId, String workspaceId) {
        getWorkspaceForUsage(workspaceId);
        return projectRepository.findByMembers_UserId(userId)
            .stream()
            .filter(p -> "ACTIVE".equals(p.getStatus()))
            .filter(p -> workspaceId.equals(p.getWorkspaceId()))
            .collect(Collectors.toList());
    }

    /**
     * Get a specific project
     */
    public Optional<Project> getProject(String projectId) {
        Optional<Project> projectOpt = projectRepository.findActiveByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            return Optional.empty();
        }
        return isWorkspaceAccessibleForUsage(projectOpt.get().getWorkspaceId()) ? projectOpt : Optional.empty();
    }

    /**
     * Get a workspace by ID
     */
    public Optional<Workspace> getWorkspace(String workspaceId) {
        return workspaceRepository.findByWorkspaceId(workspaceId);
    }

    /**
     * Update project details
     */
    public Project updateProject(String projectId, String userId, String name, String description) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
        // Check permissions
        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("User does not have permission to edit this project");
        }
        
        // Validate name if provided
        if (name != null && !name.isBlank()) {
            name = name.trim();
            
            if (name.length() > 255) {
                throw new IllegalArgumentException("Project name cannot exceed 255 characters");
            }
            
            if (name.contains("<") || name.contains(">")) {
                throw new IllegalArgumentException("Project name cannot contain < or > characters");
            }
            
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                throw new IllegalArgumentException("Project name cannot contain path traversal characters");
            }
            
            if (name.matches(".*[<>/\\\\:*?\"'|].*")) {
                throw new IllegalArgumentException("Project name contains invalid characters");
            }
            
            project.setName(name);
        }
        
        // Validate description if provided
        if (description != null) {
            description = description.trim();
            
            if (description.length() > 1000) {
                throw new IllegalArgumentException("Description cannot exceed 1000 characters");
            }
            
            if (description.contains("<") || description.contains(">")) {
                throw new IllegalArgumentException("Description cannot contain < or > characters");
            }
            
            project.setDescription(description);
        }
        
        if (name != null) project.setName(name);
        if (description != null) project.setDescription(description);
        
        return projectRepository.save(project);
    }

    /**
     * Add a member to a project
     */
    public Project addMember(String projectId, String userId, String targetUserId, String targetUsername, String targetEmail, String role) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
        // Check permissions (only OWNER or WORKSPACE OWNER can add members)
        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or workspace owner can add members");
        }
        
        if (project.hasMember(targetUserId)) {
            throw new IllegalArgumentException("User is already a member");
        }
        
        project.addMember(targetUserId, targetUsername, targetEmail, role);
        return projectRepository.save(project);
    }

    /**
     * Update a member's role in a project
     */
    public Project updateMemberRole(String projectId, String userId, String targetUserId, String newRole) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        // Only project owner or workspace owner can update roles
        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or workspace owner can update member roles");
        }

        // Cannot change the owner's role
        if (project.getOwnerId().equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot change the project owner's role");
        }

        // Validate role
        if (!List.of("ADMIN", "EDITOR", "VIEWER").contains(newRole)) {
            throw new IllegalArgumentException("Invalid role. Must be ADMIN, EDITOR, or VIEWER");
        }

        Project.ProjectMember member = project.getMember(targetUserId);
        if (member == null) {
            throw new IllegalArgumentException("User is not a member of this project");
        }

        member.setRole(newRole);
        return projectRepository.save(project);
    }

    /**
     * Remove a member from a project
     */
    public Project removeMember(String projectId, String userId, String targetUserId) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
        // Check permissions
        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or workspace owner can remove members");
        }
        
        // Cannot remove owner
        if (project.getOwnerId().equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot remove project owner");
        }
        
        project.removeMember(targetUserId);
        return projectRepository.save(project);
    }

    /**
     * Archive a project
     */
    public void archiveProject(String projectId, String userId) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
        // Check permissions
        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or workspace owner can archive the project");
        }
        
        project.setStatus("ARCHIVED");
        projectRepository.save(project);
    }

    /**
     * Soft delete a project and cascade to all related files
     */
    public void deleteProject(String projectId, String userId) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
        // Check permissions
        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or workspace owner can delete the project");
        }
        
        // Soft delete the project
        project.setIsDeleted(true);
        project.setDeletedAt(LocalDateTime.now());
        project.setDeletedBy(userId);
        project.setStatus("DELETED");
        projectRepository.save(project);
        
        // Cascade soft delete to all files in this project
        for (Project.FileMetadataInfo fileInfo : project.getFiles()) {
            if (!"DELETED".equals(fileInfo.getStatus())) {
                fileInfo.setStatus("DELETED");
            }
        }
        projectRepository.save(project);
    }
    
    /**
     * Restore a soft deleted project and optionally restore files
     */
    public void restoreProject(String projectId, String userId, boolean restoreFiles) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
        // Check permissions
        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or workspace owner can restore the project");
        }
        
        if (!Boolean.TRUE.equals(project.getIsDeleted())) {
            throw new IllegalStateException("Project is not deleted");
        }
        
        // Restore the project
        project.setIsDeleted(false);
        project.setDeletedAt(null);
        project.setDeletedBy(null);
        project.setStatus("ACTIVE");
        
        if (restoreFiles) {
            // Restore all files in this project
            for (Project.FileMetadataInfo fileInfo : project.getFiles()) {
                if ("DELETED".equals(fileInfo.getStatus())) {
                    fileInfo.setStatus("ACTIVE");
                }
            }
        }
        
        projectRepository.save(project);
    }

    /**
     * Check if user has access to a project
     */
    public boolean hasAccess(String projectId, String userId) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            return false;
        }
        Project project = projectOpt.get();
        // Check direct project membership first
        if (project.hasMember(userId)) {
            return true;
        }
        // Workspace owners and admins have access to all projects in their workspace
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(project.getWorkspaceId());
        if (workspaceOpt.isPresent()) {
            Workspace workspace = workspaceOpt.get();
            if (!canUseWorkspace(workspace)) {
                return false;
            }
            Workspace.WorkspaceMember wsMember = workspace.getMember(userId);
            if (wsMember != null) {
                Workspace.WorkspaceRole role = wsMember.getRole();
                return role == Workspace.WorkspaceRole.OWNER || role == Workspace.WorkspaceRole.ADMIN;
            }
        }
        return false;
    }

    /**
     * Check if user has edit permission
     */
    private boolean hasEditPermission(Project project, String userId) {
        if (project.getOwnerId().equals(userId)) {
            return true;
        }
        
        Project.ProjectMember member = project.getMember(userId);
        if (member != null) {
            String role = member.getRole();
            return "OWNER".equals(role) || "ADMIN".equals(role) || "EDITOR".equals(role);
        }
        
        // Workspace owners/admins also have edit permission
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(project.getWorkspaceId());
        if (workspaceOpt.isPresent()) {
            if (!canUseWorkspace(workspaceOpt.get())) {
                return false;
            }
            Workspace.WorkspaceMember wsMember = workspaceOpt.get().getMember(userId);
            if (wsMember != null) {
                Workspace.WorkspaceRole wsRole = wsMember.getRole();
                return wsRole == Workspace.WorkspaceRole.OWNER || wsRole == Workspace.WorkspaceRole.ADMIN;
            }
        }
        return false;
    }

    /**
     * Check if user is owner
     */
    private boolean isOwner(Project project, String userId) {
        return project.getOwnerId().equals(userId);
    }

    /**
     * Check if user is workspace owner
     */
    private boolean isWorkspaceOwner(String workspaceId, String userId) {
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        return workspaceOpt.isPresent()
                && canUseWorkspace(workspaceOpt.get())
                && workspaceOpt.get().getOwnerId().equals(userId);
    }

    /**
     * Check if user can manage project (owner or workspace owner)
     */
    private boolean canManageProject(Project project, String userId) {
        return isOwner(project, userId) || isWorkspaceOwner(project.getWorkspaceId(), userId);
    }

    /**
     * Generate unique project ID
     */
    private String generateProjectId() {
        String projectId;
        do {
            projectId = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        } while (projectRepository.existsByProjectId(projectId));
        return projectId;
    }

    /**
     * Get project by ID and verify user access
     */
    private Project getProjectById(String projectId, String userId) {
        Optional<Project> projectOpt = projectRepository.findActiveByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found or has been deleted");
        }
        
        Project project = projectOpt.get();
        if (!isWorkspaceAccessibleForUsage(project.getWorkspaceId())) {
            throw new SecurityException("Workspace payment is pending. Complete payment to continue.");
        }
        
        // Check if user has access to this project
        if (!project.hasMember(userId)) {
            throw new SecurityException("User does not have access to this project");
        }
        
        return project;
    }

    /**
     * Get all projects owned by a user
     */
    public List<Project> getProjectsByOwnerId(String userId) {
        return projectRepository.findByOwnerIdAndStatus(userId, "ACTIVE");
    }

    /**
     * Get all projects shared with a user (where user is a member but not owner)
     */
    public List<Project> getProjectsSharedWithUser(String userId) {
        return projectRepository.findByMembers_UserId(userId)
            .stream()
            .filter(p -> "ACTIVE".equals(p.getStatus()) && !userId.equals(p.getOwnerId()))
            .filter(p -> isWorkspaceAccessibleForUsage(p.getWorkspaceId()))
            .collect(Collectors.toList());
    }

    private Workspace getWorkspaceForUsage(String workspaceId) {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));
        requireWorkspaceUsable(workspace);
        return workspace;
    }

    private void requireWorkspaceUsable(Workspace workspace) {
        if (!canUseWorkspace(workspace)) {
            throw new SecurityException("Workspace payment is pending. Complete payment to continue.");
        }
    }

    private boolean isWorkspaceAccessibleForUsage(String workspaceId) {
        return workspaceRepository.findByWorkspaceId(workspaceId)
                .map(this::canUseWorkspace)
                .orElse(false);
    }

    private boolean canUseWorkspace(Workspace workspace) {
        String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";
        if ("FREE".equalsIgnoreCase(plan)) {
            return true;
        }
        String billingStatus = workspace.getBillingStatus();
        if (billingStatus == null || billingStatus.isBlank()) {
            return Boolean.TRUE.equals(workspace.getCollaborationEnabled());
        }
        return "ACTIVE".equalsIgnoreCase(billingStatus);
    }

    /**
     * Update a project (save changes)
     */
    public Project updateProject(Project project) {
        return projectRepository.save(project);
    }

    /**
     * Add a file to a project
     */
    public Project addFile(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);
        
        // Check if user has edit permission
        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to add files to this project");
        }
        
        // Add file to project (backward compatibility)
        if (!project.getFileIds().contains(fileId)) {
            project.getFileIds().add(fileId);
            project.setUpdatedAt(LocalDateTime.now());
            return projectRepository.save(project);
        }
        
        return project;
    }

    /**
     * Add file metadata to a project
     */
    public Project addFileMetadata(String projectId, String userId, Project.FileMetadataInfo fileMetadata) {
        Project project = getProjectById(projectId, userId);
        
        // Check if user has edit permission
        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to add files to this project");
        }
        
        // Add file metadata to project
        project.addFileMetadata(fileMetadata);
        return projectRepository.save(project);
    }

    /**
     * Soft delete a file from a project
     */
    public Project removeFile(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);
        
        // Check if user has edit permission
        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to remove files from this project");
        }
        
        // Editors can only delete their own files
        Project.ProjectMember member = project.getMember(userId);
        if (member != null && "EDITOR".equals(member.getRole()) && !project.getOwnerId().equals(userId)) {
            Project.FileMetadataInfo fileInfo = project.getFile(fileId);
            if (fileInfo != null && fileInfo.getUploadedBy() != null && !fileInfo.getUploadedBy().equals(userId)) {
                throw new SecurityException("Editors can only delete files they uploaded");
            }
        }
        
        // Mark file as deleted in project metadata
        Project.FileMetadataInfo fileInfo = project.getFile(fileId);
        if (fileInfo != null) {
            fileInfo.setStatus("DELETED");
        }
        
        // Remove file from project (backward compatibility)
        project.getFileIds().remove(fileId);
        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }
    
    /**
     * Restore a soft deleted file in a project
     */
    public Project restoreFile(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);
        
        // Check if user has edit permission
        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to restore files in this project");
        }
        
        // Restore file in project metadata
        Project.FileMetadataInfo fileInfo = project.getFile(fileId);
        if (fileInfo != null) {
            if (!"DELETED".equals(fileInfo.getStatus())) {
                throw new IllegalStateException("File is not deleted");
            }
            fileInfo.setStatus("ACTIVE");
        } else {
            throw new IllegalArgumentException("File not found in project");
        }
        
        // Add file back to fileIds (backward compatibility)
        if (!project.getFileIds().contains(fileId)) {
            project.getFileIds().add(fileId);
        }
        
        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }
    
    /**
     * Get file metadata from project
     */
    public Project.FileMetadataInfo getFileMetadata(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);
        return project.getFile(fileId);
    }
}
