package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.OntologyChangeRepository;
import self.research.ontology.owlEditor.service.DraftTrackingService;
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

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class ProjectLoadController {

    private static final Logger log = LoggerFactory.getLogger(ProjectLoadController.class);

    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;
    private final ProjectImportService importService;
    private final GridFSFileService gridFSFileService;
    private final ProjectShareService shareService;
    private final DraftTrackingService draftTrackingService;
    private final OntologyChangeRepository changeRepository;
    private final DraftChangeRepository draftChangeRepository;

    public ProjectLoadController(StorageManager storageManager,
                                 ProjectMetadataService metadataService,
                                 ProjectImportService importService,
                                 GridFSFileService gridFSFileService,
                                 ProjectShareService shareService,
                                 DraftTrackingService draftTrackingService,
                                 OntologyChangeRepository changeRepository,
                                 DraftChangeRepository draftChangeRepository) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
        this.importService = importService;
        this.gridFSFileService = gridFSFileService;
        this.shareService = shareService;
        this.draftTrackingService = draftTrackingService;
        this.changeRepository = changeRepository;
        this.draftChangeRepository = draftChangeRepository;
    }

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String projectId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(required = false) String ownerEmail) {
        try {
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
            
            // Store file in GridFS first
            String gridfsFileId = gridFSFileService.storeFile(
                actualProjectId, 
                file.getOriginalFilename(), 
                file.getContentType(),
                file.getInputStream()
            );
            
            log.info("Stored file in GridFS for project {}: fileId={}", actualProjectId, gridfsFileId);
            
            // Also save to local filesystem for processing
            Path projectDir = storageManager.prepareProjectDir(actualProjectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Files.createDirectories(original.getParent());
            
            // Retrieve from GridFS and save to local file
            gridFSFileService.getFileById(gridfsFileId).ifPresent(resource -> {
                try (InputStream in = resource.getInputStream();
                     OutputStream out = Files.newOutputStream(original,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to save file to local filesystem", e);
                }
            });
            
            ProjectStatus status = ProjectStatus.uploaded(file.getOriginalFilename());
            metadataService.writeStatus(actualProjectId, status);
            
            // Set GridFS file ID mapping
            metadataService.setGridfsFileId(actualProjectId, gridfsFileId);
            
            // Set owner email if provided
            if (ownerEmail != null && !ownerEmail.isEmpty()) {
                metadataService.setOwnerEmail(actualProjectId, ownerEmail);
            }
            
            importService.submitImport(actualProjectId, original);
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
        try {
            log.info("[SAVE] Save requested for project: {} by user: {}", projectId, username);

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

            // STEP 7: Record changes to history
            log.info("[SAVE] Recording {} changes to history...", drafts.size());
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            for (DraftChange draft : drafts) {
                OntologyChange change = new OntologyChange();
                change.setProjectId(projectId);
                change.setUserId(userId != null ? userId : "system");
                change.setUsername(username != null ? username : "System");
                change.setTimestamp(now);
                change.setChangeType(mapOperationTypeToChangeType(draft.getOperationType()));
                
                // Extract entity details from operation data
                Map<String, Object> opData = draft.getOperationData();
                if (opData != null) {
                    if (opData.containsKey("iri")) {
                        change.setEntityIRI(opData.get("iri").toString());
                    }
                    if (opData.containsKey("label")) {
                        change.setEntityLabel(opData.get("label").toString());
                    }
                    if (opData.containsKey("oldValue")) {
                        change.setOldValue(opData.get("oldValue").toString());
                    }
                    if (opData.containsKey("newValue")) {
                        change.setNewValue(opData.get("newValue").toString());
                    }
                }
                
                change.setDescription(draft.getOperationType() + " operation");
                changeRepository.save(change);
            }
            log.info("[SAVE] History recording complete");

            // STEP 8: Clear applied drafts (cleanup)
            draftTrackingService.clearAppliedDrafts(projectId);
            log.info("[SAVE] Cleared applied drafts");

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

    private OntologyChange.ChangeType mapOperationTypeToChangeType(String operationType) {
        return switch (operationType) {
            case "createClass" -> OntologyChange.ChangeType.CLASS_CREATED;
            case "deleteClass" -> OntologyChange.ChangeType.CLASS_DELETED;
            case "updateClassLabel" -> OntologyChange.ChangeType.CLASS_MODIFIED;
            case "addSubClass" -> OntologyChange.ChangeType.CLASS_MODIFIED;
            case "createObjectProperty" -> OntologyChange.ChangeType.PROPERTY_CREATED;
            case "createDataProperty" -> OntologyChange.ChangeType.PROPERTY_CREATED;
            case "deleteObjectProperty" -> OntologyChange.ChangeType.PROPERTY_DELETED;
            case "deleteDataProperty" -> OntologyChange.ChangeType.PROPERTY_DELETED;
            case "addAnnotation" -> OntologyChange.ChangeType.ANNOTATION_ADDED;
            case "deleteAnnotation" -> OntologyChange.ChangeType.ANNOTATION_DELETED;
            case "createIndividual" -> OntologyChange.ChangeType.INDIVIDUAL_CREATED;
            case "deleteIndividual" -> OntologyChange.ChangeType.INDIVIDUAL_DELETED;
            default -> OntologyChange.ChangeType.OTHER;
        };
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
}

