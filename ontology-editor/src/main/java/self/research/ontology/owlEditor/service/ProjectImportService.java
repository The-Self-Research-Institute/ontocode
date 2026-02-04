package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OWLEntityRenamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.model.collaboration.ImportStatusMessage;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Background job runner that streams uploaded OWL files into TDB2 and refreshes metadata.
 */
@Service
public class ProjectImportService {

    private static final Logger log = LoggerFactory.getLogger(ProjectImportService.class);

    // Prevent concurrent imports for the same project (which cause overlapping progress threads and GraphDB clears)
    private final Map<String, AtomicBoolean> importInProgress = new ConcurrentHashMap<>();

    private final Executor owlParsingExecutor;
    private final GraphDBDatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final StorageManager storageManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final ImportQueueManager queueManager;

    public ProjectImportService(@Qualifier("owlParsingExecutor") Executor owlParsingExecutor,
                                GraphDBDatasetService datasetService,
                                OntologyIndexService indexService,
                                ProjectMetadataService metadataService,
                                StorageManager storageManager,
                                SimpMessagingTemplate messagingTemplate,
                                ImportQueueManager queueManager) {
        this.owlParsingExecutor = owlParsingExecutor;
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.storageManager = storageManager;
        this.messagingTemplate = messagingTemplate;
        this.queueManager = queueManager;
    }

    public void submitImport(String projectId, Path owlFile) {
        submitImport(projectId, owlFile, null);
    }

    public void submitImport(String projectId, Path owlFile, String ownerEmail) {
        String filename = owlFile.getFileName().toString();

        log.info("[Import] Submitting import for project {}: {}", projectId, filename);

        // Add to queue
        queueManager.enqueue(projectId, filename, ownerEmail, owlFile);

        // Try to process next item in queue
        processNextInQueue();
    }

    private void processNextInQueue() {
        if (!queueManager.canProcess()) {
            log.debug("[Import] Cannot process - max concurrent imports reached");
            return;
        }

        owlParsingExecutor.execute(() -> {
            self.research.ontology.owlEditor.model.ImportQueueItem item = queueManager.dequeue();
            if (item == null) {
                return; // No items in queue
            }

            long startTime = System.currentTimeMillis();
            try {
                runImport(item.getProjectId(), item.getOwlFile());
                long duration = System.currentTimeMillis() - startTime;
                queueManager.markCompleted(item.getProjectId(), duration);
            } catch (Exception e) {
                log.error("[Import] Failed to process queue item for project {}", item.getProjectId(), e);
                
                // Check if error is retryable (connection issues, timeouts, etc.)
                boolean shouldRetry = isRetryableError(e);
                String errorReason = extractErrorReason(e);
                
                queueManager.markFailed(item.getProjectId(), errorReason, shouldRetry);
            } finally {
                // Try to process next item in queue
                processNextInQueue();
            }
        });
    }

    /**
     * Determine if an error is retryable (connection issues, timeouts)
     */
    private boolean isRetryableError(Exception e) {
        String message = e.getMessage();
        String causeMessage = e.getCause() != null ? e.getCause().getMessage() : "";
        
        // Check for connection-related errors
        return message != null && (
                message.contains("SocketException") ||
                message.contains("Connection aborted") ||
                message.contains("Connection reset") ||
                message.contains("Connection timeout") ||
                message.contains("NonRepeatableRequestException") ||
                causeMessage.contains("SocketException") ||
                causeMessage.contains("Connection aborted")
        );
    }

    /**
     * Extract a user-friendly error reason
     */
    private String extractErrorReason(Exception e) {
        if (isRetryableError(e)) {
            return "Connection to GraphDB lost. This usually means GraphDB ran out of memory for large files.";
        }
        
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("RDFParseException")) {
                return "Invalid RDF format in ontology file";
            }
            if (message.contains("OutOfMemoryError")) {
                return "Out of memory - file too large";
            }
        }
        
        return message != null ? message : "Unknown error";
    }

    private void runImport(String projectId, Path owlFile) {
        AtomicBoolean guard = importInProgress.computeIfAbsent(projectId, id -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            log.warn("[Import {}] Import already running, rejecting duplicate request", projectId);
            Map<String, Object> errorMeta = new HashMap<>();
            errorMeta.put("error", "Import already in progress for this project");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                    "ERROR", "Import already in progress, please wait", owlFile.getFileName().toString(), errorMeta);
            return;
        }

        long importStart = System.nanoTime();
        String stage = "initialization";
        String filename = metadataService.readStatus(projectId)
                .map(ProjectStatus::filename)
                .orElse(owlFile.getFileName().toString());

        log.info("[Import {}] Starting import for file {}", projectId, filename);

        // Notify: Import started
        sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_STARTED,
                "PROCESSING", "Import started", filename, null);

        metadataService.writeStatus(projectId, ProjectStatus.processing(filename));
        try {
            stage = "detect-format";
            RDFFormat format = detectFormat(owlFile);
            log.info("[Import {}] Detected RDF format: {}", projectId, format);

            // Check if file needs format conversion (OWL Functional Syntax, Manchester, etc.)
            stage = "format-conversion";
            Path fileToLoad = owlFile;
            boolean converted = false;
            
            if (OWLFormatConverter.needsConversion(owlFile)) {
                log.info("[Import {}] File requires format conversion to RDF/XML", projectId);
                
                Map<String, Object> conversionMeta = new HashMap<>();
                conversionMeta.put("progress", 15);
                conversionMeta.put("stage", "format-conversion");
                sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                        "PROCESSING", "Converting OWL format to RDF/XML...", filename, conversionMeta);
                
                try {
                    long conversionStart = System.nanoTime();
                    fileToLoad = OWLFormatConverter.convertToRDFXML(owlFile);
                    converted = true;
                    format = RDFFormat.RDFXML; // Update format after conversion
                    long conversionDuration = elapsedMillis(conversionStart);
                    log.info("[Import {}] Format conversion completed in {} ms", projectId, conversionDuration);
                } catch (Exception e) {
                    log.error("[Import {}] Format conversion failed", projectId, e);
                    throw new RuntimeException("Failed to convert OWL format: " + e.getMessage(), e);
                }
            } else {
                log.info("[Import {}] File format is compatible with GraphDB, no conversion needed", projectId);
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // STEP 1: Parse with OWL API first to validate and get metrics
            // ═══════════════════════════════════════════════════════════════════════════
            stage = "owlapi-parsing";
            log.info("[Import {}] ═══════════════════════════════════════════════════════════", projectId);
            log.info("[Import {}] STEP 1: Parsing OWL file with OWL API...", projectId);
            
            Map<String, Object> parsingMeta = new HashMap<>();
            parsingMeta.put("progress", 25);
            parsingMeta.put("stage", "owlapi-parsing");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Parsing OWL file...", filename, parsingMeta);
            
            // Create OWL API manager with silent import handling to avoid network failures
            OWLOntologyManager owlManager = OWLManager.createOWLOntologyManager();
            OWLOntologyLoaderConfiguration loaderConfig = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
            owlManager.setOntologyLoaderConfiguration(loaderConfig);
            
            // Log skipped imports
            owlManager.addMissingImportListener(event -> {
                log.warn("[Import {}] Skipping missing import: {} (reason: {})", 
                        projectId,
                        event.getImportedOntologyURI(), 
                        event.getCreationException() != null ? event.getCreationException().getMessage() : "unknown");
            });
            
            OWLOntology owlOntology;
            int owlapiAxiomCount = 0;
            int owlapiClassCount = 0;
            int owlapiObjectPropertyCount = 0;
            int owlapiDataPropertyCount = 0;
            int owlapiIndividualCount = 0;
            String ontologyIRI = "unknown";
            
            try {
                long owlapiStart = System.nanoTime();
                owlOntology = owlManager.loadOntologyFromOntologyDocument(fileToLoad.toFile());
                long owlapiDuration = elapsedMillis(owlapiStart);
                
                // Extract metrics from OWL API
                owlapiAxiomCount = owlOntology.getAxiomCount();
                owlapiClassCount = owlOntology.getClassesInSignature().size();
                owlapiObjectPropertyCount = owlOntology.getObjectPropertiesInSignature().size();
                owlapiDataPropertyCount = owlOntology.getDataPropertiesInSignature().size();
                owlapiIndividualCount = owlOntology.getIndividualsInSignature().size();
                
                // Get ontology IRI
                if (owlOntology.getOntologyID().getOntologyIRI().isPresent()) {
                    ontologyIRI = owlOntology.getOntologyID().getOntologyIRI().get().toString();
                }
                
                // Get document format
                OWLDocumentFormat docFormat = owlManager.getOntologyFormat(owlOntology);
                String formatName = docFormat != null ? docFormat.getClass().getSimpleName() : "Unknown";
                
                log.info("[Import {}] ✓ OWL API parsing completed in {} ms", projectId, owlapiDuration);
                log.info("[Import {}]   Ontology IRI: {}", projectId, ontologyIRI);
                log.info("[Import {}]   Document Format: {}", projectId, formatName);
                log.info("[Import {}]   Axioms: {}", projectId, owlapiAxiomCount);
                log.info("[Import {}]   Classes: {}", projectId, owlapiClassCount);
                log.info("[Import {}]   Object Properties: {}", projectId, owlapiObjectPropertyCount);
                log.info("[Import {}]   Data Properties: {}", projectId, owlapiDataPropertyCount);
                log.info("[Import {}]   Individuals: {}", projectId, owlapiIndividualCount);
                log.info("[Import {}] ═══════════════════════════════════════════════════════════", projectId);
                
                if (owlapiAxiomCount == 0) {
                    log.warn("[Import {}] ⚠️ WARNING: OWL API found 0 axioms in the file!", projectId);
                }
                
            } catch (OWLOntologyCreationException e) {
                log.error("[Import {}] ❌ OWL API failed to parse file: {}", projectId, e.getMessage());
                throw new RuntimeException("OWL API parsing failed: " + e.getMessage(), e);
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // STEP 1.5: Sanitize invalid IRIs (remove/encode characters like [ ] that RDF4J rejects)
            // ═══════════════════════════════════════════════════════════════════════════
            stage = "iri-sanitization";
            log.info("[Import {}] STEP 1.5: Sanitizing invalid IRIs for RDF4J compatibility...", projectId);
            
            int sanitizedCount = sanitizeInvalidIRIs(owlOntology, owlManager, projectId);
            if (sanitizedCount > 0) {
                log.info("[Import {}] ✓ Sanitized {} entities with invalid IRIs", projectId, sanitizedCount);
            } else {
                log.info("[Import {}] ✓ No invalid IRIs found, all entities are RDF4J-compatible", projectId);
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // STEP 2: Re-serialize through OWL API to sanitize IRIs and fix format issues
            // ═══════════════════════════════════════════════════════════════════════════
            stage = "owlapi-serialize";
            log.info("[Import {}] STEP 2: Re-serializing ontology through OWL API to clean RDF/XML...", projectId);
            
            Path cleanedFile = null;
            try {
                // Create a temporary file for the cleaned RDF/XML
                cleanedFile = owlFile.getParent().resolve(projectId.replaceAll("[^a-zA-Z0-9_-]", "_") + "-cleaned.owl");
                
                // Configure RDF/XML format
                org.semanticweb.owlapi.formats.RDFXMLDocumentFormat rdfXmlFormat = 
                        new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
                
                // Preserve prefixes if available
                OWLDocumentFormat originalFormat = owlManager.getOntologyFormat(owlOntology);
                if (originalFormat != null && originalFormat.isPrefixOWLDocumentFormat()) {
                    org.semanticweb.owlapi.formats.PrefixDocumentFormat prefixFormat = 
                            originalFormat.asPrefixOWLDocumentFormat();
                    prefixFormat.getPrefixName2PrefixMap().forEach(rdfXmlFormat::setPrefix);
                }
                
                // Save as clean RDF/XML
                long serializeStart = System.nanoTime();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cleanedFile.toFile())) {
                    owlManager.saveOntology(owlOntology, rdfXmlFormat, fos);
                }
                long serializeDuration = elapsedMillis(serializeStart);
                
                long cleanedFileSize = Files.size(cleanedFile);
                log.info("[Import {}] ✓ Serialized clean RDF/XML in {} ms ({} bytes)", 
                        projectId, serializeDuration, cleanedFileSize);
                
                // STEP 2.5: Post-process the serialized file to sanitize any remaining invalid IRIs
                // OWL API may preserve invalid IRIs in annotations or other constructs
                log.info("[Import {}] STEP 2.5: Post-processing RDF/XML to sanitize remaining invalid IRIs...", projectId);
                int fileFixCount = sanitizeRdfXmlFile(cleanedFile, projectId);
                if (fileFixCount > 0) {
                    log.info("[Import {}] ✓ Fixed {} invalid IRIs in serialized file", projectId, fileFixCount);
                }
                
                // Use the cleaned file for GraphDB loading
                fileToLoad = cleanedFile;
                format = RDFFormat.RDFXML;
                converted = true; // Mark as converted so it gets cleaned up
                
            } catch (Exception e) {
                log.error("[Import {}] ❌ Failed to re-serialize ontology: {}", projectId, e.getMessage());
                throw new RuntimeException("Failed to re-serialize ontology: " + e.getMessage(), e);
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // STEP 3: Load into GraphDB
            // ═══════════════════════════════════════════════════════════════════════════
            stage = "bulk-load";
            log.info("[Import {}] STEP 3: Loading data into GraphDB...", projectId);
            long fileSize = Files.size(owlFile);
            log.info("File size: {} bytes ({} MB)", fileSize, fileSize / (1024 * 1024));

            // Start a thread to send periodic progress updates
            AtomicBoolean loadingComplete = new AtomicBoolean(false);
            AtomicInteger progressCounter = new AtomicInteger(10);

            Thread progressThread = new Thread(() -> {
                try {
                    while (!loadingComplete.get() && progressCounter.get() < 90) {
                        Thread.sleep(5000); // Update every 5 seconds
                        if (!loadingComplete.get()) {
                            int progress = progressCounter.addAndGet(10);
                            progress = Math.min(progress, 90);

                            Map<String, Object> progressMeta = new HashMap<>();
                            progressMeta.put("progress", progress);
                            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                                    "PROCESSING", String.format("Importing... (%d%%)", progress), filename, progressMeta);
                            log.info("Import progress for {}: {}%", projectId, progress);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            progressThread.setDaemon(true);
            progressThread.start();

            long bulkLoadStart = System.nanoTime();
            
            // Notify user that we're starting GraphDB bulk load (this may take time for large files)
            Map<String, Object> bulkLoadStartMeta = new HashMap<>();
            bulkLoadStartMeta.put("progress", 60);
            bulkLoadStartMeta.put("stage", "graphdb-loading");
            bulkLoadStartMeta.put("message", "Loading data into GraphDB (this may take several minutes for large files)...");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "Loading into GraphDB...", filename, bulkLoadStartMeta);
            
            try (InputStream in = Files.newInputStream(fileToLoad)) {
                datasetService.bulkLoadChunked(projectId, in, format);
            } finally {
                loadingComplete.set(true);
                
                // Clean up converted file if it was created
                if (converted && fileToLoad != null && !fileToLoad.equals(owlFile)) {
                    try {
                        Files.deleteIfExists(fileToLoad);
                        log.debug("[Import {}] Cleaned up temporary converted file", projectId);
                    } catch (Exception e) {
                        log.warn("[Import {}] Failed to clean up converted file: {}", projectId, e.getMessage());
                    }
                }
            }
            log.info("[Import {}] GraphDB bulk load completed in {} ms", projectId, elapsedMillis(bulkLoadStart));

            Map<String, Object> postLoadMeta = new HashMap<>();
            postLoadMeta.put("progress", 95);
            postLoadMeta.put("stage", "graphdb-load-complete");
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_PROGRESS,
                    "PROCESSING", "GraphDB load finished, computing metadata", filename, postLoadMeta);

            // Copy file
            stage = "persist-copy";
            Path current = storageManager.resolveProjectFile(projectId, "ontology.current." + extensionFor(format));
            Files.createDirectories(current.getParent());
            Files.copy(owlFile, current, StandardCopyOption.REPLACE_EXISTING);

            // Compute metadata from GraphDB
            stage = "metadata";
            log.info("[Import {}] ═══════════════════════════════════════════════════════════", projectId);
            log.info("[Import {}] STEP 4: Computing metadata from GraphDB...", projectId);
            long metadataStart = System.nanoTime();
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            log.info("[Import {}] Metadata computed in {} ms", projectId, elapsedMillis(metadataStart));
            
            // Compare OWL API vs GraphDB counts
            int graphdbClassCount = meta.get("classes") != null ? ((Number) meta.get("classes")).intValue() : 0;
            int graphdbTripleCount = meta.get("triples") != null ? ((Number) meta.get("triples")).intValue() : 0;
            
            log.info("[Import {}] ═══════════════════════════════════════════════════════════", projectId);
            log.info("[Import {}] COMPARISON: OWL API vs GraphDB", projectId);
            log.info("[Import {}]   OWL API Classes: {} | GraphDB Classes: {}", projectId, owlapiClassCount, graphdbClassCount);
            log.info("[Import {}]   OWL API Axioms: {} | GraphDB Triples: {}", projectId, owlapiAxiomCount, graphdbTripleCount);
            
            if (graphdbClassCount == 0 && owlapiClassCount > 0) {
                log.error("[Import {}] ❌ DATA LOSS: OWL API found {} classes but GraphDB has 0!", projectId, owlapiClassCount);
            } else if (graphdbClassCount < owlapiClassCount) {
                log.warn("[Import {}] ⚠️ GraphDB has fewer classes ({}) than OWL API ({})", projectId, graphdbClassCount, owlapiClassCount);
            } else {
                log.info("[Import {}] ✓ Data loaded successfully into GraphDB", projectId);
            }
            log.info("[Import {}] ═══════════════════════════════════════════════════════════", projectId);
            
            // Add OWL API metrics to metadata
            meta.put("owlapiAxiomCount", owlapiAxiomCount);
            meta.put("owlapiClassCount", owlapiClassCount);
            meta.put("ontologyIRI", ontologyIRI);
            
            metadataService.writeMeta(projectId, meta);

            // Update status
            stage = "complete";
            metadataService.writeStatus(projectId, ProjectStatus.completed(filename));
            log.info("Completed import for project {} in {} ms", projectId, elapsedMillis(importStart));

            // Notify: Import completed
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tripleCount", meta.get("tripleCount"));
            metadata.put("classCount", meta.get("classes"));
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_COMPLETED,
                    "COMPLETED", "Import completed successfully", filename, metadata);

        } catch (Exception e) {
            log.error("Import failed for {} while {}", projectId, stage, e);
            metadataService.writeStatus(projectId, ProjectStatus.error(filename, e.getMessage()));

            // Notify: Import failed
            Map<String, Object> errorMeta = new HashMap<>();
            errorMeta.put("error", e.getMessage());
            sendImportNotification(projectId, ImportStatusMessage.ImportStatusType.IMPORT_FAILED,
                    "ERROR", "Import failed: " + e.getMessage(), filename, errorMeta);
        } finally {
            guard.set(false);
        }
    }

    /**
     * Send WebSocket notification about import status
     */
    private void sendImportNotification(String projectId, ImportStatusMessage.ImportStatusType type,
                                       String status, String message, String filename, Object metadata) {
        try {
            // Extract progress if present in metadata
            Integer progress = null;
            if (metadata instanceof Map) {
                Object progressObj = ((Map<?, ?>) metadata).get("progress");
                if (progressObj instanceof Integer) {
                    progress = (Integer) progressObj;
                }
            }

            ImportStatusMessage notification = ImportStatusMessage.builder()
                    .type(type)
                    .projectId(projectId)
                    .status(status)
                    .statusMessage(message)
                    .filename(filename)
                    .progress(progress)
                    .timestamp(System.currentTimeMillis())
                    .metadata(metadata)
                    .build();

            // Send to all users subscribed to this project
            messagingTemplate.convertAndSend("/topic/import/" + projectId, notification);
            log.debug("Sent import notification for project {}: {}", projectId, type);
        } catch (Exception e) {
            log.warn("Failed to send import notification: {}", e.getMessage());
        }
    }

    private RDFFormat detectFormat(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".ttl") || fileName.endsWith(".turtle")) {
            return RDFFormat.TURTLE;
        } else if (fileName.endsWith(".nt") || fileName.endsWith(".ntriples")) {
            return RDFFormat.NTRIPLES;
        } else if (fileName.endsWith(".jsonld")) {
            return RDFFormat.JSONLD;
        } else if (fileName.endsWith(".n3")) {
            return RDFFormat.N3;
        }
        return RDFFormat.RDFXML; // default
    }

    private String extensionFor(RDFFormat format) {
        if (format == null) {
            return "owl";
        }
        if (RDFFormat.TURTLE.equals(format)) {
            return "ttl";
        }
        if (RDFFormat.NTRIPLES.equals(format)) {
            return "nt";
        }
        if (RDFFormat.JSONLD.equals(format)) {
            return "jsonld";
        }
        return "owl";
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Sanitize IRIs that contain characters invalid for RDF4J/GraphDB.
     * RDF4J is stricter than OWL API about IRI syntax - it rejects characters like [ ] 
     * which are technically invalid per RFC 3987 but OWL API tolerates.
     * 
     * This method finds all entities with invalid IRIs and renames them to valid IRIs
     * by percent-encoding the problematic characters.
     * 
     * @param ontology The OWL ontology to sanitize
     * @param manager The OWL ontology manager
     * @param projectId For logging purposes
     * @return Number of entities that were renamed
     */
    private int sanitizeInvalidIRIs(OWLOntology ontology, OWLOntologyManager manager, String projectId) {
        // Pattern to detect invalid IRI characters that RDF4J rejects
        // RFC 3987 disallows: [ ] { } | \ ^ ` and control characters, spaces
        Pattern invalidIriPattern = Pattern.compile("[\\[\\]{}|\\\\^`\\s<>\"']");
        
        OWLDataFactory dataFactory = manager.getOWLDataFactory();
        OWLEntityRenamer renamer = new OWLEntityRenamer(manager, java.util.Collections.singleton(ontology));
        
        Map<OWLEntity, IRI> renamings = new HashMap<>();
        
        // Check all entities in the ontology
        for (OWLEntity entity : ontology.getSignature()) {
            String iriString = entity.getIRI().toString();
            
            if (invalidIriPattern.matcher(iriString).find()) {
                // Found invalid characters - create a sanitized IRI
                String sanitizedIri = sanitizeIriString(iriString);
                IRI newIri = IRI.create(sanitizedIri);
                
                log.debug("[Import {}] Sanitizing IRI: {} -> {}", projectId, iriString, sanitizedIri);
                renamings.put(entity, newIri);
            }
        }
        
        if (!renamings.isEmpty()) {
            log.info("[Import {}] Found {} entities with invalid IRIs, renaming...", projectId, renamings.size());
            
            // Apply all renamings
            List<OWLOntologyChange> changes = new ArrayList<>();
            for (Map.Entry<OWLEntity, IRI> entry : renamings.entrySet()) {
                changes.addAll(renamer.changeIRI(entry.getKey(), entry.getValue()));
            }
            
            manager.applyChanges(changes);
            log.info("[Import {}] Applied {} ontology changes for IRI sanitization", projectId, changes.size());
        }
        
        return renamings.size();
    }
    
    /**
     * Sanitize a single IRI string by percent-encoding invalid characters.
     * Preserves the structure (scheme, authority, path, fragment) while encoding
     * only the problematic characters within the local name.
     */
    private String sanitizeIriString(String iri) {
        // Find the fragment separator or last slash to identify the local name
        int fragmentIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int separatorIndex = Math.max(fragmentIndex, slashIndex);
        
        if (separatorIndex < 0) {
            // No separator found, encode the whole thing (unusual case)
            return encodeInvalidChars(iri);
        }
        
        // Split into prefix and local name
        String prefix = iri.substring(0, separatorIndex + 1);
        String localName = iri.substring(separatorIndex + 1);
        
        // Encode invalid characters in the local name only
        String sanitizedLocalName = encodeInvalidChars(localName);
        
        return prefix + sanitizedLocalName;
    }
    
    /**
     * Encode characters that are invalid in IRIs according to RFC 3987.
     * Only encodes the specific problematic characters, not all non-ASCII.
     */
    private String encodeInvalidChars(String input) {
        StringBuilder result = new StringBuilder();
        
        for (char c : input.toCharArray()) {
            switch (c) {
                case '[':
                    result.append("%5B");
                    break;
                case ']':
                    result.append("%5D");
                    break;
                case '{':
                    result.append("%7B");
                    break;
                case '}':
                    result.append("%7D");
                    break;
                case '|':
                    result.append("%7C");
                    break;
                case '\\':
                    result.append("%5C");
                    break;
                case '^':
                    result.append("%5E");
                    break;
                case '`':
                    result.append("%60");
                    break;
                case ' ':
                    result.append("%20");
                    break;
                case '<':
                    result.append("%3C");
                    break;
                case '>':
                    result.append("%3E");
                    break;
                case '"':
                    result.append("%22");
                    break;
                case '\'':
                    result.append("%27");
                    break;
                default:
                    result.append(c);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Post-process a serialized RDF/XML file to sanitize any remaining invalid IRIs.
     * This catches IRIs that OWL API preserves in annotations, imports, or other constructs
     * that aren't covered by entity renaming.
     * 
     * @param rdfXmlFile Path to the RDF/XML file
     * @param projectId For logging purposes
     * @return Number of IRIs that were fixed
     */
    private int sanitizeRdfXmlFile(Path rdfXmlFile, String projectId) throws java.io.IOException {
        // Read file content
        String content = Files.readString(rdfXmlFile, StandardCharsets.UTF_8);
        
        // Pattern to match IRIs in RDF/XML attributes (rdf:about, rdf:resource, rdf:datatype, etc.)
        // Also matches IRIs in xmlns declarations
        Pattern iriPattern = Pattern.compile(
            "(rdf:(?:about|resource|datatype|ID)|xmlns(?::[a-zA-Z0-9_-]*)?)\\s*=\\s*\"([^\"]+)\""
        );
        
        java.util.regex.Matcher matcher = iriPattern.matcher(content);
        StringBuilder result = new StringBuilder();
        int fixCount = 0;
        int lastEnd = 0;
        
        while (matcher.find()) {
            String attrName = matcher.group(1);
            String iriValue = matcher.group(2);
            
            // Check if this IRI contains invalid characters
            if (containsInvalidIriChars(iriValue)) {
                // Sanitize the IRI
                String sanitizedIri = sanitizeIriInFile(iriValue);
                
                log.debug("[Import {}] Fixing IRI in file: {} -> {}", projectId, iriValue, sanitizedIri);
                
                // Append content up to match, then the fixed attribute
                result.append(content, lastEnd, matcher.start());
                result.append(attrName).append("=\"").append(sanitizedIri).append("\"");
                lastEnd = matcher.end();
                fixCount++;
            }
        }
        
        if (fixCount > 0) {
            // Append remaining content
            result.append(content.substring(lastEnd));
            
            // Write back the sanitized content
            Files.writeString(rdfXmlFile, result.toString(), StandardCharsets.UTF_8);
            log.info("[Import {}] Rewrote RDF/XML file with {} IRI fixes", projectId, fixCount);
        }
        
        return fixCount;
    }
    
    /**
     * Check if an IRI string contains characters that are invalid for RDF4J/GraphDB.
     */
    private boolean containsInvalidIriChars(String iri) {
        for (char c : iri.toCharArray()) {
            if (c == '[' || c == ']' || c == '{' || c == '}' || c == '|' || 
                c == '\\' || c == '^' || c == '`' || c == ' ' || c == '<' || 
                c == '>' || c == '"' || c == '\'') {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Sanitize an IRI found in an RDF/XML file by percent-encoding invalid characters.
     * Handles both full IRIs and fragment-only references.
     */
    private String sanitizeIriInFile(String iri) {
        StringBuilder result = new StringBuilder();
        
        for (char c : iri.toCharArray()) {
            switch (c) {
                case '[':
                    result.append("%5B");
                    break;
                case ']':
                    result.append("%5D");
                    break;
                case '{':
                    result.append("%7B");
                    break;
                case '}':
                    result.append("%7D");
                    break;
                case '|':
                    result.append("%7C");
                    break;
                case '\\':
                    result.append("%5C");
                    break;
                case '^':
                    result.append("%5E");
                    break;
                case '`':
                    result.append("%60");
                    break;
                case ' ':
                    result.append("%20");
                    break;
                // Note: < > " are XML special chars and shouldn't appear unescaped in attribute values
                // but we handle them just in case they're entity-encoded
                default:
                    result.append(c);
            }
        }
        
        return result.toString();
    }
}
