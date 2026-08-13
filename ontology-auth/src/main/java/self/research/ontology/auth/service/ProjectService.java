package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final SystemSettingsService systemSettingsService;

    public ProjectService(ProjectRepository projectRepository, WorkspaceRepository workspaceRepository, UserRepository userRepository, SystemSettingsService systemSettingsService) {
        this.projectRepository = projectRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.systemSettingsService = systemSettingsService;
    }

    public Project createProject(String workspaceId, String userId, String username, String email, String name, String description) {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name is required");
        }

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

        if (!workspace.isMember(userId)) {
            throw new SecurityException("User does not have access to this workspace");
        }

        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setName(name);
        project.setDescription(description);
        project.setWorkspaceId(workspaceId);
        project.setOwnerId(userId);
        project.setStatus("ACTIVE");

        project.addMember(userId, username, email, "OWNER");

        return projectRepository.save(project);
    }

    public boolean applyImplicitWorkspaceLeadershipEditors(Project project, Workspace workspace) {
        if (project == null || workspace == null) {
            return false;
        }
        boolean dirty = false;
        String wsOwnerId = workspace.getOwnerId();
        if (wsOwnerId != null && !wsOwnerId.equals(project.getOwnerId())) {
            dirty |= upsertLinkedWorkspaceEditor(project, workspace, wsOwnerId, Project.WS_EDITOR_LINK_OWNER);
        }
        for (Workspace.WorkspaceMember wm : workspace.getMembers()) {
            if (wm.getUserId() == null || wm.getStatus() != Workspace.MemberStatus.ACTIVE) {
                continue;
            }
            if (wm.getUserId().equals(project.getOwnerId())) {
                continue;
            }
            if (wm.getRole() == Workspace.WorkspaceRole.ADMIN) {
                dirty |= upsertLinkedWorkspaceEditor(project, workspace, wm.getUserId(), Project.WS_EDITOR_LINK_ADMIN);
            }
        }
        return dirty;
    }

    private boolean upsertLinkedWorkspaceEditor(Project project, Workspace workspace, String userId, String link) {
        Workspace.WorkspaceMember wm = workspace.getMember(userId);
        String username = wm != null ? wm.getUsername() : null;
        String email = wm != null ? wm.getEmail() : null;
        if ((username == null || email == null) && userId.equals(workspace.getOwnerId())) {
            Optional<User> u = userRepository.findById(userId);
            if (u.isPresent()) {
                if (username == null) {
                    username = u.get().getUsername();
                }
                if (email == null) {
                    email = u.get().getEmail();
                }
            }
        }
        if (username == null) {
            username = "";
        }
        if (email == null) {
            email = "";
        }

        Project.ProjectMember pm = project.getMember(userId);
        if (pm == null) {
            project.addMember(userId, username, email, "EDITOR", link);
            return true;
        }

        boolean dirty = false;
        if (Project.WS_EDITOR_LINK_OWNER.equals(link)) {
            if (!Project.WS_EDITOR_LINK_OWNER.equals(pm.getWorkspaceEditorLink())) {
                pm.setWorkspaceEditorLink(Project.WS_EDITOR_LINK_OWNER);
                dirty = true;
            }
        } else if (Project.WS_EDITOR_LINK_ADMIN.equals(link)) {
            if (pm.getWorkspaceEditorLink() == null || Project.WS_EDITOR_LINK_ADMIN.equals(pm.getWorkspaceEditorLink())) {
                pm.setWorkspaceEditorLink(Project.WS_EDITOR_LINK_ADMIN);
                dirty = true;
            }
        }

        String r = pm.getRole() != null ? pm.getRole() : "VIEWER";
        if ("VIEWER".equals(r)) {
            pm.setRole("EDITOR");
            dirty = true;
        }

        if (dirty) {
            project.setUpdatedAt(LocalDateTime.now());
        }
        return dirty;
    }

    public List<Project> getWorkspaceProjects(String workspaceId) {
        getWorkspaceForUsage(workspaceId);
        return projectRepository.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE");
    }

    public List<Project> getUserProjects(String userId) {
        return projectRepository.findByMembers_UserId(userId)
            .stream()
            .filter(p -> "ACTIVE".equals(p.getStatus()))
            .filter(p -> isWorkspaceAccessibleForUsage(p.getWorkspaceId()))
            .collect(Collectors.toList());
    }

    public List<Project> getUserProjectsInWorkspace(String userId, String workspaceId) {
        getWorkspaceForUsage(workspaceId);
        return projectRepository.findByMembers_UserId(userId)
            .stream()
            .filter(p -> "ACTIVE".equals(p.getStatus()))
            .filter(p -> workspaceId.equals(p.getWorkspaceId()))
            .collect(Collectors.toList());
    }

    public Optional<Project> getProject(String projectId) {
        List<Project> projects = projectRepository.findAllActiveByProjectId(projectId);
        Project project;
        if (!projects.isEmpty()) {
            if (projects.size() > 1) {
                log.warn("Duplicate active project documents for projectId={} (count={}), using first", projectId, projects.size());
            }
            project = projects.get(0);
        } else {

            Optional<Project> byId = projectRepository.findById(projectId);
            if (byId.isEmpty() || Boolean.TRUE.equals(byId.get().getIsDeleted())) {
                return Optional.empty();
            }
            project = byId.get();
            boolean needsBackfill = false;
            if (project.getProjectId() == null || project.getProjectId().isEmpty()) {
                project.setProjectId(projectId);
                needsBackfill = true;
            }
            if (project.getWorkspaceId() == null || project.getWorkspaceId().isEmpty()) {
                project.setWorkspaceId("desktop-workspace-local");
                needsBackfill = true;
            }
            if (needsBackfill) {
                log.info("[ProjectService] Backfilling projectId/workspaceId for desktop-created project {}", projectId);
                project = projectRepository.save(project);
            }
        }
        if (!isWorkspaceAccessibleForUsage(project.getWorkspaceId())) {
            log.warn("[ProjectService] getProject blocked: projectId={} workspaceId={} — workspace not accessible (billing or enterprise check)",
                    projectId, project.getWorkspaceId());
            return Optional.empty();
        }
        return Optional.of(project);
    }

    public Optional<Workspace> getWorkspace(String workspaceId) {
        return workspaceRepository.findByWorkspaceId(workspaceId);
    }

    public Project updateProject(String projectId, String userId, String name, String description) {
        Optional<Project> projectOpt = findProjectByIdTolerant(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("User does not have permission to edit this project");
        }

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

    public Project addMember(String projectId, String userId, String targetUserId, String targetUsername, String targetEmail, String role) {
        Optional<Project> projectOpt = findProjectByIdTolerant(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or a workspace owner/admin can add members");
        }

        String normalizedRole = role == null ? "" : role.toUpperCase();
        if (!List.of("ADMIN", "EDITOR", "DRAFT_EDITOR", "VIEWER").contains(normalizedRole)) {
            throw new IllegalArgumentException("Invalid role. Must be ADMIN, EDITOR, DRAFT_EDITOR, or VIEWER");
        }

        if (project.hasMember(targetUserId)) {
            throw new IllegalArgumentException("User is already a member");
        }

        project.addMember(targetUserId, targetUsername, targetEmail, normalizedRole);
        return projectRepository.save(project);
    }

    public Project updateMemberRole(String projectId, String userId, String targetUserId, String newRole) {
        Optional<Project> projectOpt = findProjectByIdTolerant(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or a workspace owner/admin can update member roles");
        }

        if (project.getOwnerId().equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot change the project owner's role");
        }

        String normalizedRole = newRole == null ? "" : newRole.toUpperCase();
        if (!List.of("ADMIN", "EDITOR", "DRAFT_EDITOR", "VIEWER").contains(normalizedRole)) {
            throw new IllegalArgumentException("Invalid role. Must be ADMIN, EDITOR, DRAFT_EDITOR, or VIEWER");
        }

        Project.ProjectMember member = project.getMember(targetUserId);
        if (member == null) {
            throw new IllegalArgumentException("User is not a member of this project");
        }

        Workspace workspace = workspaceRepository.findByWorkspaceId(project.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        if (Project.WS_EDITOR_LINK_OWNER.equals(member.getWorkspaceEditorLink())) {
            throw new IllegalArgumentException("The workspace owner's access on this project cannot be changed.");
        }
        if (Project.WS_EDITOR_LINK_ADMIN.equals(member.getWorkspaceEditorLink())
                && !workspace.getOwnerId().equals(userId)) {
            throw new SecurityException("Only the workspace owner can change this workspace administrator's project role.");
        }

        member.setRole(normalizedRole);
        return projectRepository.save(project);
    }

    public Project removeMember(String projectId, String userId, String targetUserId) {
        Optional<Project> projectOpt = findProjectByIdTolerant(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or a workspace owner/admin can remove members");
        }

        if (project.getOwnerId().equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot remove project owner");
        }

        Workspace workspace = workspaceRepository.findByWorkspaceId(project.getWorkspaceId()).orElse(null);
        if (workspace != null) {
            Project.ProjectMember target = project.getMember(targetUserId);
            if (target != null) {
                if (Project.WS_EDITOR_LINK_OWNER.equals(target.getWorkspaceEditorLink())) {
                    throw new SecurityException("The workspace owner cannot be removed from this project.");
                }
                if (Project.WS_EDITOR_LINK_ADMIN.equals(target.getWorkspaceEditorLink())
                        && !workspace.getOwnerId().equals(userId)) {
                    throw new SecurityException(
                            "Only the workspace owner can remove a workspace administrator from this project.");
                }
            }
        }

        project.removeMember(targetUserId);
        return projectRepository.save(project);
    }

    public void archiveProject(String projectId, String userId) {
        Optional<Project> projectOpt = findProjectByIdTolerant(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or a workspace owner/admin can archive the project");
        }

        project.setStatus("ARCHIVED");
        projectRepository.save(project);
    }

    public void deleteProject(String projectId, String userId) {
        Optional<Project> projectOpt = findProjectByIdTolerant(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or a workspace owner/admin can delete the project");
        }

        project.setIsDeleted(true);
        project.setDeletedAt(LocalDateTime.now());
        project.setDeletedBy(userId);
        project.setStatus("DELETED");
        projectRepository.save(project);

        for (Project.FileMetadataInfo fileInfo : project.getFiles()) {
            if (!"DELETED".equals(fileInfo.getStatus())) {
                fileInfo.setStatus("DELETED");
            }
        }
        projectRepository.save(project);
    }

    public void restoreProject(String projectId, String userId, boolean restoreFiles) {
        Optional<Project> projectOpt = findProjectByIdTolerant(projectId);
        if (projectOpt.isEmpty()) {
            throw new IllegalArgumentException("Project not found");
        }

        Project project = projectOpt.get();

        if (!canManageProject(project, userId)) {
            throw new SecurityException("Only project owner or a workspace owner/admin can restore the project");
        }

        if (!Boolean.TRUE.equals(project.getIsDeleted())) {
            throw new IllegalStateException("Project is not deleted");
        }

        project.setIsDeleted(false);
        project.setDeletedAt(null);
        project.setDeletedBy(null);
        project.setStatus("ACTIVE");

        if (restoreFiles) {

            for (Project.FileMetadataInfo fileInfo : project.getFiles()) {
                if ("DELETED".equals(fileInfo.getStatus())) {
                    fileInfo.setStatus("ACTIVE");
                }
            }
        }

        projectRepository.save(project);
    }

    public boolean hasAccess(String projectId, String userId) {
        List<Project> projects = projectRepository.findAllActiveByProjectId(projectId);
        if (projects.isEmpty()) {
            return false;
        }
        if (projects.size() > 1) {
            log.warn("Duplicate active project documents for projectId={} (count={}) in hasAccess, using first", projectId, projects.size());
        }
        Project project = projects.get(0);

        if (project.hasMember(userId)) {
            return true;
        }

        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(project.getWorkspaceId());
        if (workspaceOpt.isPresent()) {
            Workspace workspace = workspaceOpt.get();
            if (!canUseWorkspace(workspace)) {
                return false;
            }
            Workspace.WorkspaceMember wsMember = workspace.getMember(userId);
            if (wsMember != null) {
                boolean isOwnerOrAdmin = wsMember.getRole() == Workspace.WorkspaceRole.OWNER
                        || wsMember.getRole() == Workspace.WorkspaceRole.ADMIN;

                return isOwnerOrAdmin && project.getMembers().size() > 1;
            }
        }
        return false;
    }

    private boolean hasEditPermission(Project project, String userId) {
        if (project.getOwnerId().equals(userId)) {
            return true;
        }

        Project.ProjectMember member = project.getMember(userId);
        if (member != null) {
            String role = member.getRole();
            if ("OWNER".equals(role) || "ADMIN".equals(role) || "EDITOR".equals(role)) {
                return true;
            }

        }

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

    private boolean isOwner(Project project, String userId) {
        return project.getOwnerId().equals(userId);
    }

    private boolean isWorkspaceOwnerOrAdmin(String workspaceId, String userId) {
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        if (workspaceOpt.isEmpty()) {
            return false;
        }
        Workspace workspace = workspaceOpt.get();
        if (!canUseWorkspace(workspace)) {
            return false;
        }
        if (workspace.getOwnerId().equals(userId)) {
            return true;
        }
        Workspace.WorkspaceMember wsMember = workspace.getMember(userId);
        if (wsMember == null) {
            return false;
        }
        Workspace.WorkspaceRole wsRole = wsMember.getRole();
        return wsRole == Workspace.WorkspaceRole.OWNER || wsRole == Workspace.WorkspaceRole.ADMIN;
    }

    private boolean canManageProject(Project project, String userId) {
        if (isOwner(project, userId)) {
            return true;
        }
        Project.ProjectMember member = project.getMember(userId);
        if (member != null && "ADMIN".equals(member.getRole())) {
            return true;
        }
        return isWorkspaceOwnerOrAdmin(project.getWorkspaceId(), userId);
    }

    private Optional<Project> findProjectByIdTolerant(String projectId) {
        List<Project> projects = projectRepository.findAllByProjectId(projectId);
        if (projects.isEmpty()) return Optional.empty();
        if (projects.size() > 1) {
            log.warn("Duplicate project documents for projectId={} (count={}), using first", projectId, projects.size());
        }
        return Optional.of(projects.get(0));
    }

    private String generateProjectId() {
        String projectId;
        do {
            projectId = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        } while (projectRepository.existsByProjectId(projectId));
        return projectId;
    }

    private Project getProjectById(String projectId, String userId) {
        List<Project> projects = projectRepository.findAllActiveByProjectId(projectId);
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("Project not found or has been deleted");
        }
        if (projects.size() > 1) {
            log.warn("Duplicate active project documents for projectId={} (count={}), using first", projectId, projects.size());
        }
        Project project = projects.get(0);
        if (!isWorkspaceAccessibleForUsage(project.getWorkspaceId())) {
            throw new SecurityException("Workspace payment is pending. Complete payment to continue.");
        }

        if (!project.hasMember(userId) && !isWorkspaceOwnerOrAdmin(project.getWorkspaceId(), userId)) {
            throw new SecurityException("User does not have access to this project");
        }

        return project;
    }

    public List<Project> getProjectsByOwnerId(String userId) {
        return projectRepository.findByOwnerIdAndStatus(userId, "ACTIVE");
    }

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

        User owner = userRepository.findById(workspace.getOwnerId()).orElse(null);
        if (owner == null) return true;

        if (systemSettingsService.isEnterpriseBypass(owner.getEmail())) return true;

        String subStatus = owner.getSubscriptionStatus();
        String subPlan = owner.getSubscriptionPlanName();

        if (subPlan == null || "FREE".equalsIgnoreCase(subPlan)) {
            return true;
        }

        boolean accessible = "active".equalsIgnoreCase(subStatus) || "trialing".equalsIgnoreCase(subStatus);
        if (!accessible) {
            log.warn("[ProjectService] canUseWorkspace=false workspaceId={} ownerEmail={} plan={} status={} (enterprise domains configured: {})",
                    workspace.getWorkspaceId(), owner.getEmail(), subPlan, subStatus,
                    systemSettingsService.get().getEnterpriseDomains());
        }
        return accessible;
    }

    public Project updateProject(Project project) {
        return projectRepository.save(project);
    }

    public Project addFile(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);

        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to add files to this project");
        }

        if (!project.getFileIds().contains(fileId)) {
            project.getFileIds().add(fileId);
            project.setUpdatedAt(LocalDateTime.now());
            return projectRepository.save(project);
        }

        return project;
    }

    public Project addFileMetadata(String projectId, String userId, Project.FileMetadataInfo fileMetadata) {
        Project project = getProjectById(projectId, userId);

        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to add files to this project");
        }

        project.addFileMetadata(fileMetadata);
        return projectRepository.save(project);
    }

    public Project removeFile(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);

        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to remove files from this project");
        }

        Project.ProjectMember member = project.getMember(userId);
        if (member != null && "EDITOR".equals(member.getRole()) && !project.getOwnerId().equals(userId)) {
            Project.FileMetadataInfo fileInfo = project.getFile(fileId);
            if (fileInfo != null && fileInfo.getUploadedBy() != null && !fileInfo.getUploadedBy().equals(userId)) {
                throw new SecurityException("Editors can only delete files they uploaded");
            }
        }

        Project.FileMetadataInfo fileInfo = project.getFile(fileId);
        if (fileInfo != null) {
            fileInfo.setStatus("DELETED");
        }

        project.getFileIds().remove(fileId);
        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public Project renameFile(String projectId, String userId, String fileId, String newFileName) {
        Project project = getProjectById(projectId, userId);

        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to rename files in this project");
        }

        Project.ProjectMember member = project.getMember(userId);
        Project.FileMetadataInfo fileInfo = project.getFile(fileId);
        if (fileInfo == null) {
            throw new IllegalArgumentException("File not found");
        }
        if (member != null && "EDITOR".equals(member.getRole()) && !project.getOwnerId().equals(userId)) {
            if (fileInfo.getUploadedBy() != null && !fileInfo.getUploadedBy().equals(userId)) {
                throw new SecurityException("Editors can only rename files they uploaded");
            }
        }

        String trimmed = newFileName == null ? "" : newFileName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }

        String currentName = fileInfo.getFileName();
        String extension = currentName != null && currentName.contains(".")
                ? currentName.substring(currentName.lastIndexOf('.'))
                : "";
        String requestedBase = trimmed.contains(".") ? trimmed.substring(0, trimmed.lastIndexOf('.')) : trimmed;
        String finalName = requestedBase + extension;

        boolean collides = project.getActiveFiles().stream()
                .anyMatch(f -> !f.getFileId().equals(fileId) && finalName.equalsIgnoreCase(f.getFileName()));
        if (collides) {
            throw new IllegalArgumentException("A file named \"" + finalName + "\" already exists in this project");
        }

        fileInfo.setFileName(finalName);
        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public Project restoreFile(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);

        if (!hasEditPermission(project, userId)) {
            throw new SecurityException("You don't have permission to restore files in this project");
        }

        Project.FileMetadataInfo fileInfo = project.getFile(fileId);
        if (fileInfo != null) {
            if (!"DELETED".equals(fileInfo.getStatus())) {
                throw new IllegalStateException("File is not deleted");
            }
            fileInfo.setStatus("ACTIVE");
        } else {
            throw new IllegalArgumentException("File not found in project");
        }

        if (!project.getFileIds().contains(fileId)) {
            project.getFileIds().add(fileId);
        }

        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public Project.FileMetadataInfo getFileMetadata(String projectId, String userId, String fileId) {
        Project project = getProjectById(projectId, userId);
        return project.getFile(fileId);
    }
}
