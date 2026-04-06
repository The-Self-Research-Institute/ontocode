package self.research.ontology.plugins.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.plugins.service.ReasonerService;
import self.research.ontology.plugins.service.ReasonerType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for reasoning operations on ontologies.
 * Provides endpoints for consistency checking, classification, realization, and inference.
 */
@RestController
@RequestMapping("/api/reasoner")
@CrossOrigin(originPatterns = "*")
public class ReasonerController {

    private static final Logger log = LoggerFactory.getLogger(ReasonerController.class);

    @Autowired
    @Qualifier("ontologyGridFsTemplate")
    private GridFsTemplate gridfs;

    @Autowired
    private ReasonerService reasonerService;

    @Value("${ontology.editor.url:http://owl-editor:8083}")
    private String editorServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // Cache for loaded ontologies
    private final Map<String, OWLOntology> ontologyCache = new HashMap<>();

    /**
     * Load ontology from multiple sources in priority order:
     * 1. Editor service (for ontologies being edited)
     * 2. GridFS (for uploaded ontologies)
     * 3. Local filesystem (development fallback)
     */
    private OWLOntology loadOntology(String projectId) throws Exception {
        log.info("Loading ontology for project: {}", projectId);

        if (ontologyCache.containsKey(projectId)) {
            log.info("Returning cached ontology for project: {}", projectId);
            return ontologyCache.get(projectId);
        }

        // 1. Try editor service first (for ontologies being edited in the IDE)
        OWLOntology editorOntology = loadOntologyFromEditorService(projectId);
        if (editorOntology != null) {
            ontologyCache.put(projectId, editorOntology);
            return editorOntology;
        }

        // 2. Try GridFS (for uploaded ontologies)
        GridFSFile file = gridfs.findOne(new Query(Criteria.where("metadata.projectId").is(projectId)));
        if (file == null) {
            log.warn("File not found with metadata.projectId={}, trying filename", projectId);
            file = gridfs.findOne(new Query(Criteria.where("filename").is(projectId + ".owl")));
        }

        if (file != null) {
            log.info("Found ontology file in GridFS: {}", file.getFilename());
            GridFsResource resource = gridfs.getResource(file);
            try (InputStream inputStream = resource.getInputStream()) {
                OWLOntology ontology = loadOntologyFromStream(projectId, inputStream, "GridFS file " + file.getFilename());
                if (ontology != null) {
                    ontologyCache.put(projectId, ontology);
                    return ontology;
                }
            }
        }

        // 3. Fallback: try loading from local filesystem (dev convenience)
        OWLOntology filesystemOntology = loadOntologyFromFilesystem(projectId);
        if (filesystemOntology != null) {
            ontologyCache.put(projectId, filesystemOntology);
            return filesystemOntology;
        }

        log.error("Ontology file not found for project: {} (tried: editor service, GridFS, filesystem)", projectId);
        throw new RuntimeException("Ontology file not found for project: " + projectId + 
            ". Make sure the ontology is either being edited in the IDE or has been uploaded to the system.");
    }

    /**
     * Fetch ontology from the editor service API
     */
    private OWLOntology loadOntologyFromEditorService(String projectId) {
        try {
            String url = editorServiceUrl + "/api/ontology-file/" + projectId;
            log.info("Fetching ontology from editor service: {}", url);
            
            ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                try (InputStream inputStream = new java.io.ByteArrayInputStream(response.getBody())) {
                    OWLOntology ontology = loadOntologyFromStream(projectId, inputStream, "editor service");
                    if (ontology != null) {
                        log.info("Successfully loaded ontology from editor service");
                        return ontology;
                    }
                }
            } else {
                log.warn("Editor service returned status {} for project {}", response.getStatusCode(), projectId);
            }
        } catch (Exception e) {
            log.warn("Could not fetch ontology from editor service for project {}: {}", projectId, e.getMessage());
        }
        return null;
    }

    private OWLOntology loadOntologyFromStream(String projectId, InputStream inputStream, String sourceDescription) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            log.info("Loading ontology for {} from {}", projectId, sourceDescription);
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);
            log.info("Ontology loaded successfully: {} axioms", ontology.getAxiomCount());
            return ontology;
        } catch (Exception e) {
            log.error("Failed to load ontology from {}", sourceDescription, e);
            return null;
        }
    }

    private OWLOntology loadOntologyFromFilesystem(String projectId) {
        List<Path> candidateFiles = Arrays.asList(
            Paths.get("..", "ontology-editor", "data", "projects", projectId, "ontology.current.owl"),
            Paths.get("..", "ontology-editor", "data", "projects", projectId, "ontology.original.owl"),
            Paths.get("ontology-editor", "data", "projects", projectId, "ontology.current.owl"),
            Paths.get(projectId + ".owl"),
            Paths.get("test-reasoner-ontology.owl"),
            Paths.get("..", "test-reasoner-ontology.owl")
        );

        for (Path candidate : candidateFiles) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (!Files.exists(absolute)) {
                continue;
            }

            log.info("Attempting to load ontology from filesystem path: {}", absolute);
            try (InputStream inputStream = Files.newInputStream(absolute)) {
                OWLOntology ontology = loadOntologyFromStream(projectId, inputStream, "filesystem path " + absolute);
                if (ontology != null) {
                    return ontology;
                }
            } catch (Exception e) {
                log.error("Failed to read ontology file at {}", absolute, e);
            }
        }

        log.warn("No filesystem ontology file found for project: {}", projectId);
        return null;
    }

    /**
     * Check ontology consistency
     * POST /api/reasoner/{projectId}/consistency
     */
    @PostMapping("/{projectId}/consistency")
    public ResponseEntity<Map<String, Object>> checkConsistency(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String reasonerType = request.getOrDefault("reasonerType", "HERMIT");
            log.info("Checking consistency for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            boolean isConsistent = reasonerService.isConsistent(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> result = new HashMap<>();
            result.put("consistent", isConsistent);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("projectId", projectId);
            
            // If inconsistent, get unsatisfiable classes
            if (!isConsistent) {
                Set<OWLClass> unsatisfiable = reasonerService.getUnsatisfiableClasses(ontology, type);
                List<Map<String, String>> unsatisfiableList = unsatisfiable.stream()
                    .map(cls -> Map.of(
                        "iri", cls.getIRI().toString(),
                        "label", getLabel(cls, ontology)
                    ))
                    .collect(Collectors.toList());
                result.put("unsatisfiableClasses", unsatisfiableList);
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error checking consistency", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Classify the ontology (compute class hierarchy)
     * POST /api/reasoner/{projectId}/classify
     */
    @PostMapping("/{projectId}/classify")
    public ResponseEntity<Map<String, Object>> classify(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String reasonerType = request.getOrDefault("reasonerType", "HERMIT");
            log.info("Classifying ontology for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            reasonerService.classify(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            // Get classification results
            Map<String, Object> classificationData = reasonerService.getClassificationResults(ontology, type);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Classification completed successfully");
            
            // Add classification details
            result.put("classHierarchy", classificationData.get("classHierarchy"));
            result.put("objectPropertyHierarchy", classificationData.get("objectPropertyHierarchy"));
            result.put("dataPropertyHierarchy", classificationData.get("dataPropertyHierarchy"));
            result.put("equivalentClasses", classificationData.get("equivalentClasses"));
            result.put("unsatisfiableClasses", classificationData.get("unsatisfiableClasses"));
            result.put("totalClasses", classificationData.get("totalClasses"));
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during classification", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Realize the ontology (compute instances)
     * POST /api/reasoner/{projectId}/realize
     */
    @PostMapping("/{projectId}/realize")
    public ResponseEntity<Map<String, Object>> realize(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String reasonerType = request.getOrDefault("reasonerType", "HERMIT");
            log.info("Realizing ontology for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            reasonerService.realize(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            // Get realization results
            Map<String, Object> realizationData = reasonerService.getRealizationResults(ontology, type);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Realization completed successfully");
            result.put("instances", realizationData.get("instances"));
            result.put("totalInstances", realizationData.get("totalInstances"));
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during realization", e);
            if (e.getMessage() != null && e.getMessage().contains("Ontology file not found")) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "errorType", "ONTOLOGY_NOT_FOUND",
                    "projectId", projectId,
                    "suggestion", "Please upload an ontology file for this project. Use /api/reasoner/diagnose/" + projectId + " to investigate."
                ));
            }
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Explain why the ontology is inconsistent
     * POST /api/reasoner/{projectId}/explain-inconsistency
     */
    @PostMapping("/{projectId}/explain-inconsistency")
    public ResponseEntity<Map<String, Object>> explainInconsistency(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String reasonerType = request.getOrDefault("reasonerType", "HERMIT");
            log.info("Explaining inconsistency for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            Map<String, Object> explanation = reasonerService.explainInconsistency(ontology, type);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.putAll(explanation);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error explaining inconsistency", e);
            if (e.getMessage() != null && e.getMessage().contains("Ontology file not found")) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "errorType", "ONTOLOGY_NOT_FOUND",
                    "projectId", projectId,
                    "suggestion", "Please upload an ontology file for this project. Use /api/reasoner/diagnose/" + projectId + " to investigate."
                ));
            }
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred axioms
     * GET /api/reasoner/{projectId}/inferred-axioms
     */
    @GetMapping("/{projectId}/inferred-axioms")
    public ResponseEntity<Map<String, Object>> getInferredAxioms(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Getting inferred axioms for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            Set<OWLAxiom> inferredAxioms = reasonerService.getInferredAxioms(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            List<Map<String, String>> axiomsList = inferredAxioms.stream()
                .limit(100) // Limit to first 100 to avoid huge responses
                .map(axiom -> Map.of(
                    "axiomType", axiom.getAxiomType().getName(),
                    "readable", formatAxiom(axiom, ontology),
                    "axiom", axiom.toString()
                ))
                .collect(Collectors.toList());
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("axioms", axiomsList);
            result.put("totalCount", inferredAxioms.size());
            result.put("durationMs", duration);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error getting inferred axioms", e);
            if (e.getMessage() != null && e.getMessage().contains("Ontology file not found")) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "errorType", "ONTOLOGY_NOT_FOUND",
                    "projectId", projectId,
                    "suggestion", "Please upload an ontology file for this project. Use /api/reasoner/diagnose/" + projectId + " to investigate."
                ));
            }
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get reasoner statistics
     * GET /api/reasoner/{projectId}/stats
     */
    @GetMapping("/{projectId}/stats")
    public ResponseEntity<Map<String, Object>> getReasonerStats(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            Map<String, Object> stats = reasonerService.getReasonerStats(ontology, type);
            stats.put("success", true);
            stats.put("projectId", projectId);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            if (e.getMessage() != null && e.getMessage().contains("Ontology file not found")) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "errorType", "ONTOLOGY_NOT_FOUND",
                    "projectId", projectId,
                    "suggestion", "Please upload an ontology file for this project. Use /api/reasoner/diagnose/" + projectId + " to investigate."
                ));
            }
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Clear reasoner cache
     * POST /api/reasoner/clear-cache
     */
    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        try {
            reasonerService.clearCache();
            ontologyCache.clear();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Cache cleared successfully"
            ));
            
        } catch (Exception e) {
            log.error("Error clearing cache", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Diagnostic endpoint to check GridFS file storage status
     * GET /api/reasoner/diagnose/{projectId}
     */
    @GetMapping("/diagnose/{projectId}")
    public ResponseEntity<Map<String, Object>> diagnoseFileStorage(@PathVariable String projectId) {
        try {
            Map<String, Object> diagnosis = new HashMap<>();
            diagnosis.put("projectId", projectId);
            diagnosis.put("timestamp", new java.util.Date());
            
            // Check GridFS by metadata.projectId
            GridFSFile fileByMetadata = gridfs.findOne(new Query(Criteria.where("metadata.projectId").is(projectId)));
            if (fileByMetadata != null) {
                diagnosis.put("foundInGridFS", true);
                diagnosis.put("searchMethod", "metadata.projectId");
                diagnosis.put("gridfsFileId", fileByMetadata.getObjectId().toString());
                diagnosis.put("filename", fileByMetadata.getFilename());
                diagnosis.put("uploadDate", fileByMetadata.getUploadDate());
                diagnosis.put("length", fileByMetadata.getLength());
                diagnosis.put("metadata", fileByMetadata.getMetadata());
                diagnosis.put("status", "OK - File found in GridFS");
            } else {
                // Try by filename
                GridFSFile fileByFilename = gridfs.findOne(new Query(Criteria.where("filename").is(projectId + ".owl")));
                if (fileByFilename != null) {
                    diagnosis.put("foundInGridFS", true);
                    diagnosis.put("searchMethod", "filename");
                    diagnosis.put("gridfsFileId", fileByFilename.getObjectId().toString());
                    diagnosis.put("filename", fileByFilename.getFilename());
                    diagnosis.put("uploadDate", fileByFilename.getUploadDate());
                    diagnosis.put("length", fileByFilename.getLength());
                    diagnosis.put("metadata", fileByFilename.getMetadata());
                    diagnosis.put("warning", "File found by filename only, not by metadata.projectId");
                } else {
                    diagnosis.put("foundInGridFS", false);
                    diagnosis.put("status", "ERROR - File not found in GridFS");
                }
            }
            
            // Check filesystem fallback locations
            List<Map<String, Object>> filesystemChecks = new ArrayList<>();
            List<Path> candidateFiles = Arrays.asList(
                Paths.get("..", "ontology-editor", "data", "projects", projectId, "ontology.current.owl"),
                Paths.get("..", "ontology-editor", "data", "projects", projectId, "ontology.original.owl"),
                Paths.get("ontology-editor", "data", "projects", projectId, "ontology.current.owl"),
                Paths.get(projectId + ".owl")
            );
            
            for (Path candidate : candidateFiles) {
                Path absolute = candidate.toAbsolutePath().normalize();
                Map<String, Object> fileCheck = new HashMap<>();
                fileCheck.put("path", absolute.toString());
                fileCheck.put("exists", Files.exists(absolute));
                if (Files.exists(absolute)) {
                    fileCheck.put("size", Files.size(absolute));
                    fileCheck.put("lastModified", Files.getLastModifiedTime(absolute).toString());
                }
                filesystemChecks.add(fileCheck);
            }
            diagnosis.put("filesystemFallback", filesystemChecks);
            
            // Check if cached
            diagnosis.put("cachedInMemory", ontologyCache.containsKey(projectId));
            
            // List recent GridFS files for reference
            List<Map<String, Object>> recentFiles = new ArrayList<>();
            gridfs.find(new Query().limit(10))
                .sort(new org.bson.Document("uploadDate", -1))
                .forEach(file -> {
                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("filename", file.getFilename());
                    fileInfo.put("uploadDate", file.getUploadDate());
                    fileInfo.put("fileId", file.getObjectId().toString());
                    if (file.getMetadata() != null) {
                        fileInfo.put("metadata", file.getMetadata());
                    }
                    recentFiles.add(fileInfo);
                });
            diagnosis.put("recentGridFSFiles", recentFiles);
            
            // Provide suggestions
            List<String> suggestions = new ArrayList<>();
            if (!diagnosis.containsKey("foundInGridFS") || !(Boolean) diagnosis.get("foundInGridFS")) {
                suggestions.add("File not found in GridFS - the upload may have failed or the file was deleted");
                suggestions.add("Check if the project was copied without copying the ontology file");
                suggestions.add("Try re-uploading the ontology file or copying from the original project");
                suggestions.add("Check MongoDB GridFS collections: db.fs.files and db.fs.chunks");
            }
            diagnosis.put("suggestions", suggestions);
            
            return ResponseEntity.ok(diagnosis);
            
        } catch (Exception e) {
            log.error("Error diagnosing file storage for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage(),
                "projectId", projectId
            ));
        }
    }

    // Helper methods

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(entity.getIRI()).stream()
            .filter(a -> a.getProperty().isLabel())
            .findFirst()
            .map(a -> a.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(""))
            .orElse(getLocalName(entity.getIRI().toString()));
    }

    private String getLocalName(String iri) {
        int hashIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < iri.length() - 1
            ? iri.substring(splitIndex + 1)
            : iri;
    }

    private String formatAxiom(OWLAxiom axiom, OWLOntology ontology) {
        String axiomString = axiom.toString();
        
        // Replace IRIs with labels where possible
        for (OWLEntity entity : axiom.getSignature()) {
            String label = getLabel(entity, ontology);
            if (!label.isEmpty()) {
                axiomString = axiomString.replace(entity.getIRI().toString(), label);
            }
        }
        
        return axiomString;
    }
}
