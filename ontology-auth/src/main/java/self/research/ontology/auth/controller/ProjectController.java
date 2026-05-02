package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.ProjectService;
import self.research.ontology.auth.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    @Value("${storage.limit.free.gb:10}")
    private double storageLimitFreeGb;

    @Value("${storage.limit.pro.gb:100}")
    private double storageLimitProGb;

    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final JwtUtil jwtUtil;
    private final self.research.ontology.auth.service.WorkspaceService workspaceService;
    private final self.research.ontology.auth.repository.ProjectRepository projectRepository;
    private final GridFsTemplate gridFsTemplate;
    private final org.springframework.web.client.RestTemplate restTemplate;

    public ProjectController(ProjectService projectService, UserRepository userRepository, FileMetadataRepository fileMetadataRepository, JwtUtil jwtUtil, self.research.ontology.auth.service.WorkspaceService workspaceService, self.research.ontology.auth.repository.ProjectRepository projectRepository, GridFsTemplate gridFsTemplate) {
        this.projectService = projectService;
        this.userRepository = userRepository;
        this.fileMetadataRepository = fileMetadataRepository;
        this.jwtUtil = jwtUtil;
        this.workspaceService = workspaceService;
        this.projectRepository = projectRepository;
        this.gridFsTemplate = gridFsTemplate;
        this.restTemplate = new org.springframework.web.client.RestTemplate();
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
            
            log.info("[createProject] User: {}, userId: {}, email: {}, workspaceId: {}", 
                username, user.getId(), user.getEmail(), request.workspaceId);
            
            // FREE plan members are view-only — only the workspace owner can create projects
            var viewOnlyBlock = checkFreeViewOnly(request.workspaceId, user.getId());
            if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();

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
            
            // Handle member sharing - add members before final save
            boolean membersAdded = false;
            
            // If creator is not workspace owner, add workspace owner as ADMIN
            Optional<self.research.ontology.auth.model.Workspace> workspaceOpt = 
                projectService.getWorkspace(request.workspaceId);
            if (workspaceOpt.isPresent()) {
                self.research.ontology.auth.model.Workspace workspace = workspaceOpt.get();
                String wsOwnerId = workspace.getOwnerId();
                if (wsOwnerId != null && !wsOwnerId.equals(user.getId())) {
                    Optional<User> wsOwnerOpt = userRepository.findById(wsOwnerId);
                    if (wsOwnerOpt.isPresent()) {
                        User wsOwner = wsOwnerOpt.get();
                        project.addMember(wsOwner.getId(), wsOwner.getUsername(), wsOwner.getEmail(), "ADMIN");
                        membersAdded = true;
                    }
                }
            }
            
            if ("all".equals(request.shareWith)) {
                // Add all active workspace members as viewers (matching UI: "All members will have view access")
                // Workspace owner is already added as ADMIN above; skip project creator and ws owner
                if (workspaceOpt.isPresent()) {
                    self.research.ontology.auth.model.Workspace workspace = workspaceOpt.get();
                    for (self.research.ontology.auth.model.Workspace.WorkspaceMember member : workspace.getMembers()) {
                        if (member.getUserId() != null 
                                && !member.getUserId().equals(user.getId())
                                && !member.getUserId().equals(workspace.getOwnerId())
                                && member.getStatus() == self.research.ontology.auth.model.Workspace.MemberStatus.ACTIVE) {
                            // Use the role from request if provided, otherwise default to VIEWER
                            String memberRole = request.memberRole != null ? request.memberRole : "VIEWER";
                            project.addMember(member.getUserId(), member.getUsername(), member.getEmail(), memberRole);
                            membersAdded = true;
                        }
                    }
                }
            } else if ("specific".equals(request.shareWith) && request.memberUsernames != null) {
                // Add specific members with the role specified in the request (default: VIEWER)
                String memberRole = request.memberRole != null ? request.memberRole : "VIEWER";
                for (String memberUsername : request.memberUsernames) {
                    Optional<User> memberOpt = userRepository.findByUsername(memberUsername);
                    if (memberOpt.isPresent() && !memberOpt.get().getId().equals(user.getId())) {
                        User member = memberOpt.get();
                        project.addMember(member.getId(), member.getUsername(), member.getEmail(), memberRole);
                        membersAdded = true;
                    }
                }
            }
            
            if (membersAdded) {
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

            User user = userOpt.get();
            List<Project> projects = projectService.getWorkspaceProjects(workspaceId);
            
            // Filter projects based on workspace role and project privacy.
            // Workspace OWNER sees all non-private projects (private = only the project owner is a member).
            // Workspace ADMIN and regular members only see projects they are explicitly a member of.
            Optional<Workspace> wsOpt = workspaceService.getWorkspace(workspaceId);
            boolean isWsOwner = false;
            if (wsOpt.isPresent()) {
                Workspace.WorkspaceMember wsMember = wsOpt.get().getMember(user.getId());
                if (wsMember != null) {
                    isWsOwner = wsMember.getRole() == Workspace.WorkspaceRole.OWNER;
                }
            }
            if (isWsOwner) {
                // Workspace owner sees all shared projects (> 1 member) plus their own
                projects = projects.stream()
                        .filter(p -> p.hasMember(user.getId()) || p.getMembers().size() > 1)
                        .collect(Collectors.toList());
            } else {
                // Admins and regular members only see projects they are explicitly added to
                projects = projects.stream()
                        .filter(p -> p.hasMember(user.getId()))
                        .collect(Collectors.toList());
            }
            
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
    public ResponseEntity<?> getMyProjects(HttpServletRequest request,
                                           @RequestParam(required = false) String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Get workspace ID: prefer JWT token claim, fallback to query parameter
            String tokenWorkspaceId = getWorkspaceIdFromToken(request);
            String effectiveWorkspaceId = (tokenWorkspaceId != null && !tokenWorkspaceId.isEmpty()) 
                ? tokenWorkspaceId : workspaceId;
            
            log.info("[getMyProjects] User: {}, JWT workspaceId: {}, query workspaceId: {}, effective: {}", 
                username, tokenWorkspaceId, workspaceId, effectiveWorkspaceId);
            
            List<Project> projects;
            
            // Check if user has no workspace - use user-based storage
            boolean hasNoWorkspace = effectiveWorkspaceId == null || effectiveWorkspaceId.isEmpty();
            
            if (hasNoWorkspace) {
                log.info("[getMyProjects] User {} has no workspace - fetching user-based projects", username);
                // Get user's own projects (not workspace-based)
                projects = projectService.getUserProjects(user.getId());
                log.info("[getMyProjects] User-based projects found: {}", projects.size());
            } else {
                // Get ALL projects in the current workspace (workspace-based)
                log.info("[getMyProjects] User {} has workspace {} - fetching workspace projects", username, effectiveWorkspaceId);
                projects = projectService.getWorkspaceProjects(effectiveWorkspaceId);
                log.info("[getMyProjects] Workspace projects found: {}", projects.size());
                
                // Auto-repair projects with missing ownerId or empty members
                for (Project p : projects) {
                    boolean needsRepair = false;
                    if (p.getOwnerId() == null || p.getOwnerId().isEmpty()) {
                        log.info("[getMyProjects] Repairing project {} - setting ownerId to {}", p.getProjectId(), user.getId());
                        p.setOwnerId(user.getId());
                        needsRepair = true;
                    }
                    if (p.getMembers() == null || p.getMembers().isEmpty()) {
                        log.info("[getMyProjects] Repairing project {} - adding owner {} as member", p.getProjectId(), username);
                        p.addMember(user.getId(), user.getUsername(), user.getEmail(), "OWNER");
                        needsRepair = true;
                    }
                    if (needsRepair) {
                        try {
                            projectService.updateProject(p);
                            log.info("[getMyProjects] ✓ Repaired project {}", p.getProjectId());
                        } catch (Exception repairError) {
                            log.warn("[getMyProjects] Failed to repair project {}: {}", p.getProjectId(), repairError.getMessage());
                        }
                    }
                    log.info("[getMyProjects]   Project: id={}, name={}, ownerId={}, members={}, files={}", 
                        p.getProjectId(), p.getName(), p.getOwnerId(), p.getMembers().size(), p.getActiveFiles().size());
                }
                
                // Workspace OWNER sees all shared projects plus their own.
                // Admins and regular members only see projects they are explicitly a member of.
                Optional<Workspace> wsOpt = workspaceService.getWorkspace(effectiveWorkspaceId);
                boolean isWsOwner = false;
                if (wsOpt.isPresent()) {
                    Workspace.WorkspaceMember wsMember = wsOpt.get().getMember(user.getId());
                    if (wsMember != null) {
                        isWsOwner = wsMember.getRole() == Workspace.WorkspaceRole.OWNER;
                    }
                }
                if (isWsOwner) {
                    projects = projects.stream()
                            .filter(p -> p.hasMember(user.getId()) || p.getMembers().size() > 1)
                            .collect(Collectors.toList());
                    log.info("[getMyProjects] Filtered to {} projects for workspace owner {}", projects.size(), username);
                } else {
                    projects = projects.stream()
                            .filter(p -> p.hasMember(user.getId()))
                            .collect(Collectors.toList());
                    log.info("[getMyProjects] Filtered to {} projects for user {}", projects.size(), username);
                }
            }
            
            List<Map<String, Object>> projectDTOs = projects.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "projects", projectDTOs,
                "count", projectDTOs.size(),
                "isUserBased", hasNoWorkspace
            ));
        } catch (Exception e) {
            log.error("Error fetching user projects", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get a specific project
     */
    @GetMapping("/{projectId}")
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
    @PutMapping("/{projectId}")
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
    @GetMapping("/{projectId}/members/check")
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
    @PostMapping("/{projectId}/members")
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
     * Update a member's role in a project
     */
    @PatchMapping("/{projectId}/members/{memberId}/role")
    public ResponseEntity<?> updateMemberRole(
            @PathVariable String projectId,
            @PathVariable String memberId,
            @Valid @RequestBody UpdateMemberRoleRequest request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            Project project = projectService.updateMemberRole(
                projectId,
                user.getId(),
                memberId,
                request.role
            );

            return ResponseEntity.ok(Map.of(
                "message", "Member role updated successfully",
                "project", convertToDTO(project)
            ));
        } catch (SecurityException e) {
            log.error("Security error updating member role", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating member role", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Remove a member from a project
     */
    @DeleteMapping("/{projectId}/members/{userId}")
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
    @PostMapping("/{projectId}/archive")
    public ResponseEntity<?> archiveProject(@PathVariable String projectId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            Optional<Project> projectForCheck = projectService.getProject(projectId);
            if (projectForCheck.isPresent()) {
                var viewOnlyBlock = checkFreeViewOnly(projectForCheck.get().getWorkspaceId(), user.getId());
                if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();
            }

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
    @DeleteMapping("/{projectId}")
    public ResponseEntity<?> deleteProject(@PathVariable String projectId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            // FREE plan members are view-only
            Optional<Project> projectForCheck = projectRepository.findByProjectId(projectId);
            if (projectForCheck.isPresent()) {
                var viewOnlyBlock = checkFreeViewOnly(projectForCheck.get().getWorkspaceId(), user.getId());
                if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();
            }

            // Delete from GraphDB first (best-effort) for each file in the project
            try {
                Optional<Project> projectOpt = projectRepository.findByProjectId(projectId);
                if (projectOpt.isPresent()) {
                    Project project = projectOpt.get();
                    String editorServiceUrl = System.getenv().getOrDefault("ONTOLOGY_EDITOR_URL", "http://localhost:8083");

                    for (Project.FileMetadataInfo fileInfo : project.getFiles()) {
                        if ("DELETED".equals(fileInfo.getStatus())) continue;
                        String fileId = fileInfo.getFileId();
                        if (fileId == null || fileId.isEmpty()) continue;

                        String graphDbProjectId = projectId + "/" + fileId;
                        try {
                            String graphDbDeleteUrl = editorServiceUrl + "/api/ontology/project/" + java.net.URLEncoder.encode(graphDbProjectId, "UTF-8");
                            log.info("🗑️ Deleting file from GraphDB during project delete: {} (graph: http://ontocode.org/project/{})", fileId, graphDbProjectId);
                            restTemplate.delete(graphDbDeleteUrl);
                            log.info("✅ Successfully deleted file from GraphDB: {}", graphDbProjectId);
                        } catch (Exception graphDbEx) {
                            log.warn("⚠️ Failed to delete file {} from GraphDB (continuing): {}", fileId, graphDbEx.getMessage());
                        }
                    }

                    // Also try to delete the project-level graph (for files imported directly under the project ID)
                    try {
                        String graphDbDeleteUrl = editorServiceUrl + "/api/ontology/project/" + java.net.URLEncoder.encode(projectId, "UTF-8");
                        log.info("🗑️ Deleting project graph from GraphDB: {}", projectId);
                        restTemplate.delete(graphDbDeleteUrl);
                        log.info("✅ Successfully deleted project graph from GraphDB: {}", projectId);
                    } catch (Exception graphDbEx) {
                        log.warn("⚠️ Failed to delete project graph from GraphDB (continuing): {}", graphDbEx.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ GraphDB cleanup failed during project delete (continuing with soft delete): {}", e.getMessage());
            }

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
    @PostMapping("/{projectId}/restore")
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

            Optional<Project> projectForCheck = projectService.getProject(projectId);
            if (projectForCheck.isPresent()) {
                var viewOnlyBlock = checkFreeViewOnly(projectForCheck.get().getWorkspaceId(), user.getId());
                if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();
            }

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
        dto.put("files", project.getActiveFiles()); // Only include non-deleted files
        return dto;
    }

    /**
     * Get files for a project
     */
    @GetMapping("/{projectId}/files")
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
            
            // Determine user's project role
            String userProjectRole = "VIEWER"; // default
            if (project.getOwnerId().equals(user.getId())) {
                userProjectRole = "OWNER";
            } else {
                // Workspace owners/admins always get ADMIN role in projects
                Optional<Workspace> wsOpt = workspaceService.getWorkspace(project.getWorkspaceId());
                boolean isWsOwnerOrAdmin = false;
                if (wsOpt.isPresent()) {
                    Workspace.WorkspaceMember wsMember = wsOpt.get().getMember(user.getId());
                    if (wsMember != null) {
                        Workspace.WorkspaceRole wsRole = wsMember.getRole();
                        if (wsRole == Workspace.WorkspaceRole.OWNER || wsRole == Workspace.WorkspaceRole.ADMIN) {
                            userProjectRole = "ADMIN";
                            isWsOwnerOrAdmin = true;
                        }
                    }
                }
                if (!isWsOwnerOrAdmin) {
                    Project.ProjectMember pm = project.getMember(user.getId());
                    if (pm != null) {
                        userProjectRole = pm.getRole();
                    }
                }
            }
            
            // Get files from project metadata (primary source)
            List<Map<String, Object>> files = new ArrayList<>();
            for (Project.FileMetadataInfo fileInfo : project.getActiveFiles()) {
                Map<String, Object> fileData = new HashMap<>();
                fileData.put("id", fileInfo.getFileId());
                fileData.put("name", fileInfo.getFileName());
                fileData.put("size", fileInfo.getFileSize());
                fileData.put("uploadedBy", fileInfo.getUploaderUsername());
                fileData.put("uploadedByUserId", fileInfo.getUploadedBy());
                fileData.put("uploadedAt", fileInfo.getUploadedAt() != null ? fileInfo.getUploadedAt().toString() : null);
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
                    fileInfo.put("uploadedByUserId", fileMeta.getUploadedBy());
                    fileInfo.put("uploadedAt", fileMeta.getUploadedAt() != null ? fileMeta.getUploadedAt().toString() : null);
                    fileInfo.put("type", fileMeta.getExtension());
                    files.add(fileInfo);
                }
            }

            return ResponseEntity.ok(Map.of(
                "files", files,
                "count", files.size(),
                "userProjectRole", userProjectRole
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
     * Get file content by file ID.
     * Uses streaming for GridFS files to avoid loading entire file into memory (OOM for large files).
     */
    @GetMapping("/{projectId}/files/{fileId}/content")
    public ResponseEntity<?> getFileContent(
            @PathVariable String projectId,
            @PathVariable String fileId,
            HttpServletResponse httpResponse) {
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

            // Retrieve content from GridFS using streaming to avoid OOM on large files
            if (fileMeta.getGridfsId() != null && !fileMeta.getGridfsId().isEmpty()) {
                try {
                    GridFsResource resource = gridFsTemplate.getResource(
                            gridFsTemplate.findOne(Query.query(Criteria.where("_id").is(new ObjectId(fileMeta.getGridfsId())))));

                    String fileType = fileMeta.getFileType() != null ? fileMeta.getFileType() : "application/rdf+xml";
                    long fileSize = fileMeta.getFileSize() != null ? fileMeta.getFileSize() : 0;

                    // Write directly to HttpServletResponse to stream base64 in constant memory.
                    // NOTE: ResponseEntity<StreamingResponseBody> doesn't work when the method
                    // return type is ResponseEntity<?> — Spring MVC serialises the lambda as {}
                    // instead of executing the stream.
                    httpResponse.setContentType("application/json");
                    httpResponse.setCharacterEncoding("UTF-8");
                    httpResponse.setStatus(HttpServletResponse.SC_OK);

                    try (InputStream inputStream = resource.getInputStream();
                         OutputStream outputStream = httpResponse.getOutputStream()) {
                        // Write JSON opening with metadata
                        String prefix = "{\"id\":\"" + escapeJson(fileMeta.getFileId())
                                + "\",\"name\":\"" + escapeJson(fileMeta.getFileName())
                                + "\",\"content\":\"data:" + escapeJson(fileType) + ";base64,";
                        outputStream.write(prefix.getBytes(StandardCharsets.UTF_8));

                        // Stream base64-encoded file content using a non-closing wrapper
                        // so closing base64Out writes final padding without closing the response stream
                        OutputStream nonClosing = new FilterOutputStream(outputStream) {
                            @Override
                            public void close() throws IOException {
                                flush();
                            }
                        };
                        try (OutputStream base64Out = Base64.getEncoder().wrap(nonClosing)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                base64Out.write(buffer, 0, bytesRead);
                            }
                        }

                        // Write JSON closing with remaining metadata
                        String suffix = "\",\"type\":\"" + escapeJson(fileType)
                                + "\",\"size\":" + fileSize + "}";
                        outputStream.write(suffix.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }

                    return null; // Response already written directly
                } catch (Exception gridfsEx) {
                    log.error("Error reading file from GridFS (id={}): {}", fileMeta.getGridfsId(), gridfsEx.getMessage());
                    if (!httpResponse.isCommitted()) {
                        return ResponseEntity.internalServerError().body(Map.of("error", "Could not read file content from storage"));
                    }
                    return null;
                }
            } else {
                // Legacy: file content stored inline (will be null for purged documents)
                String base64Content = fileMeta.getBase64Data();
                if (base64Content == null) {
                    return ResponseEntity.status(404).body(Map.of("error", "File content not available"));
                }

                return ResponseEntity.ok(Map.of(
                    "id", fileMeta.getFileId(),
                    "name", fileMeta.getFileName(),
                    "content", base64Content,
                    "type", fileMeta.getFileType() != null ? fileMeta.getFileType() : "application/rdf+xml",
                    "size", fileMeta.getFileSize() != null ? fileMeta.getFileSize() : 0
                ));
            }
        } catch (Exception e) {
            log.error("Error getting file content", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to get file content"));
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Check if a file with the same name already exists in the project
     */
    @GetMapping("/{projectId}/files/check")
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
            
            // Check if file with same name exists in active (non-deleted) files
            boolean exists = project.getActiveFiles().stream()
                    .anyMatch(file -> file.getFileName().equals(fileName));
            
            if (exists) {
                // Find the existing file details
                Project.FileMetadataInfo existingFile = project.getActiveFiles().stream()
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
            }
            
            // Additionally check if GraphDB already has data for this project
            // This prevents loading duplicate ontology data even if filename is different
            try {
                String editorServiceUrl = System.getenv("EDITOR_SERVICE_URL");
                if (editorServiceUrl == null || editorServiceUrl.isEmpty()) {
                    editorServiceUrl = "http://localhost:8081"; // default for development
                }
                
                String graphdbCheckUrl = String.format("%s/api/ontology/%s/graphdb/check?fileName=%s",
                    editorServiceUrl, projectId, java.net.URLEncoder.encode(fileName, "UTF-8"));
                
                log.debug("Checking GraphDB for duplicates at: {}", graphdbCheckUrl);
                
                // Make HTTP call to editor service to check GraphDB
                java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(graphdbCheckUrl))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
                
                java.net.http.HttpResponse<String> response = httpClient.send(request, 
                    java.net.http.HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    // Parse response to check if GraphDB has data
                    log.debug("GraphDB check response: {}", response.body());
                    // Note: For a complete implementation, parse the JSON response
                    // For now, we log it and continue with metadata check result
                }
            } catch (Exception graphdbCheckEx) {
                // If GraphDB check fails, log warning but don't fail the request
                log.warn("GraphDB duplicate check failed (will proceed with metadata check): {}", 
                    graphdbCheckEx.getMessage());
            }
            
            return ResponseEntity.ok(Map.of("exists", false, "fileName", fileName));
        } catch (Exception e) {
            log.error("Error checking file existence", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Upload a file to a project (multipart streaming — no base64, no full memory buffering)
     */
    @PostMapping(value = "/{projectId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "replaceFileId", required = false) String replaceFileId,
            @RequestParam(value = "fileType", required = false, defaultValue = "application/rdf+xml") String fileType) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Validate file
            if (fileName == null || fileName.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File name is required"));
            }
            
            if (file.isEmpty()) {
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
            
            // Check user's project role - viewers cannot upload files
            Project.ProjectMember projectMember = project.getMember(user.getId());
            if (projectMember != null && "VIEWER".equals(projectMember.getRole())) {
                return ResponseEntity.status(403).body(Map.of("error", "Viewers cannot upload files to this project"));
            }
            
            // Check GraphDB for duplicate data BEFORE uploading
            // This prevents loading the same ontology data multiple times into the same project graph
            // Skip for small files (< 10KB) as they are typically new empty ontologies
            if ((replaceFileId == null || replaceFileId.isEmpty()) && file.getSize() > 10240) {
                // Only check for new uploads, skip for replacements
                try {
                    String editorServiceUrl = System.getenv("EDITOR_SERVICE_URL");
                    if (editorServiceUrl == null || editorServiceUrl.isEmpty()) {
                        editorServiceUrl = "http://localhost:8081"; // default for development
                    }
                    
                    String graphdbCheckUrl = String.format("%s/api/ontology/%s/graphdb/check?fileName=%s",
                        editorServiceUrl, projectId, java.net.URLEncoder.encode(fileName, "UTF-8"));
                    
                    log.info("Checking GraphDB for duplicate data before upload: {}", graphdbCheckUrl);
                    
                    java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest checkRequest = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(graphdbCheckUrl))
                        .GET()
                        .timeout(java.time.Duration.ofSeconds(5))
                        .build();
                    
                    java.net.http.HttpResponse<String> checkResponse = httpClient.send(checkRequest, 
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (checkResponse.statusCode() == 200) {
                        String responseBody = checkResponse.body();
                        log.debug("GraphDB duplicate check response: {}", responseBody);
                        
                        // Parse JSON response to check if data exists
                        // Simple check: look for "\"exists\":true" in response
                        if (responseBody != null && responseBody.contains("\"exists\":true")) {
                            log.warn("GraphDB already contains data for project {}. Upload may create duplicates.", projectId);
                            
                            // Extract graph size if available
                            String warningMessage = "This project already contains ontology data in GraphDB. " +
                                "Uploading this file may create duplicate triples. " +
                                "Consider replacing the existing file or clearing the project data first.";
                            
                            // Return warning but allow upload to proceed
                            // Frontend can decide whether to show a confirmation dialog
                            // For now, we log the warning and continue
                            log.warn("DUPLICATE WARNING: {}", warningMessage);
                        }
                    }
                } catch (Exception graphdbCheckEx) {
                    // If GraphDB check fails, log error but don't block upload
                    log.error("GraphDB duplicate check failed before upload (proceeding anyway): {}", 
                        graphdbCheckEx.getMessage());
                }
            }
            
            // Get workspace and check storage limits
            String workspaceId = project.getWorkspaceId();
            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            String subscriptionPlan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";
            String ownerId = workspace.getOwnerId();

            // FREE plan members are view-only
            var viewOnlyBlock = checkFreeViewOnly(workspaceId, user.getId());
            if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();

            // Storage quota is shared across ALL workspaces owned by the same account.
            long currentStorageBytes = calculateOwnerStorageUsage(ownerId);
            long newFileSize = file.getSize();

            // If replacing, subtract old file size
            if (replaceFileId != null && !replaceFileId.isEmpty()) {
                Optional<FileMetadata> oldFile = fileMetadataRepository.findById(replaceFileId);
                if (oldFile.isPresent() && oldFile.get().getFileSize() != null) {
                    currentStorageBytes -= oldFile.get().getFileSize();
                }
            }

            // Get storage limit for subscription plan (convert GB to bytes)
            double storageLimitGB = getStorageLimitForPlan(subscriptionPlan);
            long storageLimitBytes = (long) (storageLimitGB * 1024 * 1024 * 1024);

            // Check if upload would exceed limit
            if (currentStorageBytes + newFileSize > storageLimitBytes) {
                double currentStorageMB = currentStorageBytes / (1024.0 * 1024.0);
                double newFileSizeMB = newFileSize / (1024.0 * 1024.0);
                double limitMB = storageLimitGB * 1024;

                log.warn("Storage limit exceeded for owner {}. Current: {} MB, New file: {} MB, Limit: {} MB",
                    ownerId, String.format("%.2f", currentStorageMB), String.format("%.2f", newFileSizeMB), String.format("%.2f", limitMB));

                return ResponseEntity.status(413).body(Map.of(
                    "error", "Storage limit exceeded for " + subscriptionPlan + " plan",
                    "currentStorageMB", String.format("%.2f", currentStorageMB),
                    "newFileSizeMB", String.format("%.2f", newFileSizeMB),
                    "storageLimitMB", String.format("%.2f", limitMB),
                    "plan", subscriptionPlan,
                    "message", String.format("Your account has used %.2f MB of %.2f MB across all workspaces. This file (%.2f MB) would exceed your storage limit. Please upgrade your plan or delete existing files.",
                        currentStorageMB, limitMB, newFileSizeMB)
                ));
            }
            
            // If replaceFileId is provided, delete the old file first
            if (replaceFileId != null && !replaceFileId.isEmpty()) {
                try {
                    Optional<FileMetadata> oldFileMeta = fileMetadataRepository.findById(replaceFileId);
                    if (oldFileMeta.isPresent() && oldFileMeta.get().getGridfsId() != null) {
                        gridFsTemplate.delete(Query.query(Criteria.where("_id").is(new ObjectId(oldFileMeta.get().getGridfsId()))));
                        log.info("Deleted old GridFS object during replace: {}", oldFileMeta.get().getGridfsId());
                    }
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
            
            // Stream file directly to GridFS — no base64, no full byte[] in memory
            String contentType = fileType;
            String gridfsId;
            try (InputStream inputStream = file.getInputStream()) {
                ObjectId gridfsObjectId = gridFsTemplate.store(inputStream, fileName, contentType);
                gridfsId = gridfsObjectId.toString();
                log.info("Stored file in GridFS: {} (objectId={}, size={})", fileName, gridfsId, newFileSize);
            }
            
            // Save file metadata
            FileMetadata fileMetadata = new FileMetadata(fileId, fileName, projectId, project.getWorkspaceId());
            fileMetadata.setFileSize(newFileSize);
            fileMetadata.setFileType(contentType);
            fileMetadata.setExtension(extension);
            fileMetadata.setGridfsId(gridfsId);
            fileMetadata.setUploadedBy(user.getId());
            fileMetadata.setUploaderEmail(user.getEmail());
            fileMetadata.setUploaderUsername(user.getUsername());
            
            fileMetadataRepository.save(fileMetadata);
            
            // Add file metadata to project
            Project.FileMetadataInfo projectFileInfo = new Project.FileMetadataInfo(
                fileId, fileName, newFileSize, contentType, extension
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
    @DeleteMapping("/{projectId}/files/{fileId}")
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

            // FREE plan members are view-only — resolve workspace via project
            Optional<Project> projectForCheck = projectService.getProject(projectId);
            if (projectForCheck.isPresent()) {
                var viewOnlyBlock = checkFreeViewOnly(projectForCheck.get().getWorkspaceId(), user.getId());
                if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();
            }

            // DELETE FROM GRAPHDB FIRST (hierarchical project ID: parentProject/fileId)
            // This removes all RDF triples for this file from the GraphDB named graph
            String graphDbProjectId = projectId + "/" + fileId;
            try {
                String editorServiceUrl = System.getenv().getOrDefault("ONTOLOGY_EDITOR_URL", "http://localhost:8081");
                String graphDbDeleteUrl = editorServiceUrl + "/api/ontology/project/" + java.net.URLEncoder.encode(graphDbProjectId, "UTF-8");
                
                log.info("🗑️ Deleting file from GraphDB: {} (graph: http://ontocode.org/project/{})", fileId, graphDbProjectId);
                
                restTemplate.delete(graphDbDeleteUrl);
                log.info("✅ Successfully deleted file from GraphDB: {}", graphDbProjectId);
            } catch (Exception graphDbEx) {
                log.warn("⚠️ Failed to delete from GraphDB (continuing with MongoDB cleanup): {}", graphDbEx.getMessage());
                // Continue with MongoDB deletion even if GraphDB fails
            }
            
            // THEN DELETE FROM MONGODB (soft delete file metadata)
            Optional<FileMetadata> fileMetaOpt = fileMetadataRepository.findByFileId(fileId);
            if (fileMetaOpt.isPresent()) {
                FileMetadata fileMeta = fileMetaOpt.get();
                fileMeta.setIsDeleted(true);
                fileMeta.setDeletedAt(LocalDateTime.now());
                fileMeta.setDeletedBy(user.getId());
                fileMeta.setStatus("DELETED");
                fileMetadataRepository.save(fileMeta);
                
                // Remove binary from GridFS to free storage space
                if (fileMeta.getGridfsId() != null && !fileMeta.getGridfsId().isEmpty()) {
                    try {
                        gridFsTemplate.delete(Query.query(Criteria.where("_id").is(new ObjectId(fileMeta.getGridfsId()))));
                        log.info("Deleted GridFS object: {}", fileMeta.getGridfsId());
                    } catch (Exception gridfsEx) {
                        log.warn("Could not delete GridFS object {}: {}", fileMeta.getGridfsId(), gridfsEx.getMessage());
                    }
                }
            }
            
            // Soft delete file in project
            Project project = projectService.removeFile(projectId, user.getId(), fileId);

            return ResponseEntity.ok(Map.of(
                "message", "File deleted successfully from both GraphDB and MongoDB"
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
    @PostMapping("/{projectId}/files/{fileId}/restore")
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

            // FREE plan members are view-only
            Optional<Project> projectForCheck = projectService.getProject(projectId);
            if (projectForCheck.isPresent()) {
                var viewOnlyBlock = checkFreeViewOnly(projectForCheck.get().getWorkspaceId(), user.getId());
                if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();
            }

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
     * Update project details (description, etc.)
     */
    @PatchMapping("/{projectId}")
    public ResponseEntity<?> updateProject(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            // Get project and verify ownership
            Optional<Project> projectOpt = projectService.getProject(projectId);
            if (projectOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Project project = projectOpt.get();

            var viewOnlyBlock = checkFreeViewOnly(project.getWorkspaceId(), user.getId());
            if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();

            if (!project.getOwnerId().equals(user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Only project owner can update project settings"));
            }

            // Update description if provided
            if (request.containsKey("description")) {
                project.setDescription(request.get("description"));
            }

            project.setUpdatedAt(java.time.LocalDateTime.now());
            projectService.updateProject(project);

            return ResponseEntity.ok(Map.of(
                "message", "Project updated successfully",
                "project", convertToDTO(project)
            ));
        } catch (Exception e) {
            log.error("Error updating project", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update project"));
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

            var viewOnlyBlock = checkFreeViewOnly(project.getWorkspaceId(), user.getId());
            if (viewOnlyBlock.isPresent()) return viewOnlyBlock.get();

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
        public String memberRole; // Role for shared members: VIEWER (default), EDITOR, ADMIN
    }

    public static class UpdateProjectRequest {
        public String name;
        public String description;
    }

    public static class AddMemberRequest {
        public String username;
        public String role; // EDITOR, VIEWER
    }

    public static class UpdateMemberRoleRequest {
        public String role; // ADMIN, EDITOR, VIEWER
    }
    
    /**
     * Returns 403 if the workspace is FREE and the caller is not the owner.
     * FREE plan members get view-only access — they cannot create, modify, or delete content.
     */
    private Optional<ResponseEntity<?>> checkFreeViewOnly(String workspaceId, String userId) {
        Optional<self.research.ontology.auth.model.Workspace> wsOpt = workspaceService.getWorkspace(workspaceId);
        if (wsOpt.isEmpty()) return Optional.empty();
        self.research.ontology.auth.model.Workspace ws = wsOpt.get();
        String plan = ws.getSubscriptionPlan() != null ? ws.getSubscriptionPlan() : "FREE";
        if ("FREE".equalsIgnoreCase(plan) && !ws.getOwnerId().equals(userId)) {
            return Optional.of(ResponseEntity.status(403).body(Map.of(
                "error", "Members have view-only access on the Free plan. The workspace owner must upgrade to Pro to allow members to edit.",
                "requiresUpgrade", true
            )));
        }
        return Optional.empty();
    }

    /**
     * Calculate total storage used across all workspaces owned by this user (in bytes).
     * Storage quota is account-wide, not per-workspace.
     */
    private long calculateOwnerStorageUsage(String ownerId) {
        try {
            return workspaceService.getOwnedWorkspaces(ownerId).stream()
                .mapToLong(ws -> calculateWorkspaceStorageUsage(ws.getWorkspaceId()))
                .sum();
        } catch (Exception e) {
            log.error("Error calculating owner storage usage for {}", ownerId, e);
            return 0;
        }
    }

    /**
     * Calculate total storage usage for a workspace (in bytes)
     */
    private long calculateWorkspaceStorageUsage(String workspaceId) {
        try {
            // Get all projects in the workspace
            List<Project> projects = projectRepository.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE");
            
            long totalStorage = 0;
            
            // Sum up all file sizes from all projects
            for (Project project : projects) {
                List<Project.FileMetadataInfo> files = project.getActiveFiles();
                for (Project.FileMetadataInfo file : files) {
                    if (file.getFileSize() != null) {
                        totalStorage += file.getFileSize();
                    }
                }
            }
            
            log.info("Workspace {} storage usage: {} bytes ({:.2f} MB)", 
                workspaceId, totalStorage, totalStorage / (1024.0 * 1024.0));
            
            return totalStorage;
        } catch (Exception e) {
            log.error("Error calculating workspace storage usage", e);
            return 0;
        }
    }
    
    /**
     * Get storage limit for subscription plan (in GB)
     */
    private double getStorageLimitForPlan(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> storageLimitFreeGb;
            case "PRO" -> storageLimitProGb;
            case "ENTERPRISE" -> Double.MAX_VALUE;
            default -> storageLimitFreeGb;
        };
    }
}
