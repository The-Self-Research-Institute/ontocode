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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.apache.commons.io.input.TeeInputStream;

import java.util.Locale;

import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.GraphDBDatasetService;
import self.research.ontology.owlEditor.service.GraphDBHistoryService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.OntologyPreparseService;
import self.research.ontology.owlEditor.service.ImportWorkerDispatcher;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.List;
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
    private final GraphDBDatasetService datasetService;
    private final ProjectRepository projectRepository;
    private final OntologyPreparseService preparseService;
    private final ImportWorkerDispatcher importWorkerDispatcher;

    public ProjectLoadController(StorageManager storageManager,
                                 ProjectMetadataService metadataService,
                                 ProjectImportService importService,
                                 GridFSFileService gridFSFileService,
                                 ProjectShareService shareService,
                                 DraftTrackingService draftTrackingService,
                                 GraphDBHistoryService historyService,
                                 DraftChangeRepository draftChangeRepository,
                                 SimpMessagingTemplate messagingTemplate,
                                 GraphDBDatasetService datasetService,
                                 ProjectRepository projectRepository,
                                 OntologyPreparseService preparseService,
                                 ImportWorkerDispatcher importWorkerDispatcher) {
        this.storageManager = storageManager;
        this.metadataService = metadataService;
        this.importService = importService;
        this.gridFSFileService = gridFSFileService;
        this.shareService = shareService;
        this.draftTrackingService = draftTrackingService;
        this.historyService = historyService;
        this.draftChangeRepository = draftChangeRepository;
        this.messagingTemplate = messagingTemplate;
        this.datasetService = datasetService;
        this.projectRepository = projectRepository;
        this.preparseService = preparseService;
        this.importWorkerDispatcher = importWorkerDispatcher;
    }

    @PostMapping("/upload/{projectId:.+}")  // Allow slashes in path variable
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String projectId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(required = false) String ownerEmail,
                                                      @RequestParam(required = false) String action,
                                                      @RequestParam(required = false) String importMode,
                                                      @RequestParam(required = false) String partition,
                                                      @RequestParam(required = false) String workspaceId,
                                                      @RequestParam(required = false) String parentProjectId,
                                                      @RequestParam(required = false, defaultValue = "false") boolean compressed) {
        log.info("[ProjectLoadController] Upload request - projectId: {}, filename: {}, ownerEmail: {}, workspaceId: {}, parentProjectId: {}, action: {}, compressed: {}",
            projectId, file.getOriginalFilename(), ownerEmail, workspaceId, parentProjectId, action, compressed);
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
            
            // Skip duplicate check for hierarchical project IDs (files from project library)
            // Hierarchical IDs like "proj-123--file-456" are already unique
            // Note: Using -- separator to avoid URL encoding issues with / (%2F)
            boolean isHierarchicalId = projectId.contains("--");
            
            // Check for duplicate filename and handle based on action parameter
            if (!isHierarchicalId && ownerEmail != null && !ownerEmail.isEmpty()) {
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
                    // Handle based on action parameter
                    if ("replace".equals(action)) {
                        // Replace existing file
                        actualProjectId = existingProjectId.get();
                        isReplacement = true;
                        log.info("Replacing existing file: {} for user: {} with projectId: {}", filename, ownerEmail, actualProjectId);
                    } else if ("create_copy".equals(action)) {
                        // Create a copy with modified filename
                        String copyFilename = generateCopyFilename(filename, ownerEmail);
                        filename = copyFilename;
                        // Use the provided projectId for the new copy
                        log.info("Creating copy with new filename: {} for user: {} with projectId: {}", copyFilename, ownerEmail, actualProjectId);
                    } else {
                        // No action specified - return conflict for user decision
                        log.warn("Duplicate file detected, awaiting user decision: {} for user: {}", filename, ownerEmail);
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of(
                                    "success", false,
                                    "isDuplicate", true,
                                    "projectId", existingProjectId.get(),
                                    "filename", filename,
                                    "error", "A file with the name '" + filename + "' already exists. Please choose to replace or create a copy."
                                ));
                    }
                }
            }
            
            // Optimize by writing to filesystem and GridFS in one pass
            Path projectDir = storageManager.prepareProjectDir(actualProjectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Files.createDirectories(original.getParent());

            String gridfsFileId;
            
            // Auto-detect GZIP compression
            InputStream fileStream = file.getInputStream();
            InputStream effectiveStream = fileStream;
            boolean wasCompressed = compressed;
            
            if (!compressed) {
                PushbackInputStream pb = new PushbackInputStream(fileStream, 2);
                byte[] signature = new byte[2];
                int len = pb.read(signature);
                if (len > 0) {
                    pb.unread(signature, 0, len);
                }
                
                if (len == 2 && signature[0] == (byte) 0x1f && signature[1] == (byte) 0x8b) {
                    log.info("[ProjectLoadController] Auto-detected GZIP content. Enabling decompression.");
                    effectiveStream = new GZIPInputStream(pb);
                    wasCompressed = true;
                } else {
                    effectiveStream = pb;
                }
            } else {
                effectiveStream = new GZIPInputStream(fileStream);
            }

            try (InputStream in = effectiveStream;
                 OutputStream out = Files.newOutputStream(original,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE);
                 TeeInputStream tee = new TeeInputStream(in, out, true)) {

                if (wasCompressed) {
                    log.info("[ProjectLoadController] Decompressing gzipped file before processing");
                }

                gridfsFileId = gridFSFileService.storeFile(
                    actualProjectId,
                    filename,  // Use potentially modified filename
                    file.getContentType(),
                    tee
                );
            }

            // FIX: Add error handling - verify GridFS storage succeeded
            if (gridfsFileId == null || gridfsFileId.isEmpty()) {
                throw new RuntimeException("Failed to store file in GridFS - no file ID returned");
            }

            log.info("Stored file in GridFS for project {}: fileId={}", actualProjectId, gridfsFileId);

            // Strip binary garbage bytes that the upload pipeline may prepend before XML content.
            // This fixes the file on disk so ALL downstream consumers (preparse, import, conversion)
            // get a clean file without needing their own stripping logic.
            OWLFormatConverter.sanitizeFileOnDisk(original);

            // Extract citation-entity mappings from uploaded file for smart repositioning
            // This must be done BEFORE GraphDB import, as GraphDB will reorganize the content
            log.info("Extracting citation-entity mappings from uploaded file: {}", filename);
            storageManager.extractCitationMappingsFromFile(original, actualProjectId);

            // FIX: Batch metadata updates into single operation for better performance
            // Use the potentially modified filename
            ProjectStatus status = ProjectStatus.uploaded(filename);
            metadataService.updateProjectMetadata(actualProjectId, status, gridfsFileId, ownerEmail, workspaceId, parentProjectId);

            ImportOptions options = resolveImportOptions(importMode, partition);
            importWorkerDispatcher.dispatch(actualProjectId, original, ownerEmail, filename, gridfsFileId, options);

            RDFFormat format = detectFormat(original);
            preparseService.preparse(original, actualProjectId, format);
            
            String message = isReplacement ? "File replaced successfully, processing scheduled" : 
                            ("create_copy".equals(action) ? "Copy created successfully with name '" + filename + "', processing scheduled" :
                            "Upload complete, processing scheduled");
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", actualProjectId,
                    "gridfsFileId", gridfsFileId,
                    "isReplacement", isReplacement,
                    "filename", filename,
                    "message", message));
        } catch (IOException e) {
            log.error("Upload failed (IO)", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Upload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error",
                            e.getMessage() != null ? e.getMessage() : "Unexpected upload error"));
        }
    }
    
    /**
     * Generate a unique filename for a copy by adding a numeric suffix
     * @param originalFilename The original filename
     * @param ownerEmail The owner's email
     * @return A unique filename with suffix (e.g., "ontology-copy-1.owl")
     */
    private String generateCopyFilename(String originalFilename, String ownerEmail) {
        // Extract base name and extension
        String baseName;
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = originalFilename.substring(0, dotIndex);
            extension = originalFilename.substring(dotIndex);
        } else {
            baseName = originalFilename;
        }
        
        // Try incrementing suffixes until we find one that doesn't exist
        int copyNumber = 1;
        String candidateFilename;
        do {
            candidateFilename = baseName + "-copy-" + copyNumber + extension;
            copyNumber++;
        } while (metadataService.isDuplicateFilename(candidateFilename, ownerEmail));
        
        log.info("Generated copy filename: {} from original: {}", candidateFilename, originalFilename);
        return candidateFilename;
    }

    private ImportOptions resolveImportOptions(String importMode, String partition) {
        ImportOptions.ImportMode mode = ImportOptions.ImportMode.FULL;
        if (importMode != null) {
            switch (importMode.toLowerCase(Locale.ROOT)) {
                case "incremental" -> mode = ImportOptions.ImportMode.INCREMENTAL;
                case "diff" -> mode = ImportOptions.ImportMode.DIFF;
                default -> mode = ImportOptions.ImportMode.FULL;
            }
        }

        ImportOptions.PartitionStrategy strategy = ImportOptions.PartitionStrategy.NONE;
        if (partition != null && partition.equalsIgnoreCase("namespace")) {
            strategy = ImportOptions.PartitionStrategy.NAMESPACE;
        }

        return ImportOptions.builder()
                .mode(mode)
                .partitionStrategy(strategy)
                .build();
    }

    private RDFFormat detectFormat(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        
        // Unambiguous extensions - trust the extension
        if (fileName.endsWith(".ttl") || fileName.endsWith(".turtle")) {
            return RDFFormat.TURTLE;
        } else if (fileName.endsWith(".nt") || fileName.endsWith(".ntriples")) {
            return RDFFormat.NTRIPLES;
        } else if (fileName.endsWith(".jsonld")) {
            return RDFFormat.JSONLD;
        } else if (fileName.endsWith(".n3")) {
            return RDFFormat.N3;
        }
        
        // Ambiguous extensions (.owl, .rdf) - inspect content
        if (fileName.endsWith(".owl") || fileName.endsWith(".rdf")) {
            RDFFormat detectedFormat = detectFormatByContent(file);
            if (detectedFormat != null) {
                log.info("Detected format by content for {}: {}", fileName, detectedFormat);
                return detectedFormat;
            }
        }
        
        // Default to RDF/XML
        return RDFFormat.RDFXML;
    }
    
    /**
     * Detect RDF format by inspecting file content
     * @param file The file to inspect
     * @return Detected format or null if unable to detect
     */
    private RDFFormat detectFormatByContent(Path file) {
        try {
            // Read first 2KB to detect format
            byte[] header = java.nio.file.Files.readAllBytes(file);
            int readLength = Math.min(2048, header.length);
            
            // Skip UTF-8 BOM if present
            int offset = 0;
            if (header.length >= 3 && header[0] == (byte) 0xEF && 
                header[1] == (byte) 0xBB && header[2] == (byte) 0xBF) {
                offset = 3;
            }
            
            // Skip leading whitespace
            while (offset < readLength && (header[offset] == ' ' || header[offset] == '\t' || 
                   header[offset] == '\n' || header[offset] == '\r')) {
                offset++;
            }
            
            String content = new String(header, offset, Math.min(readLength - offset, 1024), 
                                       java.nio.charset.StandardCharsets.UTF_8);
            String contentLower = content.toLowerCase(Locale.ROOT);
            
            // Check for Turtle/N3 markers
            if (contentLower.startsWith("@prefix") || contentLower.startsWith("@base") ||
                contentLower.contains("@prefix ") || contentLower.contains("@base ")) {
                log.info("Detected Turtle format (found @prefix or @base directive)");
                return RDFFormat.TURTLE;
            }
            
            // Check for N-Triples (subject-predicate-object with full URIs)
            if (content.matches("(?s)^\\s*<[^>]+>\\s+<[^>]+>\\s+.*")) {
                log.info("Detected N-Triples format");
                return RDFFormat.NTRIPLES;
            }
            
            // Check for XML markers
            if (contentLower.startsWith("<?xml") || contentLower.contains("<rdf:rdf") || 
                contentLower.contains("<owl:ontology") || contentLower.contains("<ontology")) {
                log.info("Detected RDF/XML format (found XML markers)");
                return RDFFormat.RDFXML;
            }
            
            // Check for JSON-LD
            if (contentLower.trim().startsWith("{") && contentLower.contains("@context")) {
                log.info("Detected JSON-LD format");
                return RDFFormat.JSONLD;
            }
            
            // Unable to detect - return null to use default
            log.warn("Unable to detect format by content, will use default");
            return null;
            
        } catch (Exception e) {
            log.warn("Failed to detect format by content: {}", e.getMessage());
            return null;
        }
    }

    @GetMapping("/status/{projectId:.+}")  // Allow slashes in path variable
    public ResponseEntity<Map<String, Object>> status(@PathVariable String projectId) {
        return metadataService.readStatus(projectId)
                .map(status -> ResponseEntity.ok(Map.of("success", true, "data", status)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "Project not found")));
    }

    @GetMapping("/export/{projectId:.+}")
    public ResponseEntity<Resource> export(@PathVariable String projectId,
                                           @RequestParam(defaultValue = "rdfxml") String format) {
        try {
            Path exportPath;
            
            // Check for cached code view content first (preserves citation line positions)
            Optional<String> cachedContent = storageManager.getCodeViewCache(projectId, format);
            if (cachedContent.isPresent()) {
                log.info("[EXPORT] Using cached code view content to preserve citation positions for project: {}, format: {}", 
                         projectId, format);
                
                // Write cached content to temporary export file
                String extension = storageManager.extensionFor(format);
                exportPath = storageManager.projectDir(projectId).resolve("ontology.export." + extension);
                Files.writeString(exportPath, cachedContent.get());
            } else {
                // No cache - export from GraphDB (default behavior)
                log.info("[EXPORT] No cache found, exporting from GraphDB for project: {}, format: {}", projectId, format);
                exportPath = storageManager.exportOntology(projectId, format);
            }
            
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

    @PostMapping("/reload/{projectId:.+}")
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

    @PostMapping("/save/{projectId:.+}")
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
                String annotationProperty = null;
                
                // Extract entity details from operation data
                Map<String, Object> opData = draft.getOperationData();
                if (opData != null) {
                    entityIRI = opData.containsKey("iri") ? opData.get("iri").toString() : null;
                    entityLabel = opData.containsKey("label") ? opData.get("label").toString() : null;
                    oldValue = opData.containsKey("oldValue") ? opData.get("oldValue").toString() : null;
                    // newValue can be stored as "value" or "newValue"
                    newValue = opData.containsKey("value") ? opData.get("value").toString() : 
                               (opData.containsKey("newValue") ? opData.get("newValue").toString() : null);
                    annotationProperty = opData.containsKey("property") ? opData.get("property").toString() : null;
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
                    draft.getOperationType() + " operation",
                    annotationProperty
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
     * Check if a file with the same name already exists for the user
     * @param filename The filename to check
     * @param ownerEmail The user's email
     * @return Conflict information if duplicate exists
     */
    @GetMapping("/check-duplicate")
    public ResponseEntity<Map<String, Object>> checkDuplicate(
            @RequestParam String filename,
            @RequestParam String ownerEmail) {
        try {
            log.info("[CHECK-DUPLICATE] Checking for duplicate - filename: {}, ownerEmail: {}", filename, ownerEmail);
            
            // Check if filename conflicts with shared files
            if (shareService.isFilenameInSharedFiles(filename, ownerEmail)) {
                log.warn("[CHECK-DUPLICATE] Filename conflicts with shared file: {} for user: {}", filename, ownerEmail);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                            "success", false,
                            "isDuplicate", true,
                            "error", "The file '" + filename + "' is already shared with you. Please upload with a different file name or version."
                        ));
            }
            
            // Check if user owns a file with this name
            Optional<String> existingProjectId = metadataService.getExistingProjectId(filename, ownerEmail);
            if (existingProjectId.isPresent()) {
                String projectId = existingProjectId.get();
                log.info("[CHECK-DUPLICATE] Found duplicate file - projectId: {}", projectId);
                
                // Get file metadata
                Optional<ProjectStatus> statusOpt = metadataService.readStatus(projectId);
                
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "isDuplicate", true,
                    "projectId", projectId,
                    "filename", filename,
                    "message", "A file with the name '" + filename + "' already exists.",
                    "status", statusOpt.map(ProjectStatus::status).orElse("UNKNOWN"),
                    "lastUpdated", statusOpt.map(s -> s.updatedAt() != null ? s.updatedAt().toString() : "").orElse("")
                ));
            }
            
            log.info("[CHECK-DUPLICATE] No duplicate found");
            return ResponseEntity.ok(Map.of(
                "success", true,
                "isDuplicate", false,
                "message", "No duplicate file found"
            ));
            
        } catch (Exception e) {
            log.error("[CHECK-DUPLICATE] Error checking duplicate", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                        "success", false,
                        "error", "Failed to check for duplicate: " + e.getMessage()
                    ));
        }
    }

    /**
     * Check if a file/ontology is already loaded into GraphDB for a specific project
     * This endpoint helps prevent duplicate data being loaded into the same project graph
     * @param projectId The project ID
     * @param fileName The file name to check
     * @param fileId Optional file ID
     * @return Map with exists boolean and details about existing data
     */
    @GetMapping("/{projectId:.+}/graphdb/check")
    public ResponseEntity<Map<String, Object>> checkGraphDBDuplicate(
            @PathVariable String projectId,
            @RequestParam String fileName,
            @RequestParam(required = false) String fileId) {
        try {
            log.info("[CHECK-GRAPHDB-DUPLICATE] Checking GraphDB for project: {}, fileName: {}, fileId: {}", 
                projectId, fileName, fileId);
            
            // Call the GraphDB service to check if file is already loaded
            Map<String, Object> checkResult = datasetService.checkFileExistsInGraphDB(projectId, fileName, fileId);
            
            boolean exists = (Boolean) checkResult.getOrDefault("exists", false);
            boolean checkFailed = (Boolean) checkResult.getOrDefault("checkFailed", false);
            
            if (checkFailed) {
                log.warn("[CHECK-GRAPHDB-DUPLICATE] Check failed: {}", checkResult.get("error"));
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "exists", false,
                    "checkSkipped", true,
                    "message", "GraphDB check could not be performed, proceeding with caution"
                ));
            }
            
            if (exists) {
                log.info("[CHECK-GRAPHDB-DUPLICATE] Found existing data in GraphDB - graphSize: {}", 
                    checkResult.get("graphSize"));
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "exists", true,
                    "projectId", projectId,
                    "fileName", fileName,
                    "graphSize", checkResult.getOrDefault("graphSize", 0),
                    "ontologyIRIs", checkResult.getOrDefault("ontologyIRIs", List.of()),
                    "message", checkResult.getOrDefault("message", "Data already exists in GraphDB")
                ));
            }
            
            log.info("[CHECK-GRAPHDB-DUPLICATE] No existing data found in GraphDB");
            return ResponseEntity.ok(Map.of(
                "success", true,
                "exists", false,
                "message", "No existing data in GraphDB, file can be loaded"
            ));
            
        } catch (Exception e) {
            log.error("[CHECK-GRAPHDB-DUPLICATE] Error checking GraphDB duplicate", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                        "success", false,
                        "error", "Failed to check GraphDB: " + e.getMessage()
                    ));
        }
    }

    /**
     * Get ontology content in specified format for code view
     * @param projectId The project ID
     * @param format The format (turtle, rdfxml, ntriples, jsonld, owlxml, manchester, functional) - defaults to rdfxml
     * @return Ontology content as plain text
     */
    @GetMapping("/{projectId:.+}/content")
    public ResponseEntity<Map<String, Object>> getOntologyContent(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "rdfxml") String format,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Fetching ontology content for project: {} in format: {}, forceRefresh: {}", projectId, format, forceRefresh);
            
            // Check for cached code view content first (preserves line positions)
            if (!forceRefresh) {
                Optional<String> cachedContent = storageManager.getCodeViewCache(projectId, format);
                if (cachedContent.isPresent()) {
                    log.info("Returning cached code view content for project: {} in format: {}", projectId, format);
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "content", cachedContent.get(),
                            "format", format,
                            "projectId", projectId,
                            "cached", true
                    ));
                }
            }
            
            // No cache or force refresh - export from GraphDB
            Path exportPath = storageManager.exportOntology(projectId, format);
            String content = Files.readString(exportPath);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "content", content,
                    "format", format,
                    "projectId", projectId,
                    "cached", false
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
     * Store code view content in cache to preserve line positions.
     * POST /api/ontology/{projectId}/code-view-cache
     * This is used when the user inserts citations at specific lines.
     * Optionally accepts citation-entity mappings for smart repositioning.
     */
    @PostMapping("/{projectId:.+}/code-view-cache")
    public ResponseEntity<Map<String, Object>> storeCodeViewCache(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request) {
        try {
            String content = (String) request.get("content");
            String format = (String) request.getOrDefault("format", "rdfxml");
            
            if (content == null || content.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Content is required"));
            }
            
            log.info("Storing code view cache for project: {} in format: {}, size: {} bytes", 
                     projectId, format, content.length());
            
            storageManager.storeCodeViewCache(projectId, content, format);
            
            // Store citation-entity mapping if provided (for smart repositioning)
            String citationUrn = (String) request.get("citationUrn");
            String referencedEntity = (String) request.get("referencedEntity");
            
            if (citationUrn != null && referencedEntity != null && !referencedEntity.isEmpty()) {
                try {
                    storageManager.storeCitationEntityMapping(projectId, citationUrn, referencedEntity);
                    log.info("Stored citation-entity mapping: {} -> {}", citationUrn, referencedEntity);
                } catch (Exception e) {
                    log.warn("Failed to store citation-entity mapping for project: {}", projectId, e);
                    // Don't fail the whole request, metadata is optional
                }
            }
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "format", format,
                    "message", "Code view cache stored successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to store code view cache for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Failed to store code view cache: " + e.getMessage()
                    ));
        }
    }

    /**
     * Clear code view cache for a project.
     * DELETE /api/ontology/{projectId}/code-view-cache
     * Optionally specify format to clear only specific format cache.
     */
    @DeleteMapping("/{projectId:.+}/code-view-cache")
    public ResponseEntity<Map<String, Object>> clearCodeViewCache(
            @PathVariable String projectId,
            @RequestParam(required = false) String format) {
        try {
            log.info("Clearing code view cache for project: {}, format: {}", projectId, format);
            
            if (format != null && !format.isEmpty()) {
                storageManager.clearCodeViewCacheFormat(projectId, format);
            } else {
                storageManager.clearCodeViewCache(projectId);
            }
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "message", "Code view cache cleared successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to clear code view cache for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Failed to clear code view cache: " + e.getMessage()
                    ));
        }
    }

    /**
     * Save code view content and sync across all formats.
     * Reimports the edited content into GraphDB and clears all format caches
     * so other formats re-export fresh from the updated GraphDB.
     * POST /api/ontology/{projectId}/code-view-save
     */
    @PostMapping("/{projectId:.+}/code-view-save")
    public ResponseEntity<Map<String, Object>> saveCodeViewAndSync(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request) {
        try {
            String content = (String) request.get("content");
            String format = (String) request.getOrDefault("format", "turtle");

            if (content == null || content.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Content is required"));
            }

            log.info("[CODE-VIEW-SAVE] Saving and syncing code view for project: {} in format: {}, size: {} bytes",
                     projectId, format, content.length());

            // Step 1: Determine the RDF format for GraphDB import
            boolean isOwlApiFormat = format.equalsIgnoreCase("owlxml")
                    || format.equalsIgnoreCase("manchester")
                    || format.equalsIgnoreCase("manchestersyntax")
                    || format.equalsIgnoreCase("functional")
                    || format.equalsIgnoreCase("functionalsyntax");

            RDFFormat rdfFormat;
            byte[] importBytes;

            if (isOwlApiFormat) {
                // OWL API formats need conversion to RDF/XML before GraphDB import
                String ext = storageManager.extensionFor(format);
                Path tempFile = Files.createTempFile("codeview-", "." + ext);
                try {
                    Files.writeString(tempFile, content, StandardCharsets.UTF_8);
                    Path convertedFile = OWLFormatConverter.convertToRDFXML(tempFile);
                    importBytes = Files.readAllBytes(convertedFile);
                    Files.deleteIfExists(convertedFile);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
                rdfFormat = RDFFormat.RDFXML;
                log.info("[CODE-VIEW-SAVE] Converted {} to RDF/XML ({} bytes)", format, importBytes.length);
            } else {
                // Standard RDF formats — write to temp file and sanitize (like import pipeline)
                String ext = storageManager.extensionFor(format);
                Path tempFile = Files.createTempFile("codeview-", "." + ext);
                try {
                    Files.writeString(tempFile, content, StandardCharsets.UTF_8);
                    // Sanitize: fixes malformed RDF/XML, missing namespaces, re-serializes via OWL API
                    // Safe for all formats — skips non-RDF/XML files automatically
                    try {
                        OWLFormatConverter.sanitizeFileOnDisk(tempFile);
                        log.info("[CODE-VIEW-SAVE] Sanitization completed for format: {}", format);
                    } catch (Exception sanitizeEx) {
                        log.warn("[CODE-VIEW-SAVE] Sanitization failed (continuing with original): {}", sanitizeEx.getMessage());
                    }
                    importBytes = Files.readAllBytes(tempFile);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
                rdfFormat = switch (format.toLowerCase()) {
                    case "turtle", "ttl" -> RDFFormat.TURTLE;
                    case "ntriples", "nt" -> RDFFormat.NTRIPLES;
                    default -> RDFFormat.RDFXML;
                };
            }

            // Step 2: Reimport into GraphDB
            log.info("[CODE-VIEW-SAVE] Reimporting {} bytes into GraphDB as {}", importBytes.length, rdfFormat);
            try (InputStream is = new ByteArrayInputStream(importBytes)) {
                datasetService.bulkLoadChunked(projectId, is, rdfFormat);
            }
            log.info("[CODE-VIEW-SAVE] GraphDB reimport complete");

            // Step 3: Clear ALL code-view caches (stale after reimport)
            storageManager.clearCodeViewCache(projectId);
            log.info("[CODE-VIEW-SAVE] All format caches cleared");

            // Step 4: Store the saved format's cache (preserves the user's edited content)
            storageManager.storeCodeViewCache(projectId, content, format);
            log.info("[CODE-VIEW-SAVE] Current format cache restored");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "format", format,
                    "message", "Code view saved and synced across all formats"
            ));
        } catch (Exception e) {
            log.error("[CODE-VIEW-SAVE] Failed for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Failed to save and sync code view: " + e.getMessage()
                    ));
        }
    }

    /**
     * Get the last modified timestamp for a project (for sync checking)
     */
    @GetMapping("/metadata/{projectId:.+}/timestamp")
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

    /**
     * Delete a project in free mode (legacy mode without workspace)
     * This endpoint is routed via /api/ontology/** which goes to the editor service
     * Performs full cleanup: GraphDB, GridFS, drafts, history, shares, and local files
     */
    @DeleteMapping("/project/{projectId:.+}")
    public ResponseEntity<?> deleteProject(
            @PathVariable String projectId,
            @RequestParam(required = false) String ownerEmail) {
        try {
            log.info("[ProjectLoadController] DELETE project - projectId: {}, ownerEmail: {}", projectId, ownerEmail);
            
            // Check status to verify project exists
            var statusOpt = metadataService.readStatus(projectId);
            if (statusOpt.isEmpty()) {
                log.warn("[ProjectLoadController] Project not found for deletion: {}", projectId);
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Project not found"));
            }
            
            // Clear GraphDB dataset (best-effort)
            try {
                log.info("[ProjectLoadController] Clearing GraphDB dataset for project: {}", projectId);
                datasetService.clearDataset(projectId);
            } catch (Exception e) {
                log.warn("[ProjectLoadController] Failed to clear GraphDB dataset for {}: {}", projectId, e.getMessage());
            }

            // Delete GridFS file (best-effort)
            try {
                log.info("[ProjectLoadController] Deleting GridFS file for project: {}", projectId);
                gridFSFileService.deleteFileByProjectId(projectId);
            } catch (Exception e) {
                log.warn("[ProjectLoadController] Failed to delete GridFS file for {}: {}", projectId, e.getMessage());
            }

            // Clear drafts (best-effort)
            try {
                log.info("[ProjectLoadController] Clearing drafts for project: {}", projectId);
                draftTrackingService.discardDrafts(projectId);
                draftTrackingService.clearAppliedDrafts(projectId);
            } catch (Exception e) {
                log.warn("[ProjectLoadController] Failed to clear drafts for {}: {}", projectId, e.getMessage());
            }

            // Delete shares (best-effort)
            try {
                log.info("[ProjectLoadController] Deleting share records for project: {}", projectId);
                shareService.deleteShare(projectId);
            } catch (Exception e) {
                log.warn("[ProjectLoadController] Failed to delete share for {}: {}", projectId, e.getMessage());
            }

            // Delete project metadata from MongoDB
            try {
                log.info("[ProjectLoadController] Deleting project metadata for: {}", projectId);
                projectRepository.deleteById(projectId);
            } catch (Exception e) {
                log.warn("[ProjectLoadController] Failed to delete project metadata for {}: {}", projectId, e.getMessage());
            }

            // Delete local files (best-effort)
            try {
                log.info("[ProjectLoadController] Deleting local files for project: {}", projectId);
                Path projectDir = storageManager.projectDir(projectId);
                if (java.nio.file.Files.exists(projectDir)) {
                    java.nio.file.Files.walk(projectDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                java.nio.file.Files.deleteIfExists(path);
                            } catch (Exception e) {
                                log.warn("[ProjectLoadController] Failed to delete {}", path);
                            }
                        });
                }
            } catch (Exception e) {
                log.warn("[ProjectLoadController] Failed to delete project files for {}: {}", projectId, e.getMessage());
            }

            log.info("[ProjectLoadController] ✅ Project {} deleted successfully", projectId);
            return ResponseEntity.ok(Map.of("success", true, "message", "Project deleted successfully"));
        } catch (Exception e) {
            log.error("[ProjectLoadController] Delete failed for {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Failed to delete project"));
        }
    }
}

