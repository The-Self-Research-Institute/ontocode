package self.research.ontology.owlEditor.controller;

import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import self.research.ontology.owlEditor.model.merge.*;
import self.research.ontology.owlEditor.service.OntologyMergeService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for ontology merging operations (similar to Protege PROMPT)
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/api/projects/{projectId}/merge")
public class OntologyMergeController {

    private static final Logger log = LoggerFactory.getLogger(OntologyMergeController.class);

    private final OntologyMergeService mergeService;
    private final ProjectMetadataService metadataService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OntologyMergeController(OntologyMergeService mergeService,
                                   ProjectMetadataService metadataService) {
        this.mergeService = mergeService;
        this.metadataService = metadataService;
    }

    /**
     * Analyze a source ontology for merge conflicts with the target project
     * 
     * POST /api/projects/{projectId}/merge/analyze
     * 
     * Request body: multipart/form-data with 'file' parameter
     * 
     * Response: MergeAnalysisResult with list of conflicts
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeMerge(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetProjectId", required = false) String targetProjectId,
            @RequestParam(value = "targetFileName", required = false) String targetFileName) {
        
        log.info("[MERGE] Analyzing merge for project {}, file: {}", projectId, file.getOriginalFilename());
        
        Path tempFile = null;
        try {
            // Validate file
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No file uploaded"));
            }
            
            // Save uploaded file temporarily
            tempFile = Files.createTempFile("merge-source-", ".owl");
            log.info("[MERGE] Saving uploaded file to: {}", tempFile);
            file.transferTo(tempFile.toFile());
            
            // Analyze for conflicts
            String effectiveTargetProjectId = resolveEffectiveTargetProjectId(projectId, targetProjectId, targetFileName);
            MergeAnalysisResult result = mergeService.analyzeOntologies(
                projectId + "_source",  // source project ID (not used, just for logging)
                tempFile,
                effectiveTargetProjectId,
                targetFileName
            );
            
            log.info("[MERGE] Analysis complete: {} conflicts found", result.getTotalConflicts());
            
            return ResponseEntity.ok(result);
            
        } catch (OWLOntologyCreationException e) {
            log.error("[MERGE] Error loading ontologies", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("[MERGE] Detailed error: {}", errorMsg, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Failed to load ontology: " + errorMsg, "details", e.toString()));
        } catch (Exception e) {
            log.error("[MERGE] Error analyzing ontologies", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("[MERGE] Detailed error: {}", errorMsg, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to analyze ontologies: " + errorMsg, "details", e.toString()));
        } finally {
            // Clean up temp file
            if (tempFile != null) {
                try {
                    // Try to delete immediately first
                    boolean deleted = Files.deleteIfExists(tempFile);
                    if (!deleted) {
                        // If deletion fails (Windows file locking), mark for deletion on JVM exit
                        tempFile.toFile().deleteOnExit();
                        log.debug("[MERGE] Temp file marked for deletion on exit: {}", tempFile);
                    }
                } catch (Exception e) {
                    // On Windows, file may still be locked by OWL API - mark for deletion on exit
                    tempFile.toFile().deleteOnExit();
                    log.debug("[MERGE] Temp file locked, marked for deletion on exit: {}", tempFile);
                }
            }
        }
    }

    /**
     * Perform the merge operation with specified options
     * 
     * POST /api/projects/{projectId}/merge/execute
     * 
     * Request body: multipart form with:
     *   - file: source ontology file
     *   - options: JSON string with merge options
     * 
     * Response: MergeResult with statistics
     */
    @PostMapping("/execute")
    public ResponseEntity<?> executeMerge(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strategy", defaultValue = "SIMPLE_UNION") MergeStrategy strategy,
            @RequestParam(value = "renameSuffix", required = false) String renameSuffix,
            @RequestParam(value = "targetProjectId", required = false) String targetProjectId,
            @RequestParam(value = "targetFileName", required = false) String targetFileName,
            @RequestParam(value = "outputFileName", required = false) String outputFileName,
            @RequestParam(value = "conflictResolutions", required = false) String conflictResolutionsJson) {
        
        log.info("[MERGE] Executing merge for project {}", projectId);
        log.info("[MERGE] Strategy: {}, Rename suffix: {}", strategy, renameSuffix);
        
        try {
            // Save uploaded file temporarily
            Path tempFile = Files.createTempFile("merge-source-", ".owl");
            file.transferTo(tempFile.toFile());
            
            // Create merge options
            MergeOptions options = new MergeOptions();
            options.setStrategy(strategy);
            if (renameSuffix != null) {
                options.setRenameSuffix(renameSuffix);
            }
            if (conflictResolutionsJson != null && !conflictResolutionsJson.isBlank()) {
                Map<String, ConflictResolution> parsed = objectMapper.readValue(
                    conflictResolutionsJson,
                    new TypeReference<Map<String, ConflictResolution>>() {}
                );
                options.setConflictResolutions(parsed);
            }
            
            // Execute merge
            long startTime = System.currentTimeMillis();
            String effectiveTargetProjectId = resolveEffectiveTargetProjectId(projectId, targetProjectId, targetFileName);
            MergeResult result = mergeService.mergeOntologies(
                projectId + "_source",
                tempFile,
                effectiveTargetProjectId,
                targetFileName,
                outputFileName,
                options
            );
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);
            
            // Clean up temp file (handle Windows file locking)
            try {
                boolean deleted = Files.deleteIfExists(tempFile);
                if (!deleted) {
                    tempFile.toFile().deleteOnExit();
                }
            } catch (Exception ex) {
                tempFile.toFile().deleteOnExit();
                log.debug("[MERGE] Temp file locked, marked for deletion on exit: {}", tempFile);
            }
            
            log.info("[MERGE] Merge complete in {}ms", duration);
            
            return ResponseEntity.ok(result);
            
        } catch (StackOverflowError soe) {
            log.error("[MERGE] Stack overflow during merge — ontology may be too large or circular", soe);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Merge failed: the ontology is too large or complex to serialize. Try splitting into smaller files."));
        } catch (Exception e) {
            log.error("[MERGE] Error executing merge", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to merge ontologies: " + e.getMessage()));
        }
    }

    /**
     * Execute merge with manual conflict resolution
     * 
     * POST /api/projects/{projectId}/merge/execute-with-resolutions
     * 
     * Request: multipart with file and JSON options
     */
    @PostMapping("/execute-with-resolutions")
    public ResponseEntity<?> executeMergeWithResolutions(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetFileName", required = false) String targetFileName,
            @RequestParam(value = "outputFileName", required = false) String outputFileName,
            @RequestBody MergeOptions options) {
        
        log.info("[MERGE] Executing merge with manual resolutions for project {}", projectId);
        log.info("[MERGE] Resolutions provided: {}", options.getConflictResolutions().size());
        
        try {
            // Save uploaded file temporarily
            Path tempFile = Files.createTempFile("merge-source-", ".owl");
            file.transferTo(tempFile.toFile());
            
            // Execute merge with resolutions
            long startTime = System.currentTimeMillis();
            MergeResult result = mergeService.mergeOntologies(
                projectId + "_source",
                tempFile,
                projectId,
                targetFileName,
                outputFileName,
                options
            );
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);
            
            // Clean up temp file (handle Windows file locking)
            try {
                boolean deleted = Files.deleteIfExists(tempFile);
                if (!deleted) {
                    tempFile.toFile().deleteOnExit();
                }
            } catch (Exception ex) {
                tempFile.toFile().deleteOnExit();
                log.debug("[MERGE] Temp file locked, marked for deletion on exit: {}", tempFile);
            }
            
            log.info("[MERGE] Merge with resolutions complete in {}ms", duration);
            
            return ResponseEntity.ok(result);
            
        } catch (StackOverflowError soe) {
            log.error("[MERGE] Stack overflow during merge-with-resolutions — ontology may be too large or circular", soe);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Merge failed: the ontology is too large or complex to serialize. Try splitting into smaller files."));
        } catch (Exception e) {
            log.error("[MERGE] Error executing merge with resolutions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to merge ontologies: " + e.getMessage()));
        }
    }

    /**
     * Get merge strategies available
     * 
     * GET /api/projects/{projectId}/merge/strategies
     */
    @GetMapping("/strategies")
    public ResponseEntity<?> getMergeStrategies() {
        Map<String, String> strategies = new HashMap<>();
        strategies.put("SIMPLE_UNION", "Combine all axioms (duplicates kept as-is)");
        strategies.put("REPLACE_DUPLICATES", "Source overwrites target for conflicts");
        strategies.put("KEEP_BOTH", "Rename conflicting source entities");
        strategies.put("MANUAL_RESOLUTION", "Specify resolution for each conflict");
        
        return ResponseEntity.ok(strategies);
    }

    /**
     * Get resolution actions available
     * 
     * GET /api/projects/{projectId}/merge/resolution-actions
     */
    @GetMapping("/resolution-actions")
    public ResponseEntity<?> getResolutionActions() {
        Map<String, String> actions = new HashMap<>();
        actions.put("KEEP_SOURCE", "Keep source version");
        actions.put("KEEP_TARGET", "Keep target version");
        actions.put("RENAME_SOURCE", "Rename source and keep both");
        actions.put("MERGE", "Merge both versions (keep all axioms)");
        actions.put("SKIP", "Skip this entity");
        
        return ResponseEntity.ok(actions);
    }

    private String resolveEffectiveTargetProjectId(String defaultProjectId,
                                                   String targetProjectId,
                                                   String targetFileName) {
        if (targetProjectId != null && !targetProjectId.isBlank()) {
            if (metadataService.readStatus(targetProjectId).isPresent()) {
                return targetProjectId;
            }
            log.warn("[MERGE] Ignoring targetProjectId '{}' because no project status was found. Falling back to filename/default resolution.",
                    targetProjectId);
        }

        if (targetFileName != null && !targetFileName.isBlank()) {
            return metadataService.getProjectIdByFilename(targetFileName)
                    .map(foundProjectId -> {
                        log.info("[MERGE] Resolved target filename '{}' to project '{}'", targetFileName, foundProjectId);
                        return foundProjectId;
                    })
                    .orElse(defaultProjectId);
        }

        return defaultProjectId;
    }

    /**
     * Get merge operation status/history
     * 
     * GET /api/projects/{projectId}/merge/history
     */
    @GetMapping("/history")
    public ResponseEntity<?> getMergeHistory(@PathVariable String projectId) {
        // TODO: Implement merge history tracking
        return ResponseEntity.ok(Map.of("message", "Merge history not yet implemented"));
    }
}
