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
import self.research.ontology.owlEditor.model.ProjectStatus;
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

    public ProjectLoadController(StorageManager storageManager,
                                 ProjectMetadataService metadataService,
                                 ProjectImportService importService,
                                 GridFSFileService gridFSFileService,
                                 ProjectShareService shareService,
                                 DraftTrackingService draftTrackingService) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
        this.importService = importService;
        this.gridFSFileService = gridFSFileService;
        this.shareService = shareService;
        this.draftTrackingService = draftTrackingService;
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

    @PostMapping("/save/{projectId}")
    public ResponseEntity<Map<String, Object>> save(@PathVariable String projectId) {
        try {
            log.info("[SAVE] Save requested for project: {}", projectId);

            // STEP 1: Apply all unapplied drafts to GraphDB first
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

            // STEP 2: Export current state from GraphDB to file system
            Path exportPath = storageManager.exportOntology(projectId, "rdfxml");
            log.info("[SAVE] Ontology exported to: {}", exportPath);

            // STEP 3: Update the original file so changes persist when switching files
            Path originalPath = storageManager.projectDir(projectId).resolve("ontology.original.owl");
            if (Files.exists(exportPath) && !exportPath.equals(originalPath)) {
                Files.copy(exportPath, originalPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("[SAVE] Updated original file: {}", originalPath);
            }

            // STEP 4: Update GridFS with the current state for backup/versioning
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

            // STEP 5: Update last modified timestamp and metadata
            ProjectStatus status = metadataService.readStatus(projectId)
                    .orElse(ProjectStatus.uploaded("ontology.owl"));
            metadataService.writeStatus(projectId, status);
            log.info("[SAVE] Updated project status");

            // STEP 6: Clear applied drafts (cleanup)
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
}
