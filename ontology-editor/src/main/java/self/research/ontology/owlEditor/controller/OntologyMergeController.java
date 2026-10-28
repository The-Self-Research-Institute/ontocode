package self.research.ontology.owlEditor.controller;

import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import self.research.ontology.owlEditor.model.merge.*;
import self.research.ontology.common.disk.DiskSpaceGate;
import self.research.ontology.common.disk.InsufficientDiskSpaceException;
import self.research.ontology.owlEditor.service.BusyException;
import self.research.ontology.owlEditor.service.MergeInputTooLargeException;
import self.research.ontology.owlEditor.service.DraftMergeTooLargeException;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.OntologyMergeService;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.util.TempFiles;
import self.research.ontology.owlEditor.service.AccessDenial;
import self.research.ontology.owlEditor.service.MergeTargetResolver;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for ontology merging operations 
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/api/projects/{projectId}/merge")
public class OntologyMergeController {

    private static final Logger log = LoggerFactory.getLogger(OntologyMergeController.class);
    private static final String DESKTOP_USER_ID = "desktop-user-local";

    private final OntologyMergeService mergeService;
    private final MergeTargetResolver targetResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public OntologyMergeController(OntologyMergeService mergeService,
                                   MergeTargetResolver targetResolver) {
        this.mergeService = mergeService;
        this.targetResolver = targetResolver;
    }

    @org.springframework.beans.factory.annotation.Value("${ontocode.data.dir:./data}")
    private String dataDir;

    private Path saveSource(MultipartFile file) throws java.io.IOException {
        Path dir = dataDir == null ? null : Path.of(dataDir).toAbsolutePath().normalize().resolve("tmp");
        if (dir != null) {
            DiskSpaceGate.defaults().requireFree(dir, file.getSize() * 3);
        }
        return TempFiles.saveUpload(file, dir, "merge-source-", ".owl");
    }

    private ResponseEntity<?> inputTooLarge(MergeInputTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", e.getMessage(), "limitBytes", e.limitBytes()));
    }

    private String effectiveUserId(HttpServletRequest request, String userId, boolean draft) {
        if (desktopMode) {
            return DESKTOP_USER_ID;
        }
        return draft ? RequestActor.forScope(request, userId, true) : RequestActor.of(request, userId, null).userId();
    }

    private ResponseEntity<?> draftMergeRefusal(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private String effectiveUsername(HttpServletRequest request, String userId, String username) {
        return RequestActor.of(request, userId, username).username();
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
            @RequestParam(value = "targetFileName", required = false) String targetFileName,
            @RequestParam(value = "draft", defaultValue = "false") boolean draft,
            @RequestParam(value = "userId", required = false) String userId,
            HttpServletRequest httpRequest) {
        
        log.info("[MERGE] Analyzing merge for project {}, file: {}", projectId, file.getOriginalFilename());
        
        Path tempFile = null;
        try {
            // Validate file
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No file uploaded"));
            }
            
            tempFile = saveSource(file);
            
            // Analyze for conflicts
            String effectiveTargetProjectId = targetResolver.resolve(projectId, targetProjectId, targetFileName);
            Optional<AccessDenial> denial = targetResolver.denialFor(httpRequest, projectId, effectiveTargetProjectId);
            if (denial.isPresent()) {
                return denial.get().toResponse();
            }
            MergeAnalysisResult result = mergeService.analyzeOntologies(
                projectId + "_source",  // source project ID (not used, just for logging)
                tempFile,
                effectiveTargetProjectId,
                targetFileName,
                draft ? effectiveUserId(httpRequest, userId, true) : null
            );
            
            log.info("[MERGE] Analysis complete: {} conflicts found", result.getTotalConflicts());
            
            return ResponseEntity.ok(result);
            
        } catch (BusyException | InsufficientDiskSpaceException e) {
            throw e;
        } catch (MergeInputTooLargeException e) {
            return inputTooLarge(e);
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
            TempFiles.discard(tempFile);
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
            @RequestParam(value = "conflictResolutions", required = false) String conflictResolutionsJson,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "draft", defaultValue = "false") boolean draft,
            HttpServletRequest httpRequest) {

        log.info("[MERGE] Executing merge for project {}", projectId);
        log.info("[MERGE] Strategy: {}, Rename suffix: {}", strategy, renameSuffix);
        
        Path tempFile = null;
        try {
            tempFile = saveSource(file);
            
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
            String effectiveTargetProjectId = targetResolver.resolve(projectId, targetProjectId, targetFileName);
            Optional<AccessDenial> denial = targetResolver.denialFor(httpRequest, projectId, effectiveTargetProjectId);
            if (denial.isPresent()) {
                return denial.get().toResponse();
            }
            MergeResult result = mergeService.mergeOntologies(
                projectId + "_source",
                tempFile,
                effectiveTargetProjectId,
                targetFileName,
                outputFileName,
                options,
                effectiveUserId(httpRequest, userId, draft),
                effectiveUsername(httpRequest, userId, username),
                draft
            );
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);


            log.info("[MERGE] Merge complete in {}ms", duration);
            
            return ResponseEntity.ok(result);
            
        } catch (BusyException | InsufficientDiskSpaceException e) {
            throw e;
        } catch (MergeInputTooLargeException e) {
            return inputTooLarge(e);
        } catch (DraftNotReadyException | DraftMergeTooLargeException e) {
            return draftMergeRefusal(e);
        } catch (StackOverflowError soe) {
            log.error("[MERGE] Stack overflow during merge — ontology may be too large or circular", soe);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Merge failed: the ontology is too large or complex to serialize. Try splitting into smaller files."));
        } catch (Exception e) {
            log.error("[MERGE] Error executing merge", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to merge ontologies: " + e.getMessage()));
        } finally {
            TempFiles.discard(tempFile);
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
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "username", required = false) String username,
            @RequestBody MergeOptions options,
            HttpServletRequest httpRequest) {

        log.info("[MERGE] Executing merge with manual resolutions for project {}", projectId);
        log.info("[MERGE] Resolutions provided: {}", options.getConflictResolutions().size());
        
        Path tempFile = null;
        try {
            tempFile = saveSource(file);
            
            // Execute merge with resolutions
            long startTime = System.currentTimeMillis();
            MergeResult result = mergeService.mergeOntologies(
                projectId + "_source",
                tempFile,
                projectId,
                targetFileName,
                outputFileName,
                options,
                effectiveUserId(httpRequest, userId, false),
                effectiveUsername(httpRequest, userId, username)
            );
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);
            
            
            log.info("[MERGE] Merge with resolutions complete in {}ms", duration);
            
            return ResponseEntity.ok(result);
            
        } catch (BusyException | InsufficientDiskSpaceException e) {
            throw e;
        } catch (MergeInputTooLargeException e) {
            return inputTooLarge(e);
        } catch (StackOverflowError soe) {
            log.error("[MERGE] Stack overflow during merge-with-resolutions — ontology may be too large or circular", soe);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Merge failed: the ontology is too large or complex to serialize. Try splitting into smaller files."));
        } catch (Exception e) {
            log.error("[MERGE] Error executing merge with resolutions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to merge ontologies: " + e.getMessage()));
        } finally {
            TempFiles.discard(tempFile);
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
