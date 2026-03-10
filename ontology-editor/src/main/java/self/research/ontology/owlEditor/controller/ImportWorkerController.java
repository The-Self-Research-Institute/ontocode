package self.research.ontology.owlEditor.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.dto.ImportWorkerRequest;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/import-worker")
@CrossOrigin(originPatterns = "*")
public class ImportWorkerController {

    private final GridFSFileService gridFSFileService;
    private final StorageManager storageManager;
    private final ProjectImportService importService;
    private final ProjectMetadataService metadataService;

    public ImportWorkerController(GridFSFileService gridFSFileService,
                                  StorageManager storageManager,
                                  ProjectImportService importService,
                                  ProjectMetadataService metadataService) {
        this.gridFSFileService = gridFSFileService;
        this.storageManager = storageManager;
        this.importService = importService;
        this.metadataService = metadataService;
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody ImportWorkerRequest request) {
        try {
            String projectId = request.getProjectId();
            if (projectId == null || projectId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "projectId is required"));
            }

            Path projectDir = storageManager.prepareProjectDir(projectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Files.createDirectories(original.getParent());

            if (request.getGridfsFileId() != null && !request.getGridfsFileId().isBlank()) {
                var resourceOpt = gridFSFileService.getFileById(request.getGridfsFileId());
                if (resourceOpt.isEmpty()) {
                    return ResponseEntity.status(404).body(Map.of("success", false, "error", "GridFS file not found"));
                }
                try (InputStream in = resourceOpt.get().getInputStream()) {
                    Files.copy(in, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "gridfsFileId is required"));
            }

            String filename = request.getFilename() != null ? request.getFilename() : original.getFileName().toString();
            metadataService.writeStatus(projectId, ProjectStatus.uploaded(filename));

            ImportOptions options = ImportOptions.builder()
                    .mode(parseMode(request.getImportMode()))
                    .partitionStrategy(parsePartition(request.getPartition()))
                    .build();

            importService.submitImport(projectId, original, request.getOwnerEmail(), options);

            return ResponseEntity.ok(Map.of("success", true, "message", "Import queued"));
        } catch (Exception e) {
            log.error("Failed to submit import worker request", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private ImportOptions.ImportMode parseMode(String mode) {
        if (mode == null) {
            return ImportOptions.ImportMode.FULL;
        }
        return switch (mode.toLowerCase()) {
            case "incremental" -> ImportOptions.ImportMode.INCREMENTAL;
            case "diff" -> ImportOptions.ImportMode.DIFF;
            default -> ImportOptions.ImportMode.FULL;
        };
    }

    private ImportOptions.PartitionStrategy parsePartition(String partition) {
        if (partition == null) {
            return ImportOptions.PartitionStrategy.NONE;
        }
        return partition.equalsIgnoreCase("namespace")
                ? ImportOptions.PartitionStrategy.NAMESPACE
                : ImportOptions.PartitionStrategy.NONE;
    }
}
