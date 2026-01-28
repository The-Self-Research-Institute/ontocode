package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.ProjectService;
import self.research.ontology.auth.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final JwtUtil jwtUtil;
    private final self.research.ontology.auth.service.WorkspaceService workspaceService;
    private final self.research.ontology.auth.repository.ProjectRepository projectRepository;

    public ProjectController(ProjectService projectService, UserRepository userRepository, FileMetadataRepository fileMetadataRepository, JwtUtil jwtUtil, self.research.ontology.auth.service.WorkspaceService workspaceService, self.research.ontology.auth.repository.ProjectRepository projectRepository) {
        this.projectService = projectService;
        this.userRepository = userRepository;
        this.fileMetadataRepository = fileMetadataRepository;
        this.jwtUtil = jwtUtil;
        this.workspaceService = workspaceService;
        this.projectRepository = projectRepository;
    }

    /**
     * Get current authenticated username
     */
    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }

    /**
     * Extract workspaceId from JWT token
     */
    private String getWorkspaceIdFromToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                return jwtUtil.extractClaim(token, claims -> claims.get("workspaceId", String.class));
            } catch (Exception e) {
                log.error("Error extracting workspaceId from token", e);
                return null;
            }
        }
        return null;
    }

    /**
     * Get all projects for current user (compatible with frontend)
     * Returns myFiles (owned) and sharedFiles (shared with user)
     */
    @GetMapping
    public ResponseEntity<?> getAllProjects(@RequestParam(required = false) String userEmail) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Get projects owned by user
            List<Project> ownedProjects = projectService.getProjectsByOwnerId(user.getId());
            List<Map<String, Object>> myFiles = ownedProjects.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());
            
            // Get projects shared with user
            List<Project> sharedProjects = projectService.getProjectsSharedWithUser(user.getId());
            List<Map<String, Object>> sharedFiles = sharedProjects.stream()
                    .map(p -> {
                        Map<String, Object> dto = convertToDTO(p);
                        // Find owner email from user repository
                        Optional<User> ownerOpt = userRepository.findById(p.getOwnerId());
                        dto.put("sharedBy", ownerOpt.map(User::getEmail).orElse("Unknown"));
                        return dto;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "myFiles", myFiles,
                "sharedFiles", sharedFiles
            ));
        } catch (Exception e) {
            log.error("Error fetching projects", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Check if project name already exists in workspace
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkProjectExists(
            @RequestParam String name,
            @RequestParam String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            List<Project> workspaceProjects = projectService.getWorkspaceProjects(workspaceId);
            
            // Check if project with same name exists in workspace
            Optional<Project> existingProject = workspaceProjects.stream()
                    .filter(p -> p.getName().equalsIgnoreCase(name.trim()))
                    .findFirst();
            
            if (existingProject.isPresent()) {
                return ResponseEntity.ok(Map.of(
                    "exists", true,
                    "name", name,
                    "existingProject", Map.of(
                        "id", existingProject.get().getId(),
                        "name", existingProject.get().getName(),
                        "createdAt", existingProject.get().getCreatedAt()
                    )
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "exists", false,
                "name", name
            ));
        } catch (Exception e) {
            log.error("Error checking project existence", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Create a new project
     */
    @PostMapping
    public ResponseEntity<?> createProject(@Valid @RequestBody CreateProjectRequest request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Check for duplicate project name in workspace
            List<Project> workspaceProjects = projectService.getWorkspaceProjects(request.workspaceId);
            boolean nameExists = workspaceProjects.stream()
                .anyMatch(p -> p.getName().equalsIgnoreCase(request.name));
            if (nameExists) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "A project with this name already exists in the workspace"
                ));
            }
            
            Project project = projectService.createProject(
                request.workspaceId,
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                request.name,
                request.description
            );
            
            // Handle member sharing
            if ("all".equals(request.shareWith)) {
                // Add all workspace members as viewers (except owner)
                Optional<self.research.ontology.auth.model.Workspace> workspaceOpt = 
                    projectService.getWorkspace(request.workspaceId);
                if (workspaceOpt.isPresent()) {
                    self.research.ontology.auth.model.Workspace workspace = workspaceOpt.get();
                    for (self.research.ontology.auth.model.Workspace.WorkspaceMember member : workspace.getMembers()) {
                        if (!member.getUserId().equals(user.getId())) {
                            project.addMember(member.getUserId(), member.getUsername(), member.getEmail(), "VIEWER");
                        }
                    }
                    projectService.updateProject(project);
                }
            } else if ("specific".equals(request.shareWith) && request.memberUsernames != null) {
                // Add specific members as viewers
                for (String memberUsername : request.memberUsernames) {
                    Optional<User> memberOpt = userRepository.findByUsername(memberUsername);
                    if (memberOpt.isPresent() && !memberOpt.get().getId().equals(user.getId())) {
                        User member = memberOpt.get();
                        project.addMember(member.getId(), member.getUsername(), member.getEmail(), "VIEWER");
                    }
                }
                projectService.updateProject(project);
            }

            return ResponseEntity.ok(Map.of(
                "message", "Project created successfully",
                "project", convertToDTO(project)
            ));
        } catch (SecurityException e) {
            log.error("Security error creating project", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating project", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all projects in a workspace
     */
    @GetMapping("/workspace/{workspaceId}")
    public ResponseEntity<?> getWorkspaceProjects(@PathVariable String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            List<Project> projects = projectService.getWorkspaceProjects(workspaceId);
            
            List<Map<String, Object>> projectDTOs = projects.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "projects", projectDTOs,
                "count", projectDTOs.size()
            ));
        } catch (Exception e) {
            log.error("Error fetching workspace projects", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Get deleted projects for a workspace
     */
    @GetMapping("/workspace/{workspaceId}/deleted")
    public ResponseEntity<?> getDeletedProjects(@PathVariable String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Verify user has access to workspace
            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            if (!workspace.isMember(user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this workspace"));
            }

            // Get all deleted projects in workspace
            List<Project> allProjects = projectRepository.findByWorkspaceId(workspaceId);
            List<Project> deletedProjects = allProjects.stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsDeleted()))
                    .collect(Collectors.toList());

            // Convert to DTOs
            List<Map<String, Object>> projectDTOs = deletedProjects.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "projects", projectDTOs,
                "count", projectDTOs.size()
            ));
        } catch (Exception e) {
            log.error("Error getting deleted projects", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all projects in the current user's workspace
     */
    @GetMapping("/my")
    public ResponseEntity<?> getMyProjects(HttpServletRequest request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Get user's current workspace ID from JWT token
            String workspaceId = getWorkspaceIdFromToken(request);
            
            List<Project> projects;
            
            // Check if user has ROLE_USER (invited user) or no workspace - use user-based storage
            boolean isRoleUser = user.getRoles().contains("ROLE_USER") && !user.getRoles().contains("ROLE_ADMIN");
            boolean hasNoWorkspace = workspaceId == null || workspaceId.isEmpty();
            
            if (isRoleUser || hasNoWorkspace) {
                log.info("User {} is ROLE_USER or has no workspace - fetching user-based projects", username);
                // Get user's own projects (not workspace-based)
                projects = projectService.getUserProjects(user.getId());
            } else {
                // Get ALL projects in the current workspace (workspace-based)
                log.info("User {} has workspace {} - fetching workspace projects", username, workspaceId);
                projects = projectService.getWorkspaceProjects(workspaceId);
            }
            
            List<Map<String, Object>> projectDTOs = projects.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "projects", projectDTOs,
                "count", projectDTOs.size(),
                "isUserBased", isRoleUser || hasNoWorkspace
            ));
        } catch (Exception e) {
            log.error("Error fetching user projects", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get a specific project
     */
    @GetMapping("/{projectId:.+}")
    public ResponseEntity<?> getProject(@PathVariable String projectId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            Optional<Project> projectOpt = projectService.getProject(projectId);
            
            if (projectOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Project project = projectOpt.get();
            
            // Check access
            if (!projectService.hasAccess(projectId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }

            return ResponseEntity.ok(Map.of("project", convertToDTO(project)));
        } catch (Exception e) {
            log.error("Error fetching project", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update a project
     */
    @PutMapping("/{projectId:.+}")
    public ResponseEntity<?> updateProject(
            @PathVariable String projectId,
            @Valid @RequestBody UpdateProjectRequest request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            Project project = projectService.updateProject(
                projectId,
                user.getId(),
                request.name,
                request.description
            );

            return ResponseEntity.ok(Map.of(
                "message", "Project updated successfully",
                "project", convertToDTO(project)
            ));
        } catch (SecurityException e) {
            log.error("Security error updating project", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating project", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Check if member already exists in project
     */
    @GetMapping("/{projectId:.+}/members/check")
    public ResponseEntity<?> checkMemberExists(
            @PathVariable String projectId,
            @RequestParam String email) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            Optional<Project> projectOpt = projectService.getProject(projectId);
            
            if (projectOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project not found"));
            }
            
            Project project = projectOpt.get();
            
            // Check if user with this email is already a member
            boolean isMember = project.getMembers().stream()
                    .anyMatch(m -> m.getEmail().equalsIgnoreCase(email.trim()));
            
            if (isMember) {
                Optional<Project.ProjectMember> existingMember = project.getMembers().stream()
                        .filter(m -> m.getEmail().equalsIgnoreCase(email.trim()))
                        .findFirst();
                        
                return ResponseEntity.ok(Map.of(
                    "exists", true,
                    "email", email,
                    "existingMember", Map.of(
                        "userId", existingMember.get().getUserId(),
                        "username", existingMember.get().getUsername(),
                        "email", existingMember.get().getEmail(),
                        "role", existingMember.get().getRole()
                    )
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "exists", false,
                "email", email
            ));
        } catch (Exception e) {
            log.error("Error checking member existence", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Add a member to a project
     */
    @PostMapping("/{projectId:.+}/members")
    public ResponseEntity<?> addMember(
            @PathVariable String projectId,
            @Valid @RequestBody AddMemberRequest request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Find target user
            Optional<User> targetUserOpt = userRepository.findByUsername(request.username);
            if (targetUserOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Target user not found"));
            }
            
            User targetUser = targetUserOpt.get();
            
            Project project = projectService.addMember(
                projectId,
                user.getId(),
                targetUser.getId(),
                targetUser.getUsername(),
                targetUser.getEmail(),
                request.role
            );

            return ResponseEntity.ok(Map.of(
                "message", "Member added successfully",
                "project", convertToDTO(project)
            ));
        } catch (SecurityException e) {
            log.error("Security error adding member", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error adding member", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Remove a member from a project
     */
    @DeleteMapping("/{projectId:.+}/members/{userId}")
    public ResponseEntity<?> removeMember(
            @PathVariable String projectId,
            @PathVariable String userId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            Project project = projectService.removeMember(projectId, user.getId(), userId);

            return ResponseEntity.ok(Map.of(
                "message", "Member removed successfully",
                "project", convertToDTO(project)
            ));
        } catch (SecurityException e) {
            log.error("Security error removing member", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error removing member", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Archive a project
     */
    @PostMapping("/{projectId:.+}/archive")
    public ResponseEntity<?> archiveProject(@PathVariable String projectId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            projectService.archiveProject(projectId, user.getId());

            return ResponseEntity.ok(Map.of("message", "Project archived successfully"));
        } catch (SecurityException e) {
            log.error("Security error archiving project", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error archiving project", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Soft delete a project
     */
    @DeleteMapping("/{projectId:.+}")
    public ResponseEntity<?> deleteProject(@PathVariable String projectId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            projectService.deleteProject(projectId, user.getId());

            return ResponseEntity.ok(Map.of("message", "Project deleted successfully"));
        } catch (SecurityException e) {
            log.error("Security error deleting project", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting project", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Restore a soft deleted project
     */
    @PostMapping("/{projectId:.+}/restore")
    public ResponseEntity<?> restoreProject(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "true") boolean restoreFiles) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            projectService.restoreProject(projectId, user.getId(), restoreFiles);

            return ResponseEntity.ok(Map.of(
                "message", "Project restored successfully",
                "projectId", projectId,
                "filesRestored", restoreFiles
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            log.error("Security error restoring project", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error restoring project", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Convert Project to DTO
     */
    private Map<String, Object> convertToDTO(Project project) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", project.getId());
        dto.put("projectId", project.getProjectId());
        dto.put("name", project.getName());
        dto.put("description", project.getDescription() != null ? project.getDescription() : "");
        dto.put("workspaceId", project.getWorkspaceId());
        dto.put("ownerId", project.getOwnerId());
        dto.put("members", project.getMembers());
        dto.put("memberCount", project.getMembers().size());
        dto.put("status", project.getStatus());
        dto.put("tags", project.getTags());
        dto.put("fileCount", project.getActiveFiles().size()); // Use active files from metadata
        dto.put("createdAt", project.getCreatedAt().toString());
        dto.put("updatedAt", project.getUpdatedAt().toString());
        dto.put("fileIds", project.getFileIds()); // Keep for backward compatibility
        dto.put("files", project.getFiles()); // Include file metadata
        return dto;
    }

    /**
     * Get files for a project
     */
    @GetMapping("/{projectId:.+}/files")
    public ResponseEntity<?> getProjectFiles(@PathVariable String projectId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Get project and verify access
            Optional<Project> projectOpt = projectService.getProject(projectId);
            if (projectOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Project project = projectOpt.get();
            if (!projectService.hasAccess(projectId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
            
            // Get files from project metadata (primary source)
            List<Map<String, Object>> files = new ArrayList<>();
            for (Project.FileMetadataInfo fileInfo : project.getActiveFiles()) {
                Map<String, Object> fileData = new HashMap<>();
                fileData.put("id", fileInfo.getFileId());
                fileData.put("name", fileInfo.getFileName());
                fileData.put("size", fileInfo.getFileSize());
                fileData.put("uploadedBy", fileInfo.getUploaderUsername());
                fileData.put("uploadedAt", fileInfo.getUploadedAt().toString());
                fileData.put("type", fileInfo.getExtension());
                files.add(fileData);
            }
            
            // Fallback to separate file_metadata collection if project metadata is empty
            if (files.isEmpty()) {
                List<FileMetadata> fileMetadataList = fileMetadataRepository.findByProjectIdAndStatus(projectId, "ACTIVE");
                for (FileMetadata fileMeta : fileMetadataList) {
                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("id", fileMeta.getFileId());
                    fileInfo.put("name", fileMeta.getFileName());
                    fileInfo.put("size", fileMeta.getFileSize());
                    fileInfo.put("uploadedBy", fileMeta.getUploaderUsername());
                    fileInfo.put("uploadedAt", fileMeta.getUploadedAt().toString());
                    fileInfo.put("type", fileMeta.getExtension());
                    files.add(fileInfo);
                }
            }

            return ResponseEntity.ok(Map.of(
                "files", files,
                "count", files.size()
            ));
        } catch (SecurityException e) {
            log.error("Security error getting project files", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error getting project files", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to get project files"));
        }
    }

    /**
     * Get file content by file ID
     */
    @GetMapping("/{projectId:.+}/files/{fileId}/content")
    public ResponseEntity<?> getFileContent(
            @PathVariable String projectId,
            @PathVariable String fileId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Verify project access
            if (!projectService.hasAccess(projectId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
            
            // Get file metadata
            Optional<FileMetadata> fileMetaOpt = fileMetadataRepository.findByFileId(fileId);
            if (fileMetaOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            FileMetadata fileMeta = fileMetaOpt.get();
            
            // Verify file belongs to project
            if (!projectId.equals(fileMeta.getProjectId())) {
                return ResponseEntity.status(403).body(Map.of("error", "File does not belong to this project"));
            }

            return ResponseEntity.ok(Map.of(
                "id", fileMeta.getFileId(),
                "name", fileMeta.getFileName(),
                "content", fileMeta.getBase64Data(),
                "type", fileMeta.getFileType(),
                "size", fileMeta.getFileSize()
            ));
        } catch (Exception e) {
            log.error("Error getting file content", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to get file content"));
        }
    }

    /**
     * Check if a file with the same name already exists in the project
     */
    @GetMapping("/{projectId:.+}/files/check")
    public ResponseEntity<?> checkFileExists(
            @PathVariable String projectId,
            @RequestParam String fileName) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            // Get project
            Optional<Project> projectOpt = projectService.getProject(projectId);
            if (projectOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project not found"));
            }
            
            Project project = projectOpt.get();
            
            // Check if file with same name exists
            boolean exists = project.getFiles().stream()
                    .anyMatch(file -> file.getFileName().equals(fileName));
            
            if (exists) {
                // Find the existing file details
                Project.FileMetadataInfo existingFile = project.getFiles().stream()
                        .filter(file -> file.getFileName().equals(fileName))
                        .findFirst()
                        .orElse(null);
                
                return ResponseEntity.ok(Map.of(
                    "exists", true,
                    "fileName", fileName,
                    "existingFile", existingFile != null ? Map.of(
                        "fileId", existingFile.getFileId(),
                        "fileName", existingFile.getFileName(),
                        "fileSize", existingFile.getFileSize(),
                        "uploadedAt", existingFile.getUploadedAt()
                    ) : Map.of()
                ));
            } else {
                return ResponseEntity.ok(Map.of("exists", false, "fileName", fileName));
            }
        } catch (Exception e) {
            log.error("Error checking file existence", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Upload a file to a project
     */
    @PostMapping("/{projectId:.+}/files")
    public ResponseEntity<?> uploadFile(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> fileData) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Extract file data from JSON
            String fileName = (String) fileData.get("fileName");
            String base64Data = (String) fileData.get("fileData");
            String replaceFileId = (String) fileData.get("replaceFileId"); // Optional: file ID to replace
            
            // Validate file
            if (fileName == null || fileName.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File name is required"));
            }
            
            if (base64Data == null || base64Data.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File data is required"));
            }
            
            if (!fileName.matches(".*\\.(owl|rdf|ttl|n3)$")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file type. Only .owl, .rdf, .ttl, .n3 files are allowed"));
            }

            // Extract file extension
            String extension = fileName.substring(fileName.lastIndexOf(".") + 1);
            
            // Get project to find workspaceId
            Optional<Project> projectOpt = projectService.getProject(projectId);
            if (projectOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project not found"));
            }
            
            Project project = projectOpt.get();
            
            // If replaceFileId is provided, delete the old file first
            if (replaceFileId != null && !replaceFileId.isEmpty()) {
                try {
                    projectService.removeFile(projectId, user.getId(), replaceFileId);
                    fileMetadataRepository.deleteById(replaceFileId);
                    log.info("Replaced existing file: {} with ID: {}", fileName, replaceFileId);
                } catch (Exception e) {
                    log.warn("Error deleting old file during replacement: {}", e.getMessage());
                    // Continue with upload even if deletion fails
                }
            }
            
            // Generate file ID and save metadata
            String fileId = UUID.randomUUID().toString();
            
            // Save file metadata
            FileMetadata fileMetadata = new FileMetadata(fileId, fileName, projectId, project.getWorkspaceId());
            fileMetadata.setFileSize(((Number) fileData.getOrDefault("fileSize", 0)).longValue());
            fileMetadata.setFileType((String) fileData.getOrDefault("fileType", "application/rdf+xml"));
            fileMetadata.setExtension(extension);
            fileMetadata.setBase64Data(base64Data); // Store base64 data temporarily
            fileMetadata.setUploadedBy(user.getId());
            fileMetadata.setUploaderEmail(user.getEmail());
            fileMetadata.setUploaderUsername(user.getUsername());
            
            fileMetadataRepository.save(fileMetadata);
            
            // Add file metadata to project
            Project.FileMetadataInfo projectFileInfo = new Project.FileMetadataInfo(
                fileId, fileName, 
                ((Number) fileData.getOrDefault("fileSize", 0)).longValue(),
                (String) fileData.getOrDefault("fileType", "application/rdf+xml"),
                extension
            );
            projectFileInfo.setUploadedBy(user.getId());
            projectFileInfo.setUploaderUsername(user.getUsername());
            projectFileInfo.setUploaderEmail(user.getEmail());
            
            projectService.addFileMetadata(projectId, user.getId(), projectFileInfo);

            // Check if user is admin/owner of the project
            boolean isAdmin = project.getOwnerId().equals(user.getId());
            
            // Build response with project info for admin users
            Map<String, Object> response = new HashMap<>();
            response.put("message", "File uploaded successfully");
            response.put("fileId", fileId);
            response.put("filename", fileName);
            
            if (isAdmin) {
                response.put("projectId", projectId);
                response.put("projectName", project.getName());
                response.put("workspaceId", project.getWorkspaceId());
            }

            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            log.error("Security error uploading file", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error uploading file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to upload file"));
        }
    }

    /**
     * Soft delete a file from a project
     */
    @DeleteMapping("/{projectId:.+}/files/{fileId}")
    public ResponseEntity<?> deleteFile(
            @PathVariable String projectId,
            @PathVariable String fileId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Soft delete file metadata
            Optional<FileMetadata> fileMetaOpt = fileMetadataRepository.findByFileId(fileId);
            if (fileMetaOpt.isPresent()) {
                FileMetadata fileMeta = fileMetaOpt.get();
                fileMeta.setIsDeleted(true);
                fileMeta.setDeletedAt(LocalDateTime.now());
                fileMeta.setDeletedBy(user.getId());
                fileMeta.setStatus("DELETED");
                fileMetadataRepository.save(fileMeta);
            }
            
            // Soft delete file in project
            Project project = projectService.removeFile(projectId, user.getId(), fileId);

            return ResponseEntity.ok(Map.of(
                "message", "File deleted successfully"
            ));
        } catch (SecurityException e) {
            log.error("Security error deleting file", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to delete file"));
        }
    }
    
    /**
     * Restore a soft deleted file in a project
     */
    @PostMapping("/{projectId:.+}/files/{fileId}/restore")
    public ResponseEntity<?> restoreFile(
            @PathVariable String projectId,
            @PathVariable String fileId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Restore file metadata
            Optional<FileMetadata> fileMetaOpt = fileMetadataRepository.findByFileId(fileId);
            if (fileMetaOpt.isPresent()) {
                FileMetadata fileMeta = fileMetaOpt.get();
                if (!Boolean.TRUE.equals(fileMeta.getIsDeleted())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "File is not deleted"));
                }
                fileMeta.setIsDeleted(false);
                fileMeta.setDeletedAt(null);
                fileMeta.setDeletedBy(null);
                fileMeta.setStatus("ACTIVE");
                fileMetadataRepository.save(fileMeta);
            }
            
            // Restore file in project
            Project project = projectService.restoreFile(projectId, user.getId(), fileId);

            return ResponseEntity.ok(Map.of(
                "message", "File restored successfully",
                "fileId", fileId
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            log.error("Security error restoring file", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error restoring file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to restore file"));
        }
    }

    /**
     * Rename a project
     */
    @PatchMapping("/{projectId}/rename")
    public ResponseEntity<?> renameProject(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            String newName = request.get("name");
            
            if (newName == null || newName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project name is required"));
            }
            
            // Get project and verify ownership
            Optional<Project> projectOpt = projectService.getProject(projectId);
            if (projectOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Project project = projectOpt.get();
            if (!project.getOwnerId().equals(user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Only project owner can rename"));
            }
            
            // Update project name
            project.setName(newName.trim());
            project.setUpdatedAt(java.time.LocalDateTime.now());
            
            // Save through service (assuming there's a save method)
            projectService.updateProject(project);

            return ResponseEntity.ok(Map.of(
                "message", "Project renamed successfully",
                "project", convertToDTO(project)
            ));
        } catch (Exception e) {
            log.error("Error renaming project", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to rename project"));
        }
    }

    // Request DTOs
    public static class CreateProjectRequest {
        public String workspaceId;
        public String name;
        public String description;
        public String shareWith; // "all" or "specific"
        public List<String> memberUsernames; // List of usernames when shareWith="specific"
    }

    public static class UpdateProjectRequest {
        public String name;
        public String description;
    }

    public static class AddMemberRequest {
        public String username;
        public String role; // EDITOR, VIEWER
    }
}
