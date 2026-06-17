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
import jakarta.servlet.http.HttpServletRequest;

import self.research.ontology.owlEditor.config.JwtClaimUtils;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.apache.commons.io.input.TeeInputStream;

import java.util.Locale;

import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.OntologyPreparseService;
import self.research.ontology.owlEditor.service.ImportWorkerDispatcher;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.DesktopOntologyLoader;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsResource;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class ProjectLoadController {

    private static final Logger log = LoggerFactory.getLogger(ProjectLoadController.class);
    private static final java.util.regex.Pattern PCT_PATTERN = java.util.regex.Pattern.compile("(\\d+)%");
    
    // Project-level locks to prevent concurrent saves
    private final ConcurrentHashMap<String, Object> projectSaveLocks = new ConcurrentHashMap<>();

    // Tracks projects with an active uploadByFileRef in progress.
    // Prevents a second call from triggering a full re-import while the first
    // is still running the Fuseki PUT (Fuseki appears empty during the PUT,
    // so hasGraphData() returns false and the skip guard doesn't fire).
    private final java.util.Set<String> importInFlight =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final StorageManager storageManager;
    private final ProjectMetadataService metadataService;
    private final ProjectImportService importService;
    private final GridFSFileService gridFSFileService;
    private final ProjectShareService shareService;
    private final DraftTrackingService draftTrackingService;
    private final OntologyHistoryService historyService;
    private final DraftChangeRepository draftChangeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final SparqlDatasetService datasetService;
    private final ProjectRepository projectRepository;

    // Desktop-only — null in cloud
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private self.research.ontology.owlEditor.cache.ProjectOntologyCache ontologyCache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private DesktopOntologyLoader desktopOntologyLoader;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private self.research.ontology.owlEditor.service.DesktopHierarchyService desktopHierarchyService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private self.research.ontology.owlEditor.service.OntologyQueryService ontologyQueryService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private self.research.ontology.owlEditor.service.HierarchyIndexService hierarchyIndexService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private self.research.ontology.owlEditor.service.DesktopFusekiSyncScheduler fusekiSyncScheduler;

    private final OntologyPreparseService preparseService;
    private final ImportWorkerDispatcher importWorkerDispatcher;
    private final MongoTemplate mongoTemplate;

    public ProjectLoadController(StorageManager storageManager,
                                 ProjectMetadataService metadataService,
                                 ProjectImportService importService,
                                 GridFSFileService gridFSFileService,
                                 ProjectShareService shareService,
                                 DraftTrackingService draftTrackingService,
                                 OntologyHistoryService historyService,
                                 DraftChangeRepository draftChangeRepository,
                                 SimpMessagingTemplate messagingTemplate,
                                 SparqlDatasetService datasetService,
                                 ProjectRepository projectRepository,
                                 OntologyPreparseService preparseService,
                                 ImportWorkerDispatcher importWorkerDispatcher,
                                 MongoTemplate mongoTemplate) {
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
        this.mongoTemplate = mongoTemplate;
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
        long uploadStartTime = System.nanoTime();
        log.info("[ProjectLoadController] ═══ Upload STARTED - projectId: {}, filename: {}, size: {} bytes, ownerEmail: {}, workspaceId: {}, parentProjectId: {}, action: {}, compressed: {}",
            projectId, file.getOriginalFilename(), file.getSize(), ownerEmail, workspaceId, parentProjectId, action, compressed);
        try {
            // VALIDATION: Check file size (max 1GB)
            long stepStart = System.nanoTime();
            long maxSize = 1024L * 1024 * 1024; // 1GB
            if (file.getSize() > maxSize) {
                log.warn("File too large: {} bytes (max: {} bytes)", file.getSize(), maxSize);
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(Map.of(
                            "success", false,
                            "error", "File too large. Maximum file size is 300MB. Your file is " + (file.getSize() / (1024 * 1024)) + "MB"
                        ));
            }

            log.info("[ProjectLoadController] [TIMING] Validation: {} ms", (System.nanoTime() - stepStart) / 1_000_000);

            stepStart = System.nanoTime();
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
                        
                        // Clean up stale files before re-import so OntologyFileController
                        // does not serve the old version while the new import is processing
                        try {
                            Path oldProjectDir = storageManager.projectDir(actualProjectId);
                            for (String staleFile : new String[]{
                                "ontology.current.owl", "ontology.current.rdf",
                                "ontology.current.ttl", "ontology.current.nt"
                            }) {
                                Path stalePath = oldProjectDir.resolve(staleFile);
                                if (Files.deleteIfExists(stalePath)) {
                                    log.info("Cleaned up stale file before re-import: {}", stalePath);
                                }
                            }
                        } catch (Exception cleanupEx) {
                            log.warn("Failed to clean up stale files for project {}: {}", actualProjectId, cleanupEx.getMessage());
                        }

                        // Clear the GraphDB dataset so stale triples do not bleed into the new import
                        try {
                            datasetService.clearDataset(actualProjectId);
                            log.info("Cleared GraphDB dataset before re-import for project {}", actualProjectId);
                        } catch (Exception clearEx) {
                            log.warn("Failed to clear GraphDB dataset for project {}: {}", actualProjectId, clearEx.getMessage());
                        }
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
            
            log.info("[ProjectLoadController] [TIMING] Duplicate check: {} ms", (System.nanoTime() - stepStart) / 1_000_000);

            // Optimize by writing to filesystem and GridFS in one pass
            stepStart = System.nanoTime();
            Path projectDir = storageManager.prepareProjectDir(actualProjectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Files.createDirectories(original.getParent());
            Path importRoot = original;
            boolean ontologyPackage = isOntologyPackage(filename, file.getContentType());

            String gridfsFileId;

            if (ontologyPackage) {
                Path packageZip = projectDir.resolve("ontology-package.zip");
                Path libraryDir = projectDir.resolve("ontology-library");
                deleteRecursively(libraryDir);
                Files.createDirectories(libraryDir);

                try (InputStream in = file.getInputStream();
                     OutputStream out = Files.newOutputStream(packageZip,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE);
                     TeeInputStream tee = new TeeInputStream(in, out, true)) {
                    gridfsFileId = gridFSFileService.storeFile(
                        actualProjectId,
                        filename,
                        file.getContentType(),
                        tee
                    );
                }

                extractOntologyPackage(packageZip, libraryDir);
                importRoot = selectPackageRootOntology(libraryDir, filename)
                        .orElseThrow(() -> new IOException("Ontology package must contain at least one ontology file (.owl, .rdf, .ttl, .n3, .nt, .xml, .jsonld)"));
                Files.copy(importRoot, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                filename = importRoot.getFileName().toString();
                log.info("[ProjectLoadController] Ontology package root selected: {}", importRoot);
            } else {
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
            }

            log.info("[ProjectLoadController] [TIMING] File save (disk + GridFS): {} ms", (System.nanoTime() - stepStart) / 1_000_000);

            // FIX: Add error handling - verify GridFS storage succeeded
            if (gridfsFileId == null || gridfsFileId.isEmpty()) {
                throw new RuntimeException("Failed to store file in GridFS - no file ID returned");
            }

            log.info("Stored file in GridFS for project {}: fileId={}", actualProjectId, gridfsFileId);

            // SKIP sanitization here — ProjectImportService.runImport() does it during async import.
            // Doing it twice wastes 10-15 seconds on 224 MB files (streams entire file for IRI scanning).

            // Extract citation-entity mappings from uploaded file for smart repositioning
            // This must be done BEFORE GraphDB import, as GraphDB will reorganize the content
            stepStart = System.nanoTime();
            log.info("Extracting citation-entity mappings from uploaded file: {}", filename);
            storageManager.extractCitationMappingsFromFile(importRoot, actualProjectId);
            log.info("[ProjectLoadController] [TIMING] Citation extraction: {} ms", (System.nanoTime() - stepStart) / 1_000_000);

            // FIX: Batch metadata updates into single operation for better performance
            // Use the potentially modified filename
            stepStart = System.nanoTime();
            ProjectStatus status = ProjectStatus.uploaded(filename);
            metadataService.updateProjectMetadata(actualProjectId, status, gridfsFileId, ownerEmail, workspaceId, parentProjectId);
            log.info("[ProjectLoadController] [TIMING] Metadata update: {} ms", (System.nanoTime() - stepStart) / 1_000_000);

            stepStart = System.nanoTime();
            ImportOptions options = resolveImportOptions(importMode, partition);
            importWorkerDispatcher.dispatch(actualProjectId, importRoot, ownerEmail, filename, gridfsFileId, options);
            log.info("[ProjectLoadController] [TIMING] Import dispatch: {} ms", (System.nanoTime() - stepStart) / 1_000_000);

            stepStart = System.nanoTime();
            RDFFormat format = detectFormat(importRoot);
            log.info("[ProjectLoadController] [TIMING] Format detection: {} ms", (System.nanoTime() - stepStart) / 1_000_000);

            // Skip duplicate full-file streaming parse for large uploads; import already scans the file.
            if (Files.size(importRoot) <= 50L * 1024 * 1024) {
                preparseService.preparse(importRoot, actualProjectId, format);
            } else {
                log.info("[ProjectLoadController] Skipping preparse for large upload ({} bytes)", Files.size(importRoot));
            }
            
            long totalUploadMs = (System.nanoTime() - uploadStartTime) / 1_000_000;
            log.info("[ProjectLoadController] ═══ Upload endpoint COMPLETED in {} ms ({} sec) for project: {}",
                    totalUploadMs, totalUploadMs / 1000, actualProjectId);

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
     * Server-side import by file reference — avoids browser download/re-upload for large files.
     * Reads the file directly from the shared MongoDB GridFS (via file_metadata UUID → gridfsId),
     * writes it to disk, and dispatches the import job exactly as the regular upload does.
     */
    @PostMapping("/upload-by-file-ref/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> uploadByFileRef(
            @PathVariable String projectId,
            @RequestParam String fileId,
            @RequestParam String parentProjectId,
            @RequestParam(required = false) String ownerEmail,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String importMode,
            @RequestParam(required = false) String partition,
            @RequestParam(required = false) String workspaceId) {
        long startTime = System.nanoTime();
        log.info("[ProjectLoadController] ═══ UploadByFileRef STARTED - projectId: {}, fileId: {}, ownerEmail: {}",
                projectId, fileId, ownerEmail);
        // Guard: if an import is already running for this project, return immediately.
        // Without this, a second frontend call while the Fuseki PUT is in flight (Fuseki
        // still shows 0 triples) bypasses the hasGraphData skip and starts a full re-import.
        if (!importInFlight.add(projectId)) {
            log.info("[ProjectLoadController] Import already in flight for project {}, returning ALREADY_LOADING", projectId);
            return ResponseEntity.ok(Map.of(
                "success", true, "projectId", projectId,
                "status", "ALREADY_LOADING", "source", "in-flight-guard"
            ));
        }
        try {
            var mongoStatus = metadataService.readStatus(projectId);
            if (importService.isImportActiveOrQueued(projectId)) {
                log.info("[ProjectLoadController] Import already active for project {}, returning ALREADY_LOADING", projectId);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "status", "ALREADY_LOADING",
                    "source", "import-service-guard"
                ));
            }

            // Desktop fast path: skip re-import if data already exists.
            // Priority: OWLAPI cached → MongoDB status COMPLETED → Fuseki SPARQL count.
            // MongoDB check is most reliable (always fast, doesn't require Fuseki connection).
            if (ontologyCache != null) {
                boolean owlapiReady = ontologyCache.has(projectId);

                boolean mongoCompleted = mongoStatus
                    .map(s -> "COMPLETED".equals(s.status()) || "UPDATED".equals(s.status()))
                    .orElse(false);

                boolean fileExists = storageManager.findCurrentOntology(projectId).isPresent();

                // If MongoDB status is PROCESSING or ERROR, the last import was interrupted
                // or failed — do NOT skip even if Fuseki has partial data. Force re-import.
                boolean importFailed = mongoStatus
                    .map(s -> "PROCESSING".equals(s.status()) || "ERROR".equals(s.status()))
                    .orElse(false);
                if (importFailed && fileExists) {
                    log.info("[ProjectLoadController] MongoDB status is PROCESSING/ERROR — forcing re-import for {}", projectId);
                    // Fall through to full import path (do not set shouldSkip)
                }

                // Extra Fuseki check only if MongoDB says completed but file is missing
                // (handles case where data was manually cleared)
                boolean fusekiHasData = false;
                if (importFailed) {
                    // Never skip on a previously failed/interrupted import
                    mongoCompleted = false;
                } else if (mongoCompleted && !fileExists) {
                    // File missing → someone cleared data, allow re-import
                    mongoCompleted = false;
                    log.info("[ProjectLoadController] MongoDB says COMPLETED but file missing — forcing re-import for {}", projectId);
                } else if (!mongoCompleted && fileExists) {
                    // MongoDB not completed but file exists → check Fuseki as fallback
                    fusekiHasData = datasetService.hasGraphData(projectId);
                }

                boolean shouldSkip = owlapiReady || mongoCompleted || fusekiHasData;
                log.info("[ProjectLoadController] Desktop skip check: owlapi={} mongoDone={} fileExists={} fuseki={} → skip={}",
                    owlapiReady, mongoCompleted, fileExists, fusekiHasData, shouldSkip);

                if (shouldSkip) {
                    log.info("[ProjectLoadController] Desktop shortcut — skipping re-import for {}", projectId);
                    if (!owlapiReady && storageManager.findCurrentOntology(projectId).isEmpty()) {
                        materializeOntologyFromFileRef(projectId, fileId);
                    }
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("success", true);
                    body.put("projectId", projectId);
                    body.put("status", "ALREADY_LOADED");
                    body.put("source", "desktop-cache-skip");
                    // Block until OWLAPI is warm on reopen — logs show many ALREADY_LOADED returns
                    // with owlapi=false while the UI fell through to SPARQL (Fuseki often down).
                    if (!owlapiReady && desktopOntologyLoader != null) {
                        log.info("[ProjectLoadController] ALREADY_LOADED — blocking OWLAPI warm for {}", projectId);
                        body.putAll(desktopOntologyLoader.warmProject(projectId, 120_000));
                    } else {
                        body.put("owlapiReady", owlapiReady);
                    }
                    if (fusekiSyncScheduler != null) {
                        fusekiSyncScheduler.scheduleAfterOpen(projectId);
                    }
                    return ResponseEntity.ok(body);
                }
            }

            // 1. Look up file_metadata by UUID fileId to resolve gridfsId and fileName
            Document fileMeta = mongoTemplate.getDb()
                    .getCollection("file_metadata")
                    .find(new Document("fileId", fileId)
                            .append("isDeleted", new Document("$ne", true)))
                    .first();

            if (fileMeta == null) {
                log.warn("[ProjectLoadController] file_metadata not found for fileId: {}", fileId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "File not found: " + fileId));
            }

            String gridfsId = fileMeta.getString("gridfsId");
            String fileName = fileMeta.getString("fileName");
            log.info("[ProjectLoadController] Resolved file: fileName={}, gridfsId={}", fileName, gridfsId);

            // 2. Stream file bytes from GridFS
            Optional<GridFsResource> resourceOpt = gridFSFileService.getFileById(gridfsId);
            if (resourceOpt.isEmpty()) {
                log.error("[ProjectLoadController] GridFS content missing for gridfsId: {}", gridfsId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "File content not found in storage"));
            }

            // 3. Clear dataset when replacing
            if ("replace".equals(action)) {
                try {
                    datasetService.clearDataset(projectId);
                    log.info("[ProjectLoadController] Cleared GraphDB dataset for project {}", projectId);
                } catch (Exception e) {
                    log.warn("[ProjectLoadController] Failed to clear dataset for {}: {}", projectId, e.getMessage());
                }
            }

            // 4. Write file to project directory (same path as normal upload)
            Path projectDir = storageManager.prepareProjectDir(projectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Files.createDirectories(original.getParent());

            try (InputStream in = resourceOpt.get().getInputStream();
                 OutputStream out = Files.newOutputStream(original,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }
            log.info("[ProjectLoadController] [TIMING] GridFS read + disk write: {} ms",
                    (System.nanoTime() - startTime) / 1_000_000);

            // 5. Citation mappings, metadata, dispatch import
            storageManager.extractCitationMappingsFromFile(original, projectId);
            ProjectStatus status = ProjectStatus.uploaded(fileName);
            metadataService.updateProjectMetadata(projectId, status, gridfsId, ownerEmail, workspaceId, parentProjectId);

            ImportOptions options = resolveImportOptions(importMode, partition);
            importWorkerDispatcher.dispatch(projectId, original, ownerEmail, fileName, gridfsId, options);

            RDFFormat format = detectFormat(original);
            if (Files.size(original) <= 50L * 1024 * 1024) {
                preparseService.preparse(original, projectId, format);
            } else {
                log.info("[ProjectLoadController] Skipping preparse for large file ref ({} bytes)", Files.size(original));
            }

            long totalMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("[ProjectLoadController] ═══ UploadByFileRef COMPLETED in {} ms for project: {}", totalMs, projectId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "gridfsFileId", gridfsId,
                    "filename", fileName,
                    "message", "Import from file reference scheduled"));
        } catch (IOException e) {
            log.error("[ProjectLoadController] UploadByFileRef IO error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("[ProjectLoadController] UploadByFileRef failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error",
                            e.getMessage() != null ? e.getMessage() : "Unexpected error"));
        } finally {
            importInFlight.remove(projectId);
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

    private boolean isOntologyPackage(String filename, String contentType) {
        String lowerName = filename != null ? filename.toLowerCase(Locale.ROOT) : "";
        String lowerContentType = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        return lowerName.endsWith(".zip")
                || lowerContentType.contains("zip")
                || lowerContentType.contains("x-zip-compressed");
    }

    private void extractOntologyPackage(Path packageZip, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(packageZip))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path destination = normalizedTarget.resolve(entry.getName()).normalize();
                if (!destination.startsWith(normalizedTarget)) {
                    throw new IOException("Unsafe ZIP entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private Optional<Path> selectPackageRootOntology(Path libraryDir, String packageFilename) throws IOException {
        String packageBaseName = packageFilename != null ? packageFilename : "";
        int dot = packageBaseName.lastIndexOf('.');
        if (dot > 0) {
            packageBaseName = packageBaseName.substring(0, dot);
        }
        final String normalizedPackageBase = packageBaseName.toLowerCase(Locale.ROOT);

        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(libraryDir, 8)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(this::isOntologyDocumentFile)
                    .forEach(candidates::add);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort(Comparator
                .comparingInt((Path path) -> scoreRootCandidate(libraryDir, path, normalizedPackageBase))
                .thenComparing(path -> libraryDir.relativize(path).toString()));
        return Optional.of(candidates.get(0));
    }

    private int scoreRootCandidate(Path libraryDir, Path path, String normalizedPackageBase) {
        Path relative = libraryDir.relativize(path);
        String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        if (!normalizedPackageBase.isBlank() && base.equals(normalizedPackageBase)) {
            return 0;
        }
        if (relative.getNameCount() == 1 && (fileName.equals("root.owl") || fileName.equals("ontology.owl"))) {
            return 1;
        }
        if (relative.getNameCount() == 1) {
            return 2;
        }
        if (fileName.equals("root.owl") || fileName.equals("ontology.owl")) {
            return 3;
        }
        return 4;
    }

    private boolean isOntologyDocumentFile(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (name.equals("catalog-v001.xml")) {
            return false;
        }
        return name.endsWith(".owl")
                || name.endsWith(".rdf")
                || name.endsWith(".xml")
                || name.endsWith(".ttl")
                || name.endsWith(".n3")
                || name.endsWith(".nt")
                || name.endsWith(".jsonld")
                || name.endsWith(".owlxml");
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
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
            
            // Check for XML markers
            if (contentLower.startsWith("<?xml") || contentLower.contains("<rdf:rdf") || 
                contentLower.contains("<owl:ontology") || contentLower.contains("<ontology")) {
                log.info("Detected RDF/XML format (found XML markers)");
                return RDFFormat.RDFXML;
            }

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
                .map(status -> {
                    Map<String, Object> data = new java.util.LinkedHashMap<>();
                    data.put("status", status.status());
                    data.put("statusMessage", status.statusMessage());
                    data.put("updatedAt", status.updatedAt());
                    data.put("filename", status.filename());
                    metadataService.readMeta(projectId).ifPresent(meta -> {
                        Object ip = meta.get("importProgress");
                        if (ip instanceof Map<?, ?> progressMap) {
                            Object p = progressMap.get("progress");
                            if (p instanceof Number n) {
                                data.put("progress", n.intValue());
                            }
                            Object stage = progressMap.get("stage");
                            if (stage != null) {
                                data.put("stage", stage.toString());
                            }
                        }
                    });
                    if (!data.containsKey("progress") && status.statusMessage() != null) {
                        java.util.regex.Matcher m = PCT_PATTERN.matcher(status.statusMessage());
                        if (m.find()) {
                            data.put("progress", Integer.parseInt(m.group(1)));
                        }
                    }
                    boolean owlapiReady = ontologyCache != null && ontologyCache.has(projectId);
                    data.put("owlapiReady", owlapiReady);

                    long graphSize = -1;
                    boolean graphReady = false;
                    if ("COMPLETED".equals(status.status())) {
                        if (desktopHierarchyService != null) {
                            // Desktop: trust the COMPLETED status — OWLAPI is authoritative.
                            // Skip the Fuseki COUNT which can block 60+ seconds on cold TDB2.
                            graphReady = true;
                        } else {
                            graphSize = datasetService.getGraphTripleCount(projectId);
                            graphReady = graphSize > 0;
                        }
                    }
                    // During PROCESSING, skip synchronous triple COUNT — Fuseki may block for
                    // minutes on large graphs and stall status polls (gateway 504/500).
                    data.put("graphSize", graphSize > 0 ? graphSize : null);
                    data.put("graphReady", graphReady);

                    int topLevel = 0;
                    if (desktopHierarchyService != null && owlapiReady) {
                        topLevel = desktopHierarchyService.topLevelClassTotal(projectId);
                    } else if (graphReady && ontologyQueryService != null) {
                        try {
                            topLevel = ontologyQueryService.topLevelClassCount(projectId);
                        } catch (Exception sparqlEx) {
                            log.debug("[Status] SPARQL top-level count unavailable for {}: {}", projectId, sparqlEx.getMessage());
                        }
                    } else if (hierarchyIndexService != null && hierarchyIndexService.isReady(projectId)) {
                        topLevel = 1;
                    }

                    boolean hierarchyReady = topLevel > 0;
                    data.put("topLevelClasses", topLevel);
                    data.put("hierarchyReady", hierarchyReady);
                    data.put("editorReady", hierarchyReady);
                    if ("COMPLETED".equals(status.status()) && graphReady && !hierarchyReady) {
                        data.put("hierarchyWarming", true);
                        if (status.statusMessage() == null || status.statusMessage().isBlank()) {
                            data.put("statusMessage", "Loading class hierarchy…");
                        }
                    }
                    return ResponseEntity.ok(Map.of("success", true, "data", data));
                })
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
            @RequestParam(required = false) String username,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            @RequestParam(required = false, defaultValue = "false") boolean merge) {
        
        // Get or create a lock object for this project
        Object lock = projectSaveLocks.computeIfAbsent(projectId, k -> new Object());
        
        // Synchronize on the project-specific lock to prevent concurrent saves
        synchronized (lock) {
            try {
                String effectiveUserId = (userId != null && !userId.isBlank()) ? userId : "anonymous";
                log.info("[SAVE] Save requested for project: {} by user: {} (acquiring lock, force={}, merge={})",
                        projectId, username, force, merge);

                // STEP 1: Get this user's unapplied drafts BEFORE applying them (for history recording)
                log.info("[SAVE] Fetching drafts to record in history...");
                java.util.List<DraftChange> drafts = draftChangeRepository
                        .findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(projectId, effectiveUserId);
                log.info("[SAVE] Found {} unapplied drafts for user {}", drafts.size(), effectiveUserId);

                // STEP 2: Publish only this user's draft graph to main
                log.info("[SAVE] Applying drafts to GraphDB...");
                DraftTrackingService.ApplyDraftsResult draftResult =
                        draftTrackingService.applyDrafts(projectId, effectiveUserId, force, merge);

                if (draftResult.isConflictBlocked()) {
                    log.warn("[SAVE] Publish blocked for project {} user {}: {}",
                            projectId, effectiveUserId, draftResult.getMessage());
                    Map<String, Object> body = new java.util.HashMap<>();
                    body.put("success", false);
                    body.put("error", draftResult.getMessage());
                    body.put("conflictBlocked", true);
                    if (draftResult.getPublishAnalysis() != null) {
                        body.putAll(draftResult.getPublishAnalysis().toResponseMap());
                    }
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
                }

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
            @RequestParam(required = false) String ownerEmail,
            HttpServletRequest request) {
        try {
            String email = ownerEmail;
            if (email == null || email.isBlank()) {
                email = JwtClaimUtils.extractEmail(request.getHeader("Authorization"));
            }
            if (email == null || email.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "ownerEmail is required (or sign in with a JWT that includes an email claim)"
                ));
            }
            log.info("[CHECK-DUPLICATE] Checking for duplicate - filename: {}, ownerEmail: {}", filename, email);
            
            // Check if filename conflicts with shared files
            if (shareService.isFilenameInSharedFiles(filename, email)) {
                log.warn("[CHECK-DUPLICATE] Filename conflicts with shared file: {} for user: {}", filename, email);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                            "success", false,
                            "isDuplicate", true,
                            "error", "The file '" + filename + "' is already shared with you. Please upload with a different file name or version."
                        ));
            }
            
            // Check if user owns a file with this name
            Optional<String> existingProjectId = metadataService.getExistingProjectId(filename, email);
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
            // Fast path 1: OWLAPI model cached — data is definitely in Fuseki.
            if (ontologyCache != null && ontologyCache.has(projectId)) {
                long classCount = ontologyCache.get(projectId)
                    .map(c -> c.ontology().classesInSignature().count()).orElse(0L);
                log.info("[CHECK-GRAPHDB-DUPLICATE] OWLAPI cache shortcut — {} classes", classCount);
                return ResponseEntity.ok(Map.of("success", true, "exists", true,
                    "projectId", projectId, "fileName", fileName,
                    "graphSize", classCount, "ontologyIRIs", List.of(), "source", "owlapi-cache"));
            }

            // Fast path 2: desktop-only — filesystem check is only safe when Fuseki is
            // managed exclusively by the desktop app (single user, no external Fuseki changes).
            // On cloud, Fuseki can be cleared/migrated independently so we must always query it.
            if (ontologyCache != null) { // ontologyCache bean only exists in desktop mode
                Optional<java.nio.file.Path> currentFile = storageManager.findCurrentOntology(projectId);
                if (currentFile.isPresent()) {
                    log.info("[CHECK-GRAPHDB-DUPLICATE] Desktop file-system shortcut — ontology file exists at {}",
                        currentFile.get().getFileName());
                    return ResponseEntity.ok(Map.of("success", true, "exists", true,
                        "projectId", projectId, "fileName", fileName,
                        "graphSize", -1, "ontologyIRIs", List.of(), "source", "filesystem-check",
                        "graphReady", true));
                }
            }

            log.info("[CHECK-GRAPHDB-DUPLICATE] Checking Fuseki for project: {}, fileName: {}, fileId: {}",
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

    /**
     * ALREADY_LOADED fast path: OWLAPI warm needs an on-disk OWL file after app restart
     * even when Mongo/Fuseki still have the ontology.
     */
    private void materializeOntologyFromFileRef(String projectId, String fileId) {
        try {
            Document fileMeta = mongoTemplate.getDb()
                    .getCollection("file_metadata")
                    .find(new Document("fileId", fileId)
                            .append("isDeleted", new Document("$ne", true)))
                    .first();
            if (fileMeta == null) {
                log.warn("[ProjectLoadController] Cannot materialize {} — file_metadata missing for fileId {}", projectId, fileId);
                return;
            }
            String gridfsId = fileMeta.getString("gridfsId");
            Optional<GridFsResource> resourceOpt = gridFSFileService.getFileById(gridfsId);
            if (resourceOpt.isEmpty()) {
                log.warn("[ProjectLoadController] Cannot materialize {} — GridFS missing for {}", projectId, gridfsId);
                return;
            }
            Path projectDir = storageManager.prepareProjectDir(projectId);
            Path original = projectDir.resolve("ontology.original.owl");
            Path current = projectDir.resolve("ontology.current.owl");
            try (InputStream in = resourceOpt.get().getInputStream()) {
                Files.copy(in, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(original, current, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[ProjectLoadController] Materialized ontology on disk for OWLAPI warm: {}", projectId);
        } catch (Exception e) {
            log.warn("[ProjectLoadController] Failed to materialize ontology for {}: {}", projectId, e.getMessage());
        }
    }
}

