package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.ChangeTrackingService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.owlEditor.service.GridFSFileService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;

@RestController("owlEditorProjectController")
@RequestMapping({"/api/projects", "/api/ontology/projects"})
@CrossOrigin
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectRepository projectRepository;
    private final ProjectShareService shareService;
    private final StorageManager storageManager;
    private final GridFSFileService gridFsFileService;
    private final SparqlDatasetService datasetService;
    private final DraftTrackingService draftTrackingService;
    private final ChangeTrackingService changeTrackingService;

    public ProjectController(ProjectRepository projectRepository,
                             ProjectShareService shareService,
                             StorageManager storageManager,
                             GridFSFileService gridFsFileService,
                             SparqlDatasetService datasetService,
                             DraftTrackingService draftTrackingService,
                             ChangeTrackingService changeTrackingService) {
        this.projectRepository = projectRepository;
        this.shareService = shareService;
        this.storageManager = storageManager;
        this.gridFsFileService = gridFsFileService;
        this.datasetService = datasetService;
        this.draftTrackingService = draftTrackingService;
        this.changeTrackingService = changeTrackingService;
    }

    @GetMapping
    public ResponseEntity<?> listProjects(@RequestParam(required = false) String userEmail,
                                          @RequestParam(required = false) String parentProjectId) {
        try {
            log.info("[ProjectController] listProjects called with userEmail: {}, parentProjectId: {}", userEmail, parentProjectId);
            List<ProjectDocument> allProjects = projectRepository.findAllByOrderByUpdatedAtDesc();
            log.info("[ProjectController] Total documents in database: {}", allProjects.size());

            List<ProjectDocument> allFiles = allProjects.stream()
                .filter(doc -> doc.getGridfsFileId() != null && !doc.getGridfsFileId().isEmpty())
                .collect(Collectors.toList());
            log.info("[ProjectController] Actual files (with gridfsFileId): {}", allFiles.size());

            if (parentProjectId != null && !parentProjectId.isEmpty()) {
                allFiles = allFiles.stream()
                    .filter(doc -> parentProjectId.equals(doc.getProjectId()))
                    .collect(Collectors.toList());
                log.info("[ProjectController] Files for project {}: {}", parentProjectId, allFiles.size());
            }

            if (userEmail == null || userEmail.isEmpty()) {
                List<Map<String, Object>> projects = allFiles.stream()
                    .map(this::mapProjectToInfo)
                    .collect(Collectors.toList());
                return ResponseEntity.ok(Map.of("success", true, "projects", projects));
            }

            List<Map<String, Object>> myFiles = allFiles.stream()
                .filter(doc -> {
                    String docOwner = doc.getOwnerEmail();

                    boolean isOwner = userEmail.equals(docOwner);
                    boolean isUnowned = (docOwner == null || docOwner.isEmpty());

                    if (isUnowned) {
                        try {
                            log.info("[ProjectController] Assigning unowned file {} to user {}", doc.getId(), userEmail);
                            doc.setOwnerEmail(userEmail);
                            projectRepository.save(doc);
                        } catch (Exception e) {
                            log.error("[ProjectController] Failed to assign owner to file {}: {}", doc.getId(), e.getMessage());

                        }
                        return true;
                    }

                    return isOwner;
                })
                .map(doc -> {
                    Map<String, Object> info = mapProjectToInfo(doc);

                    try {
                        shareService.getShareByProjectId(doc.getId()).ifPresent(share -> {
                            if (!share.getSharedWithEmails().isEmpty()) {
                                info.put("sharedWith", share.getSharedWithEmails());
                            }
                        });
                    } catch (Exception e) {
                        log.warn("[ProjectController] Failed to get share info for {}: {}", doc.getId(), e.getMessage());
                    }
                    return info;
                })
                .collect(Collectors.toList());

            log.info("[ProjectController] myFiles count for {}: {}", userEmail, myFiles.size());

            List<ProjectShare> sharedWithMe = shareService.getSharedWithMe(userEmail);
            List<String> sharedProjectIds = sharedWithMe.stream()
                .map(ProjectShare::getProjectId)
                .collect(Collectors.toList());

            log.info("[ProjectController] sharedWithMe count: {}", sharedWithMe.size());

            List<Map<String, Object>> sharedFiles = allFiles.stream()
                .filter(doc -> sharedProjectIds.contains(doc.getId()))
                .map(doc -> {
                    Map<String, Object> info = mapProjectToInfo(doc);

                    info.put("sharedBy", doc.getOwnerEmail());
                    return info;
                })
                .collect(Collectors.toList());

            log.info("[ProjectController] Returning myFiles: {}, sharedFiles: {}", myFiles.size(), sharedFiles.size());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "myFiles", myFiles,
                "sharedFiles", sharedFiles
            ));

        } catch (Exception e) {
            log.error("[ProjectController] listProjects failed for user {}", userEmail, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", "Failed to list projects: " + e.getMessage()
            ));
        }
    }

    private Map<String, Object> mapProjectToInfo(ProjectDocument doc) {
        Map<String, Object> projectInfo = new HashMap<>();
        projectInfo.put("id", doc.getId());
        projectInfo.put("name", doc.getName());
        projectInfo.put("status", doc.getStatus());
        projectInfo.put("statusMessage", doc.getStatusMessage());
        projectInfo.put("updatedAt", doc.getUpdatedAt());
        projectInfo.put("filename", doc.getFilename());
        projectInfo.put("ownerEmail", doc.getOwnerEmail());
        projectInfo.put("projectId", doc.getProjectId());
        projectInfo.put("workspaceId", doc.getWorkspaceId());

        if (doc.getMetadata() != null) {
            projectInfo.put("metadata", doc.getMetadata());
        }

        return projectInfo;
    }

    @DeleteMapping("/{projectId:.+}")
    public ResponseEntity<?> deleteProject(@PathVariable String projectId,
                                           @RequestParam(required = false) String ownerEmail) {
        try {
            ProjectDocument doc = projectRepository.findById(projectId).orElse(null);
            if (doc == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Project not found"));
            }

            if (ownerEmail != null && doc.getOwnerEmail() != null && !doc.getOwnerEmail().equals(ownerEmail)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "error", "Access denied"));
            }

            try {
                datasetService.clearDataset(projectId);
            } catch (Exception e) {
                log.warn("[ProjectController] Failed to clear dataset for {}: {}", projectId, e.getMessage());
            }

            try {
                gridFsFileService.deleteFileByProjectId(projectId);
            } catch (Exception e) {
                log.warn("[ProjectController] Failed to delete GridFS file for {}: {}", projectId, e.getMessage());
            }

            try {
                draftTrackingService.discardDrafts(projectId);
                draftTrackingService.clearAppliedDrafts(projectId);
            } catch (Exception e) {
                log.warn("[ProjectController] Failed to clear drafts for {}: {}", projectId, e.getMessage());
            }
            try {
                changeTrackingService.clearProjectHistory(projectId);
            } catch (Exception e) {
                log.warn("[ProjectController] Failed to clear history for {}: {}", projectId, e.getMessage());
            }

            try {
                shareService.deleteShare(projectId);
            } catch (Exception e) {
                log.warn("[ProjectController] Failed to delete share for {}: {}", projectId, e.getMessage());
            }

            projectRepository.deleteById(projectId);

            try {
                Path projectDir = storageManager.projectDir(projectId);
                if (Files.exists(projectDir)) {
                    Files.walk(projectDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                log.warn("[ProjectController] Failed to delete {}", path);
                            }
                        });
                }
            } catch (Exception e) {
                log.warn("[ProjectController] Failed to delete project files for {}: {}", projectId, e.getMessage());
            }

            return ResponseEntity.ok(Map.of("success", true, "message", "Project deleted successfully"));
        } catch (Exception e) {
            log.error("[ProjectController] Delete failed for {}: {}", projectId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Failed to delete project"));
        }
    }
}
