package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.ProjectService;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FileMetadataRepository fileMetadataRepository;

    public ProjectController(ProjectService projectService, UserRepository userRepository, FileMetadataRepository fileMetadataRepository) {
        this.projectService = projectService;
        this.userRepository = userRepository;
        this.fileMetadataRepository = fileMetadataRepository;
    }

    /**
     * Get current authenticated username
     */
    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
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
     * Get all projects for current user
     */
    @GetMapping("/my")
    public ResponseEntity<?> getMyProjects() {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            List<Project> projects = projectService.getUserProjects(user.getId());
            
            List<Map<String, Object>> projectDTOs = projects.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "projects", projectDTOs,
                "count", projectDTOs.size()
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
     * Delete a project
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
        dto.put("fileCount", project.getFileIds().size());
        dto.put("createdAt", project.getCreatedAt().toString());
        dto.put("updatedAt", project.getUpdatedAt().toString());
        dto.put("fileIds", project.getFileIds());
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
            
            // Get actual file metadata from database
            List<FileMetadata> fileMetadataList = fileMetadataRepository.findByProjectIdAndStatus(projectId, "ACTIVE");
            
            List<Map<String, Object>> files = new ArrayList<>();
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
     * Upload a file to a project
     */
    @PostMapping("/{projectId}/files")
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
            
            // Generate file ID and save metadata
            String fileId = UUID.randomUUID().toString();
            
            // Get project to find workspaceId
            Optional<Project> projectOpt = projectService.getProject(projectId);
            if (projectOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project not found"));
            }
            
            Project project = projectOpt.get();
            
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
            
            // Add file ID to project
            projectService.addFile(projectId, user.getId(), fileId);

            return ResponseEntity.ok(Map.of(
                "message", "File uploaded successfully",
                "fileId", fileId,
                "filename", fileName
            ));
        } catch (SecurityException e) {
            log.error("Security error uploading file", e);
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error uploading file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to upload file"));
        }
    }

    /**
     * Delete a file from a project
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
            
            // Mark file metadata as deleted
            Optional<FileMetadata> fileMetaOpt = fileMetadataRepository.findByFileId(fileId);
            if (fileMetaOpt.isPresent()) {
                FileMetadata fileMeta = fileMetaOpt.get();
                fileMeta.setStatus("DELETED");
                fileMetadataRepository.save(fileMeta);
            }
            
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
