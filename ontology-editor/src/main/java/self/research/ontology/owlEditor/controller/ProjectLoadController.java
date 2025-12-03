package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.GraphDBHistoryService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class ProjectLoadController {

    private static final Logger log = LoggerFactory.getLogger(ProjectLoadController.class);
    
    // Project-level locks to prevent concurrent saves
    private final ConcurrentHashMap<String, Object> projectSaveLocks = new ConcurrentHashMap<>();

    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;
    private final ProjectImportService importService;
    private final GridFSFileService gridFSFileService;
    private final ProjectShareService shareService;
    private final DraftTrackingService draftTrackingService;
    private final GraphDBHistoryService historyService;
    private final DraftChangeRepository draftChangeRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public ProjectLoadController(StorageManager storageManager,
                                 ProjectMetadataService metadataService,
                                 ProjectImportService importService,
                                 GridFSFileService gridFSFileService,
                                 ProjectShareService shareService,
                                 DraftTrackingService draftTrackingService,
                                 GraphDBHistoryService historyService,
                                 DraftChangeRepository draftChangeRepository,
                                 SimpMessagingTemplate messagingTemplate) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
        this.importService = importService;
        this.gridFSFileService = gridFSFileService;
        this.shareService = shareService;
        this.draftTrackingService = draftTrackingService;
        this.historyService = historyService;
        this.draftChangeRepository = draftChangeRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String projectId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(required = false) String ownerEmail) {
        try {
            // VALIDATION: Check file size (max 300MB)
            long maxSize = 300 * 1024 * 1024; // 300MB
            if (file.getSize() > maxSize) {
                log.warn("File too large: {} bytes (max: {} bytes)", file.getSize(), maxSize);
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(Map.of(
                            "success", false,
                            "error", "File too large. Maximum file size is 300MB. Your file is " + (file.getSize() / (1024 * 1024)) + "MB"
                        ));
            }

            String actualProjectId = projectId;
            boolean isReplacement = false;
            String filename = file.getOriginalFilename();
            
            // Check for duplicate filename and use existing projectId if found
            if (ownerEmail != null && !ownerEmail.isEmpty()) {
                // First, check if filename conflicts with shared files
                if (shareService.isFilenameInSharedFiles(filename, ownerEmail)) {
                    log.warn("Upload blocked - filename conflicts with shared file: {} for user: {}", filename, ownerEmail);
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of(
                                "success", false, 
                                "error", "The file '" + filename + "' is already shared with you. Please upload with a different file name or version."
                            ));
                }
                
                // Then check if user owns a file with this name
                Optional<String> existingProjectId = metadataService.getExistingProjectId(filename, ownerEmail);
                if (existingProjectId.isPresent()) {
                    actualProjectId = existingProjectId.get();
                    isReplacement = true;
                    log.info("Replacing existing file: {} for user: {} with projectId: {}", filename, ownerEmail, actualProjectId);
                }
            }
            
            // FIX: Optimize by writing to both GridFS and filesystem in one pass
            // Save to local filesystem first
            Path projectDir = storageManager.prepareProjectDir(actualProjectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Files.createDirectories(original.getParent());

            // Write uploaded file directly to local filesystem
            try (InputStream in = file.getInputStream();
                 OutputStream out = Files.newOutputStream(original,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }

            log.info("Saved file to local filesystem: {}", original);

            // Then store in GridFS for backup/versioning
            String gridfsFileId;
            try (InputStream fileIn = Files.newInputStream(original)) {
                gridfsFileId = gridFSFileService.storeFile(
                    actualProjectId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    fileIn
                );
            }

            // FIX: Add error handling - verify GridFS storage succeeded
            if (gridfsFileId == null || gridfsFileId.isEmpty()) {
                throw new RuntimeException("Failed to store file in GridFS - no file ID returned");
            }

            log.info("Stored file in GridFS for project {}: fileId={}", actualProjectId, gridfsFileId);

            // FIX: Batch metadata updates into single operation for better performance
            ProjectStatus status = ProjectStatus.uploaded(file.getOriginalFilename());
            metadataService.updateProjectMetadata(actualProjectId, status, gridfsFileId, ownerEmail);

            importService.submitImport(actualProjectId, original, ownerEmail);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", actualProjectId,
                    "gridfsFileId", gridfsFileId,
                    "isReplacement", isReplacement,
                    "message", isReplacement ? "File replaced successfully, processing scheduled" : "Upload complete, processing scheduled"));
        } catch (IOException e) {
            log.error("Upload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/status/{projectId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String projectId) {
        return metadataService.readStatus(projectId)
                .map(status -> ResponseEntity.ok(Map.of("success", true, "data", status)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "Project not found")));
    }

    @GetMapping("/export/{projectId}")
    public ResponseEntity<Resource> export(@PathVariable String projectId,
                                           @RequestParam(defaultValue = "rdfxml") String format) {
        try {
            Path exportPath = storageManager.exportOntology(projectId, format);
            InputStreamResource resource = new InputStreamResource(Files.newInputStream(exportPath));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(exportPath))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + exportPath.getFileName())
                    .body(resource);
        } catch (Exception e) {
            log.error("Export failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/reload/{projectId}")
    public ResponseEntity<Map<String, Object>> reload(@PathVariable String projectId) {
        try {
            log.info("[RELOAD] Reloading project {} from saved file", projectId);
            
            // Find the original ontology file
            Path originalFile = storageManager.projectDir(projectId).resolve("ontology.original.owl");
            if (!Files.exists(originalFile)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "error", "Original ontology file not found"));
            }
            
            // Trigger re-import to reload GraphDB with the saved file
            importService.submitImport(projectId, originalFile);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Project reload initiated. Processing in background."
            ));
        } catch (Exception e) {
            log.error("[RELOAD] Reload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/save/{projectId}")
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String username) {
        
        // Get or create a lock object for this project
        Object lock = projectSaveLocks.computeIfAbsent(projectId, k -> new Object());
        
        // Synchronize on the project-specific lock to prevent concurrent saves
        synchronized (lock) {
            try {
                log.info("[SAVE] Save requested for project: {} by user: {} (acquiring lock)", projectId, username);

                // STEP 1: Get all unapplied drafts BEFORE applying them (for history recording)
                log.info("[SAVE] Fetching drafts to record in history...");
                java.util.List<DraftChange> drafts = draftChangeRepository.findByProjectIdAndAppliedFalseOrderByTimestampAsc(projectId);
                log.info("[SAVE] Found {} unapplied drafts", drafts.size());

                // STEP 2: Apply all unapplied drafts to GraphDB
                log.info("[SAVE] Applying drafts to GraphDB...");
                DraftTrackingService.ApplyDraftsResult draftResult = draftTrackingService.applyDrafts(projectId);
                
                if (!draftResult.isSuccess()) {
                    log.error("[SAVE] Failed to apply drafts: {}", draftResult.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of(
                            "success", false,
                            "error", "Failed to apply drafts: " + draftResult.getMessage()
                        ));
                }
                
                log.info("[SAVE] Applied {} draft changes", draftResult.getAppliedCount());

            // STEP 3: Export current state from GraphDB to file system
            Path exportPath = storageManager.exportOntology(projectId, "rdfxml");
            log.info("[SAVE] Ontology exported to: {}", exportPath);

            // STEP 4: Update BOTH original AND current files so changes persist when switching files
            Path originalPath = storageManager.projectDir(projectId).resolve("ontology.original.owl");
            Path currentPath = storageManager.projectDir(projectId).resolve("ontology.current.owl");
            
            if (Files.exists(exportPath)) {
                // Update original file
                if (!exportPath.equals(originalPath)) {
                    Files.copy(exportPath, originalPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("[SAVE] Updated original file: {}", originalPath);
                }
                
                // Update current file (this is what gets loaded when switching back)
                if (!exportPath.equals(currentPath)) {
                    Files.copy(exportPath, currentPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("[SAVE] Updated current file: {}", currentPath);
                }
            }

            // STEP 5: Update GridFS with the current state for backup/versioning
            try (InputStream in = Files.newInputStream(exportPath)) {
                String gridfsFileId = gridFSFileService.storeFile(
                    projectId,
                    "ontology.owl",
                    "application/rdf+xml",
                    in
                );
                metadataService.setGridfsFileId(projectId, gridfsFileId);
                log.info("[SAVE] Saved to GridFS with fileId: {}", gridfsFileId);
            }

            // STEP 6: Update status to COMPLETED after successful save
            ProjectStatus currentStatus = metadataService.readStatus(projectId)
                    .orElse(ProjectStatus.uploaded("ontology.owl"));
            ProjectStatus completedStatus = ProjectStatus.completed(currentStatus.filename());
            metadataService.writeStatus(projectId, completedStatus);
            log.info("[SAVE] Updated project status to COMPLETED");

            // STEP 7: Record changes to GraphDB history
            log.info("[SAVE] Recording {} changes to GraphDB history...", drafts.size());
            for (DraftChange draft : drafts) {
                String entityIRI = null;
                String entityLabel = null;
                String oldValue = null;
                String newValue = null;
                
                // Extract entity details from operation data
                Map<String, Object> opData = draft.getOperationData();
                if (opData != null) {
                    entityIRI = opData.containsKey("iri") ? opData.get("iri").toString() : null;
                    entityLabel = opData.containsKey("label") ? opData.get("label").toString() : null;
                    oldValue = opData.containsKey("oldValue") ? opData.get("oldValue").toString() : null;
                    newValue = opData.containsKey("newValue") ? opData.get("newValue").toString() : null;
                }
                
                historyService.recordEdit(
                    projectId,
                    userId != null ? userId : "system",
                    username != null ? username : "System",
                    draft.getOperationType(),
                    entityIRI,
                    entityLabel,
                    oldValue,
                    newValue,
                    draft.getOperationType() + " operation"
                );
            }
            log.info("[SAVE] GraphDB history recording complete");

            // STEP 8: Clear applied drafts (cleanup)
            draftTrackingService.clearAppliedDrafts(projectId);
            log.info("[SAVE] Cleared applied drafts");
            
            // STEP 9: Notify collaborators that a save completed
            if (draftResult.getAppliedCount() > 0) {
                Map<String, Object> saveNotification = Map.of(
                    "type", "PROJECT_SAVED",
                    "projectId", projectId,
                    "userId", userId != null ? userId : "system",
                    "username", username != null ? username : "System",
                    "appliedChanges", draftResult.getAppliedCount(),
                    "timestamp", System.currentTimeMillis(),
                    "message", (username != null ? username : "Someone") + " saved the project with " + draftResult.getAppliedCount() + " changes"
                );
                messagingTemplate.convertAndSend("/topic/ontology/" + projectId, saveNotification);
                log.info("[SAVE] Notified collaborators of save completion");
            }
            
            log.info("[SAVE] ✅ Save completed successfully, releasing lock");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Ontology saved successfully",
                    "projectId", projectId,
                    "savedPath", originalPath.toString(),
                    "appliedDrafts", draftResult.getAppliedCount()
            ));
            } catch (Exception e) {
                log.error("[SAVE] Save failed for project: {}", projectId, e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of(
                                "success", false,
                                "error", "Failed to save ontology: " + e.getMessage()
                        ));
            }
        }
    }

    /**
     * Get ontology content in specified format for code view
     * @param projectId The project ID
     * @param format The format (turtle, rdfxml, ntriples, jsonld) - defaults to rdfxml
     * @return Ontology content as plain text
     */
    @GetMapping("/{projectId}/content")
    public ResponseEntity<Map<String, Object>> getOntologyContent(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "rdfxml") String format) {
        try {
            log.info("Fetching ontology content for project: {} in format: {}", projectId, format);
            
            // Export the ontology in the requested format
            Path exportPath = storageManager.exportOntology(projectId, format);
            String content = Files.readString(exportPath);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "content", content,
                    "format", format,
                    "projectId", projectId
            ));
        } catch (Exception e) {
            log.error("Failed to get ontology content for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Failed to get ontology content: " + e.getMessage()
                    ));
        }
    }

    /**
     * Get the last modified timestamp for a project (for sync checking)
     */
    @GetMapping("/metadata/{projectId}/timestamp")
    public ResponseEntity<Map<String, Object>> getProjectTimestamp(@PathVariable String projectId) {
        try {
            log.debug("Fetching timestamp for project: {}", projectId);
            java.time.Instant updatedAt = metadataService.getUpdatedAt(projectId);
            
            if (updatedAt != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "projectId", projectId,
                        "updatedAt", updatedAt.toString()
                ));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "success", false,
                                "error", "Project not found"
                        ));
            }
        } catch (Exception e) {
            log.error("Failed to get timestamp for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Failed to get timestamp: " + e.getMessage()
                    ));
        }
    }
}

