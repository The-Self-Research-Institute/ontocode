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
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class ProjectLoadController {

    private static final Logger log = LoggerFactory.getLogger(ProjectLoadController.class);

    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;
    private final ProjectImportService importService;
    private final GridFSFileService gridFSFileService;

    public ProjectLoadController(StorageManager storageManager,
                                 ProjectMetadataService metadataService,
                                 ProjectImportService importService,
                                 GridFSFileService gridFSFileService) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
        this.importService = importService;
        this.gridFSFileService = gridFSFileService;
    }

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String projectId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(required = false) String ownerEmail) {
        try {
            // Store file in GridFS first
            String gridfsFileId = gridFSFileService.storeFile(
                projectId, 
                file.getOriginalFilename(), 
                file.getContentType(),
                file.getInputStream()
            );
            
            log.info("Stored file in GridFS for project {}: fileId={}", projectId, gridfsFileId);
            
            // Also save to local filesystem for processing
            Path projectDir = storageManager.prepareProjectDir(projectId);
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
            metadataService.writeStatus(projectId, status);
            
            // Set GridFS file ID mapping
            metadataService.setGridfsFileId(projectId, gridfsFileId);
            
            // Set owner email if provided
            if (ownerEmail != null && !ownerEmail.isEmpty()) {
                metadataService.setOwnerEmail(projectId, ownerEmail);
            }
            
            importService.submitImport(projectId, original);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "gridfsFileId", gridfsFileId,
                    "message", "Upload complete, processing scheduled"));
        } catch (IOException e) {
            log.error("Upload failed", e);
            metadataService.writeStatus(projectId, ProjectStatus.error(file.getOriginalFilename(),
                    "Upload failed: " + e.getMessage()));
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
            log.info("[CHANGE TRACKING] Save requested for project: {}", projectId);

            // Export current state from GraphDB to file system
            Path exportPath = storageManager.exportOntology(projectId, "rdfxml");
            log.info("[CHANGE TRACKING] Ontology saved to: {}", exportPath);

            // Update GridFS with the current state
            try (InputStream in = Files.newInputStream(exportPath)) {
                String gridfsFileId = gridFSFileService.storeFile(
                    projectId,
                    exportPath.getFileName().toString(),
                    "application/rdf+xml",
                    in
                );
                metadataService.setGridfsFileId(projectId, gridfsFileId);
                log.info("[CHANGE TRACKING] Updated GridFS file: {} for project: {}", gridfsFileId, projectId);
            }

            // Update last modified timestamp
            ProjectStatus status = metadataService.readStatus(projectId)
                    .orElse(ProjectStatus.uploaded("ontology.owl"));
            metadataService.writeStatus(projectId, status);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Ontology saved successfully",
                    "projectId", projectId,
                    "savedPath", exportPath.toString()
            ));
        } catch (Exception e) {
            log.error("[CHANGE TRACKING] Save failed for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Failed to save ontology: " + e.getMessage()
                    ));
        }
    }
}
