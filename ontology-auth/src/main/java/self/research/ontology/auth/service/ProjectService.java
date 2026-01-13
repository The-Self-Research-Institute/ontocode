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
        // Verify workspace exists
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        if (workspaceOpt.isEmpty()) {
            throw new IllegalArgumentException("Workspace not found");
        }
        
        Workspace workspace = workspaceOpt.get();
        
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
        
        // Add creator as owner
        project.addMember(userId, username, email, "OWNER");
        
        return projectRepository.save(project);
    }

    /**
     * Get all projects in a workspace
     */
    public List<Project> getWorkspaceProjects(String workspaceId) {
        return projectRepository.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE");
    }

    /**
     * Get all projects for a user (across all workspaces)
     */
    public List<Project> getUserProjects(String userId) {
        return projectRepository.findByMembers_UserId(userId)
            .stream()
            .filter(p -> "ACTIVE".equals(p.getStatus()))
            .collect(Collectors.toList());
    }

    /**
     * Get all projects for a user in a specific workspace
     */
    public List<Project> getUserProjectsInWorkspace(String userId, String workspaceId) {
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
        return projectRepository.findByProjectId(projectId);
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
        
        // Check permissions (only OWNER can add members)
        if (!isOwner(project, userId)) {
            throw new SecurityException("Only project owner can add members");
        }
        
        if (project.hasMember(targetUserId)) {
            throw new IllegalArgumentException("User is already a member");
        }
        
        project.addMember(targetUserId, targetUsername, targetEmail, role);
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
        if (!isOwner(project, userId)) {
            throw new SecurityException("Only project owner can remove members");
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
        if (!isOwner(project, userId)) {
            throw new SecurityException("Only project owner can archive the project");
        }
        
        project.setStatus("ARCHIVED");
        projectRepository.save(project);
    }

    /**
     * Delete a project
     */
    public void deleteProject(String projectId, String userId) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
        // Check permissions
        if (!isOwner(project, userId)) {
            throw new SecurityException("Only project owner can delete the project");
        }
        
        project.setStatus("DELETED");
        projectRepository.save(project);
    }

    /**
     * Check if user has access to a project
     */
    public boolean hasAccess(String projectId, String userId) {
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        return projectOpt.isPresent() && projectOpt.get().hasMember(userId);
    }

    /**
     * Check if user has edit permission
     */
    private boolean hasEditPermission(Project project, String userId) {
        if (project.getOwnerId().equals(userId)) {
            return true;
        }
        
        Project.ProjectMember member = project.getMember(userId);
        return member != null && ("OWNER".equals(member.getRole()) || "EDITOR".equals(member.getRole()));
    }

    /**
     * Check if user is owner
     */
    private boolean isOwner(Project project, String userId) {
        return project.getOwnerId().equals(userId);
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
        Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }
        
        Project project = projectOpt.get();
        
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
            .collect(Collectors.toList());
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
     * Remove a file from a project
     */
    public Project removeFile(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);
        
        // Check if user has edit permission
        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to remove files from this project");
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
     * Get file metadata from project
     */
    public Project.FileMetadataInfo getFileMetadata(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);
        return project.getFile(fileId);
    }
}
