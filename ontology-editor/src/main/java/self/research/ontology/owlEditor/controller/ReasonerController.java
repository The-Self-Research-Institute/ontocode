package self.research.ontology.owlEditor.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;
import self.research.ontology.owlEditor.service.ReasoningJobSubmitService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.semanticweb.owlapi.model.IRI;

/**
 * Controller for reasoning operations on ontologies.
 * Provides endpoints for consistency checking, classification, realization, and inference.
 */
@RestController("owlEditorReasonerController")
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class ReasonerController {

    private static final Logger log = LoggerFactory.getLogger(ReasonerController.class);

    @Autowired
    private GridFsTemplate gridfs;

    @Autowired
    private ReasonerService reasonerService;

    @Autowired
    private SparqlDatasetService datasetService;

    @Autowired
    private ReasoningJobSubmitService reasoningJobSubmitService;

    @Autowired
    private EditorReasonerCacheService editorReasonerCache;

    // Ontologies above this triple count are rejected before export to prevent OOM
    @Value("${ontocode.reasoner.max-triples:1000000}")
    private long maxReasonerTriples;

    /**
     * Load ontology from Fuseki/TDB2 (via temp-file stream) or GridFS fallback.
     * Rejects oversized ontologies before attempting in-memory OWL API parsing.
     */
    private OWLOntology loadOntology(String projectId) throws Exception {
        log.info("Loading ontology for project: {}", projectId);

        Optional<OWLOntology> cached = editorReasonerCache.getOntology(projectId);
        if (cached.isPresent()) {
            log.info("Returning cached ontology for project: {}", projectId);
            return cached.get();
        }

        editorReasonerCache.prepareForOntologyLoad(projectId);

        // Guard: reject oversized ontologies before attempting OWL API parsing.
        // Even with streaming export, loading a 2.8M-triple ontology into OWLOntology
        // objects exhausts the heap — the guard is the only thing that prevents that.
        try {
            long tripleCount = datasetService.getDatasetSize(projectId);
            if (tripleCount > maxReasonerTriples) {
                throw new IllegalArgumentException(String.format(
                    "Ontology too large for in-memory reasoning: %,d triples (limit: %,d). " +
                    "Use SPARQL queries directly against Fuseki for large ontologies.",
                    tripleCount, maxReasonerTriples));
            }
            log.info("Ontology size check passed: {} triples (limit: {})", tripleCount, maxReasonerTriples);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not check ontology size before reasoning (will proceed): {}", e.getMessage());
        }

        // Try to load from Fuseki/TDB2 by streaming to a temp file then parsing.
        // Replaces the old StringWriter → String → ByteArrayInputStream path, which
        // allocated the full graph 3× in heap before the OWL API even started parsing.
        Path tempFile = null;
        try {
            log.info("Attempting to load ontology from Fuseki for project: {}", projectId);
            tempFile = Files.createTempFile("reasoner-" + projectId + "-", ".ttl");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                datasetService.exportDatasetToStream(projectId, RDFFormat.TURTLE, out);
            }
            long tempBytes = Files.size(tempFile);
            log.info("Streamed ontology to temp file: {} bytes", tempBytes);
            if (tempBytes > 0) {
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                try (InputStream in = Files.newInputStream(tempFile)) {
                    OWLOntology ontology = manager.loadOntologyFromOntologyDocument(in);
                    log.info("Ontology loaded from Fuseki stream: {} axioms", ontology.getAxiomCount());
                    editorReasonerCache.putOntology(projectId, ontology);
                    return ontology;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load ontology from Fuseki, falling back to GridFS: {}", e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }

        // Fallback to GridFS
        GridFSFile file = gridfs.findOne(new Query(Criteria.where("metadata.projectId").is(projectId)));
        
        if (file == null) {
            log.warn("File not found with metadata.projectId={}, trying filename", projectId);
            file = gridfs.findOne(new Query(Criteria.where("filename").is(projectId + ".owl")));
        }
        
        if (file == null) {
            log.error("Ontology file not found for project: {}", projectId);
            throw new RuntimeException("Ontology file not found for project: " + projectId);
        }

        log.info("Found ontology file: {}", file.getFilename());
        GridFsResource resource = gridfs.getResource(file);
        
        try (InputStream inputStream = resource.getInputStream()) {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            log.info("Loading ontology from input stream for project: {}", projectId);
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);
            log.info("Ontology loaded successfully for project: {}", projectId);
            log.info("Axiom count: {}", ontology.getAxiomCount());
            log.info("Class count: {}", ontology.getClassesInSignature().size());
            log.info("Object property count: {}", ontology.getObjectPropertiesInSignature().size());
            log.info("Data property count: {}", ontology.getDataPropertiesInSignature().size());
            editorReasonerCache.putOntology(projectId, ontology);
            return ontology;
        } catch (Exception e) {
            log.error("Error loading ontology from GridFS", e);
            throw e;
        }
    }

    @PostMapping("/{projectId}/reasoner/refresh")
    public ResponseEntity<Map<String, Object>> refreshReasoner(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Refreshing reasoner for project: {}", projectId);
            editorReasonerCache.invalidateOntology(projectId);

            OWLOntology ontology = loadOntology(projectId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Reasoner refreshed with latest data from GraphDB",
                "axiomCount", ontology.getAxiomCount()
            ));
        } catch (Exception e) {
            log.error("Error refreshing reasoner", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Stop reasoning for a project — dispose warmed reasoner sessions without unloading ontology.
     * POST /api/ontology/{projectId}/reasoner/stop
     */
    @PostMapping("/{projectId}/reasoner/stop")
    public ResponseEntity<Map<String, Object>> stopReasoner(
            @PathVariable String projectId,
            @RequestParam(required = false) String reasonerType
    ) {
        try {
            log.info("Stopping reasoner for project: {} ({})", projectId,
                    reasonerType != null ? reasonerType : "all");
            editorReasonerCache.stopReasoning(projectId, reasonerType);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Reasoner stopped",
                    "projectId", projectId
            ));
        } catch (Exception e) {
            log.error("Error stopping reasoner", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Check ontology consistency
     * POST /api/ontology/{projectId}/reasoner/consistency
     */
    @PostMapping("/{projectId}/reasoner/consistency")
    public ResponseEntity<Map<String, Object>> checkConsistency(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            ResponseEntity<Map<String, Object>> async = reasoningJobSubmitService.submit(
                    "REASONER_CONSISTENCY", projectId, reasonerType, null);
            if (async != null) {
                return async;
            }

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
            log.error("Error checking consistency for project: " + projectId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("errorType", e.getClass().getSimpleName());
            errorResponse.put("projectId", projectId);
            
            // Include stack trace in development
            if (log.isDebugEnabled()) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                errorResponse.put("stackTrace", sw.toString());
            }
            
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * Classify the ontology (compute class hierarchy)
     * POST /api/ontology/{projectId}/reasoner/classify
     */
    @PostMapping("/{projectId}/reasoner/classify")
    public ResponseEntity<Map<String, Object>> classify(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            ResponseEntity<Map<String, Object>> async = reasoningJobSubmitService.submit(
                    "REASONER_CLASSIFY", projectId, reasonerType, null);
            if (async != null) {
                return async;
            }

            log.info("Classifying ontology for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            reasonerService.classify(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Classification completed successfully");
            
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
     * POST /api/ontology/{projectId}/reasoner/realize
     */
    @PostMapping("/{projectId}/reasoner/realize")
    public ResponseEntity<Map<String, Object>> realize(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            ResponseEntity<Map<String, Object>> async = reasoningJobSubmitService.submit(
                    "REASONER_REALIZE", projectId, reasonerType, null);
            if (async != null) {
                return async;
            }

            log.info("Realizing ontology for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            reasonerService.realize(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Realization completed successfully");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during realization", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred axioms
     * GET /api/ontology/{projectId}/reasoner/inferred-axioms
     */
    @GetMapping("/{projectId}/reasoner/inferred-axioms")
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
            
            // Convert axioms to readable format
            List<Map<String, String>> axiomsList = inferredAxioms.stream()
                .limit(100) // Limit to first 100 for performance
                .map(axiom -> Map.of(
                    "axiomType", axiom.getAxiomType().getName(),
                    "axiom", axiom.toString(),
                    "readable", formatAxiom(axiom, ontology)
                ))
                .collect(Collectors.toList());
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("totalInferredAxioms", inferredAxioms.size());
            result.put("axioms", axiomsList);
            result.put("message", axiomsList.size() < inferredAxioms.size() 
                ? "Showing first 100 of " + inferredAxioms.size() + " inferred axioms"
                : "Showing all " + inferredAxioms.size() + " inferred axioms");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error getting inferred axioms", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred superclasses for a class
     * GET /api/ontology/{projectId}/reasoner/inferred-superclasses
     */
    @GetMapping("/{projectId}/reasoner/inferred-superclasses")
    public ResponseEntity<Map<String, Object>> getInferredSuperClasses(
            @PathVariable String projectId,
            @RequestParam String classIri,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            
            Set<OWLClass> superClasses = reasonerService.getInferredSuperClasses(ontology, owlClass, type);
            
            List<Map<String, String>> superClassesList = superClasses.stream()
                .map(cls -> Map.of(
                    "iri", cls.getIRI().toString(),
                    "label", getLabel(cls, ontology)
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "classIri", classIri,
                "reasonerType", type.getDisplayName(),
                "inferredSuperClasses", superClassesList
            ));
            
        } catch (Exception e) {
            log.error("Error getting inferred superclasses", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred subclasses for a class
     * GET /api/ontology/{projectId}/reasoner/inferred-subclasses
     */
    @GetMapping("/{projectId}/reasoner/inferred-subclasses")
    public ResponseEntity<Map<String, Object>> getInferredSubClasses(
            @PathVariable String projectId,
            @RequestParam String classIri,
            @RequestParam(defaultValue = "STRUCTURAL") String reasonerType,
            @RequestParam(defaultValue = "false") boolean direct
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "ELK" : reasonerType;
            int axiomCount = ontology.getAxiomCount();
            if (axiomCount > MEDIUM_ONTOLOGY_THRESHOLD && axiomCount <= LARGE_ONTOLOGY_AXIOM_THRESHOLD
                    && !effectiveType.equalsIgnoreCase("ELK") && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                effectiveType = "ELK";
            }
            if (axiomCount > LARGE_ONTOLOGY_AXIOM_THRESHOLD && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                effectiveType = "STRUCTURAL";
            }
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            OWLReasoner reasoner = reasonerService.getReasoner(ontology, type);

            Set<OWLClass> subClasses = reasonerService.getInferredSubClasses(ontology, owlClass, type, direct);

            List<Map<String, Object>> subClassesList = subClasses.stream()
                .map(cls -> {
                    boolean hasChildren = reasoner.getSubClasses(cls, true).getFlattened().stream()
                            .anyMatch(c -> !c.isOWLNothing() && !c.equals(cls));
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("iri", cls.getIRI().toString());
                    entry.put("label", getLabel(cls, ontology));
                    entry.put("hasChildren", hasChildren);
                    return entry;
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "classIri", classIri,
                "reasonerType", type.getDisplayName(),
                "direct", direct,
                "inferredSubClasses", subClassesList
            ));

        } catch (Exception e) {
            log.error("Error getting inferred subclasses", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred class hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-class-hierarchy
     */
    private static final int MEDIUM_ONTOLOGY_THRESHOLD = 10_000;   // ELK kicks in above this
    private static final int LARGE_ONTOLOGY_AXIOM_THRESHOLD = 100_000; // STRUCTURAL fallback above this
    private static final int HIERARCHY_TIMEOUT_SECONDS = 5;
    private static final int INITIAL_HIERARCHY_DEPTH = 2;

    @GetMapping("/{projectId}/reasoner/inferred-class-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredClassHierarchy(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "OPENLLET") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "OPENLLET" : reasonerType;

            int axiomCount = ontology.getAxiomCount();
            // Medium ontologies (10k-100k): switch to ELK (fast EL reasoner, 10-100x faster than HermiT)
            if (axiomCount > MEDIUM_ONTOLOGY_THRESHOLD
                    && axiomCount <= LARGE_ONTOLOGY_AXIOM_THRESHOLD
                    && !effectiveType.equalsIgnoreCase("ELK")
                    && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                log.info("Medium ontology ({} axioms) — switching to ELK for project {}", axiomCount, projectId);
                effectiveType = "ELK";
            }
            // Very large ontologies (>100k): STRUCTURAL is safest (no reasoning, just asserted)
            if (axiomCount > LARGE_ONTOLOGY_AXIOM_THRESHOLD && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                log.warn("Very large ontology ({} axioms) — switching to STRUCTURAL for project {}", axiomCount, projectId);
                effectiveType = "STRUCTURAL";
            }

            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            // Return cached hierarchy immediately if still fresh (Caffeine handles TTL)
            String cacheKey = projectId + "-" + type.name();
            EditorReasonerCacheService.HierarchyCacheEntry cached =
                    editorReasonerCache.getHierarchy(cacheKey).orElse(null);
            if (cached != null) {
                log.info("Returning cached hierarchy for project {} ({})", projectId, type);
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "reasonerType", cached.reasonerType(),
                        "hierarchy", cached.hierarchy(),
                        "lazy", true, "cached", true
                ));
            }

            log.info("Building class hierarchy for project {} with {} ({} axioms)", projectId, type, axiomCount);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                ReasonerType finalType = type;
                Future<Map<String, Object>> future = executor.submit(() -> {
                    OWLReasoner reasoner = reasonerService.getReasoner(ontology, finalType);
                    OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
                    OWLClass thing = df.getOWLThing();
                    OWLClass nothing = df.getOWLNothing();

                    Set<String> visited = new HashSet<>();
                    Map<String, Object> root = buildClassNode(ontology, reasoner, thing, visited, INITIAL_HIERARCHY_DEPTH);

                    List<Map<String, Object>> hierarchy = new ArrayList<>();
                    hierarchy.add(root);

                    Node<OWLClass> unsatisfiableNode = reasoner.getUnsatisfiableClasses();
                    if (unsatisfiableNode.getSize() > 1 || !reasoner.getSubClasses(nothing, true).isEmpty()) {
                        hierarchy.add(buildClassNode(ontology, reasoner, nothing, visited, INITIAL_HIERARCHY_DEPTH));
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("hierarchy", hierarchy);
                    result.put("reasonerType", finalType.getDisplayName());
                    result.put("totalClasses", visited.size());
                    return result;
                });

                Map<String, Object> result = future.get(HIERARCHY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> builtHierarchy = (List<Map<String, Object>>) result.get("hierarchy");
                editorReasonerCache.putHierarchy(cacheKey, new EditorReasonerCacheService.HierarchyCacheEntry(
                        builtHierarchy, (String) result.get("reasonerType")));
                log.info("Hierarchy built for project {} — {} classes", projectId, result.get("totalClasses"));

                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "reasonerType", result.get("reasonerType"),
                        "hierarchy", builtHierarchy,
                        "lazy", true
                ));
            } catch (TimeoutException e) {
                executor.shutdownNow();
                log.warn("Hierarchy timed out after {}s for project {} — returning timeout signal", HIERARCHY_TIMEOUT_SECONDS, projectId);
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "reasonerType", type.getDisplayName(),
                        "hierarchy", List.of(),
                        "timeout", true,
                        "lazy", true
                ));
            } finally {
                executor.shutdown();
            }
        } catch (Exception e) {
            log.error("Error getting inferred class hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private Map<String, Object> buildClassNode(OWLOntology ontology, OWLReasoner reasoner,
                                               OWLClass owlClass, Set<String> visited, int maxDepth) {
        String iri = owlClass.getIRI().toString();

        List<Map<String, String>> equivalentClasses = reasoner.getEquivalentClasses(owlClass).getEntities().stream()
                .filter(cls -> !cls.equals(owlClass))
                .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", getLabel(cls, ontology)))
                .collect(Collectors.toList());

        if (visited.contains(iri) && !owlClass.isOWLThing() && !owlClass.isOWLNothing()) {
            return Map.of("id", iri, "label", getLabel(owlClass, ontology),
                    "children", List.of(), "hasChildren", false, "equivalentClasses", equivalentClasses);
        }
        visited.add(iri);

        NodeSet<OWLClass> subClassesNodeSet = reasoner.getSubClasses(owlClass, true);
        boolean hasAnyChildren = subClassesNodeSet.getFlattened().stream()
                .anyMatch(c -> !c.isOWLNothing() && !c.equals(owlClass));

        List<Map<String, Object>> children = new ArrayList<>();
        if (maxDepth > 0) {
            for (Node<OWLClass> subClassNode : subClassesNodeSet) {
                OWLClass representative = subClassNode.getRepresentativeElement();
                if (representative.isOWLNothing() && !owlClass.isOWLThing()) continue;
                if (representative.equals(owlClass)) continue;
                children.add(buildClassNode(ontology, reasoner, representative, visited, maxDepth - 1));
            }
            children.sort(Comparator.comparing(m -> m.get("label").toString()));
        }

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", getLabel(owlClass, ontology));
        node.put("children", children);
        node.put("hasChildren", hasAnyChildren);
        node.put("type", "Class");
        node.put("equivalentClasses", equivalentClasses);
        if (owlClass.isOWLNothing() || !reasoner.isSatisfiable(owlClass)) {
            node.put("isUnsatisfiable", true);
        }
        return node;
    }

    private List<Map<String, Object>> buildAnnotationPropertyHierarchy(OWLOntology ontology) {
        Set<OWLAnnotationProperty> signature = ontology.getAnnotationPropertiesInSignature();
        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        Map<String, Set<String>> superMap = new HashMap<>();

        for (OWLAnnotationProperty prop : signature) {
            String iri = prop.getIRI().toString();
            Map<String, Object> node = new HashMap<>();
            node.put("id", iri);
            node.put("label", getLabel(prop, ontology));
            node.put("type", "AnnotationProperty");
            node.put("children", new ArrayList<Map<String, Object>>());
            node.put("hasChildren", false);
            nodeMap.put(iri, node);
        }

        for (OWLSubAnnotationPropertyOfAxiom axiom : ontology.getAxioms(AxiomType.SUB_ANNOTATION_PROPERTY_OF)) {
            if (!axiom.getSubProperty().isNamed() || !axiom.getSuperProperty().isNamed()) {
                continue;
            }
            String sub = axiom.getSubProperty().asOWLAnnotationProperty().getIRI().toString();
            String sup = axiom.getSuperProperty().asOWLAnnotationProperty().getIRI().toString();
            if (!nodeMap.containsKey(sub) || !nodeMap.containsKey(sup) || sub.equals(sup)) {
                continue;
            }
            superMap.computeIfAbsent(sub, ignored -> new HashSet<>()).add(sup);
        }

        Set<String> hasSuper = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : superMap.entrySet()) {
            String subIri = entry.getKey();
            for (String superIri : entry.getValue()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) nodeMap.get(superIri).get("children");
                children.add(nodeMap.get(subIri));
                nodeMap.get(superIri).put("hasChildren", true);
                hasSuper.add(subIri);
            }
        }

        return nodeMap.values().stream()
                .filter(node -> !hasSuper.contains(node.get("id")))
                .sorted(Comparator.comparing(m -> m.get("label").toString()))
                .collect(Collectors.toList());
    }

    /**
     * Get inferred object property hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-object-property-hierarchy
     */
    @GetMapping("/{projectId}/reasoner/inferred-object-property-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredObjectPropertyHierarchy(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "OPENLLET") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            // HERMIT → OPENLLET (binary compat); ELK → OPENLLET (ELK has no property hierarchy support)
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") || reasonerType.equalsIgnoreCase("ELK")
                    ? "OPENLLET" : reasonerType;
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            log.info("========== Object Property Hierarchy Request ==========");
            log.info("Project ID: {}", projectId);
            log.info("Ontology loaded - Total axioms: {}", ontology.getAxiomCount());
            log.info("Object properties in signature: {}", ontology.getObjectPropertiesInSignature().size());

            // Ensure classification is done before building property hierarchy
            log.info("Ensuring classification for project {} with {} (Object Properties)", projectId, type);
            reasonerService.classify(ontology, type);

            OWLReasoner reasoner = reasonerService.getReasoner(ontology, type);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLObjectProperty topProperty = df.getOWLTopObjectProperty();

            Set<String> visited = new HashSet<>();
            Map<String, Object> root = null;
            
            // ELK does not support property hierarchy inference
            // Catch UnsupportedOperationException and fall back to asserted properties
            try {
                root = buildObjectPropertyNode(ontology, reasoner, topProperty, visited);
            } catch (UnsupportedOperationException e) {
                log.warn("Reasoner {} does not support object property hierarchy. Falling back to asserted properties.", type.getDisplayName());
                root = new HashMap<>();
                root.put("id", topProperty.getIRI().toString());
                root.put("label", "owl:topObjectProperty");
                root.put("children", List.of());
                root.put("hasChildren", false);
                root.put("type", "ObjectProperty");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
            
            log.info("Inferred object property hierarchy built for project: {}. Root node has {} children. Total visited: {}", 
                projectId, children.size(), visited.size());
            
            // If no inferred properties found, fall back to asserted properties from the ontology
            if (children.isEmpty()) {
                log.warn("No inferred object properties found from reasoner. Checking ontology signature...");
                int totalProps = ontology.getObjectPropertiesInSignature().size();
                log.warn("Ontology has {} object properties in signature", totalProps);
                
                children = ontology.getObjectPropertiesInSignature().stream()
                    .filter(prop -> !prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty())
                    .map(prop -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("children", List.of());
                        node.put("hasChildren", false);
                        node.put("type", "ObjectProperty");
                        log.info("  Adding fallback property: {} ({})", getLabel(prop, ontology), prop.getIRI().toString());
                        return node;
                    })
                    .collect(Collectors.toList());
                root.put("children", children);
                log.warn("Added {} properties as fallback", children.size());
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "reasonerType", type.getDisplayName(),
                    "hierarchy", List.of(root)
            ));
        } catch (Exception e) {
            log.error("Error getting inferred object property hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred data property hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-data-property-hierarchy
     */
    @GetMapping("/{projectId}/reasoner/inferred-data-property-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredDataPropertyHierarchy(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "OPENLLET") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            // HERMIT → OPENLLET (binary compat); ELK → OPENLLET (ELK has no property hierarchy support)
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") || reasonerType.equalsIgnoreCase("ELK")
                    ? "OPENLLET" : reasonerType;
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());
            log.info("========== Data Property Hierarchy Request ==========");
            log.info("Project ID: {}", projectId);
            log.info("Ontology loaded - Total axioms: {}", ontology.getAxiomCount());
            log.info("Data properties in signature: {}", ontology.getDataPropertiesInSignature().size());
            // Ensure classification is done before building property hierarchy
            log.info("Ensuring classification for project {} with {} (Data Properties)", projectId, type);
            reasonerService.classify(ontology, type);

            OWLReasoner reasoner = reasonerService.getReasoner(ontology, type);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLDataProperty topProperty = df.getOWLTopDataProperty();

            Set<String> visited = new HashSet<>();
            Map<String, Object> root = null;
            
            // ELK does not support property hierarchy inference
            // Catch UnsupportedOperationException and fall back to asserted properties
            try {
                root = buildDataPropertyNode(ontology, reasoner, topProperty, visited);
            } catch (UnsupportedOperationException e) {
                log.warn("Reasoner {} does not support data property hierarchy. Falling back to asserted properties.", type.getDisplayName());
                root = new HashMap<>();
                root.put("id", topProperty.getIRI().toString());
                root.put("label", "owl:topDataProperty");
                root.put("children", List.of());
                root.put("hasChildren", false);
                root.put("type", "DataProperty");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
            
            log.info("Inferred data property hierarchy built for project: {}. Root node has {} children. Total visited: {}", 
                projectId, children.size(), visited.size());
            
            // If no inferred properties found, fall back to asserted properties from the ontology
            if (children.isEmpty()) {
                log.warn("No inferred data properties found. Falling back to asserted properties.");
                children = ontology.getDataPropertiesInSignature().stream()
                    .filter(prop -> !prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty())
                    .map(prop -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("children", List.of());
                        node.put("hasChildren", false);
                        node.put("type", "DataProperty");
                        return node;
                    })
                    .collect(Collectors.toList());
                root.put("children", children);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "reasonerType", type.getDisplayName(),
                    "hierarchy", List.of(root)
            ));
        } catch (Exception e) {
            log.error("Error getting inferred data property hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
                        

    private Map<String, Object> buildObjectPropertyNode(OWLOntology ontology, OWLReasoner reasoner, OWLObjectProperty property, Set<String> visited) {
        String iri = property.getIRI().toString();
        
        // Get equivalent properties
        Node<OWLObjectPropertyExpression> equivalentNode = reasoner.getEquivalentObjectProperties(property);
        List<Map<String, String>> equivalentProperties = equivalentNode.getEntities().stream()
                .filter(p -> !p.equals(property) && !p.isAnonymous())
                .map(p -> Map.of(
                    "iri", p.asOWLObjectProperty().getIRI().toString(),
                    "label", getLabel(p.asOWLObjectProperty(), ontology)
                ))
                .collect(Collectors.toList());

        // Don't skip top property to allow building the tree from root
        if (visited.contains(iri) && !property.isOWLTopObjectProperty()) {
            return Map.of(
                "id", iri, 
                "label", getLabel(property, ontology), 
                "children", List.of(), 
                "hasChildren", false,
                "equivalentProperties", equivalentProperties
            );
        }
        visited.add(iri);

        NodeSet<OWLObjectPropertyExpression> subPropsNodeSet = reasoner.getSubObjectProperties(property, true);
        List<Map<String, Object>> children = new ArrayList<>();
        
        for (Node<OWLObjectPropertyExpression> subPropNode : subPropsNodeSet) {
            OWLObjectPropertyExpression representative = subPropNode.getRepresentativeElement();
            if (representative.isAnonymous()) continue;
            
            OWLObjectProperty subProp = representative.asOWLObjectProperty();
            if (subProp.isOWLBottomObjectProperty() || subProp.equals(property)) {
                continue;
            }
            
            Map<String, Object> childNode = buildObjectPropertyNode(ontology, reasoner, subProp, visited);
            if (childNode != null) {
                children.add(childNode);
            }
        }
        
        children.sort(Comparator.comparing(m -> m.get("label").toString()));

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", getLabel(property, ontology));
        node.put("children", children);
        node.put("hasChildren", !children.isEmpty());
        node.put("type", "ObjectProperty");
        node.put("equivalentProperties", equivalentProperties);
        
        return node;
    }

    private Map<String, Object> buildDataPropertyNode(OWLOntology ontology, OWLReasoner reasoner, OWLDataProperty property, Set<String> visited) {
        String iri = property.getIRI().toString();
        
        // Get equivalent properties
        Node<OWLDataProperty> equivalentNode = reasoner.getEquivalentDataProperties(property);
        List<Map<String, String>> equivalentProperties = equivalentNode.getEntities().stream()
                .filter(p -> !p.equals(property))
                .map(p -> Map.of(
                    "iri", p.getIRI().toString(),
                    "label", getLabel(p, ontology)
                ))
                .collect(Collectors.toList());

        // Don't skip top property to allow building the tree from root
        if (visited.contains(iri) && !property.isOWLTopDataProperty()) {
            return Map.of(
                "id", iri, 
                "label", getLabel(property, ontology), 
                "children", List.of(), 
                "hasChildren", false,
                "equivalentProperties", equivalentProperties
            );
        }
        visited.add(iri);

        NodeSet<OWLDataProperty> subPropsNodeSet = reasoner.getSubDataProperties(property, true);
        List<Map<String, Object>> children = new ArrayList<>();
        
        for (Node<OWLDataProperty> subPropNode : subPropsNodeSet) {
            OWLDataProperty representative = subPropNode.getRepresentativeElement();
            if (representative.isOWLBottomDataProperty() || representative.equals(property)) {
                continue;
            }
            
            Map<String, Object> childNode = buildDataPropertyNode(ontology, reasoner, representative, visited);
            if (childNode != null) {
                children.add(childNode);
            }
        }
        
        children.sort(Comparator.comparing(m -> m.get("label").toString()));

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", getLabel(property, ontology));
        node.put("children", children);
        node.put("hasChildren", !children.isEmpty());
        node.put("type", "DatatypeProperty");
        node.put("equivalentProperties", equivalentProperties);
        
        return node;
    }

    /**
     * Get inferred instances for a class
     * GET /api/ontology/{projectId}/reasoner/inferred-instances
     */
    @GetMapping("/{projectId}/reasoner/inferred-instances")
    public ResponseEntity<Map<String, Object>> getInferredInstances(
            @PathVariable String projectId,
            @RequestParam String classIri,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            
            Set<OWLNamedIndividual> instances = reasonerService.getInferredInstances(ontology, owlClass, type);
            
            List<Map<String, String>> instancesList = instances.stream()
                .map(ind -> Map.of(
                    "iri", ind.getIRI().toString(),
                    "label", getLabel(ind, ontology)
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "classIri", classIri,
                "reasonerType", type.getDisplayName(),
                "inferredInstances", instancesList
            ));
            
        } catch (Exception e) {
            log.error("Error getting inferred instances", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get all inferred individuals with their types
     * GET /api/ontology/{projectId}/reasoner/inferred-individuals
     */
    @GetMapping("/{projectId}/reasoner/inferred-individuals")
    public ResponseEntity<Map<String, Object>> getAllInferredIndividuals(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

            List<Map<String, Object>> individualsList = ontology.getIndividualsInSignature().stream()
                    .filter(OWLNamedIndividual::isNamed)
                    .map(ind -> {
                        Set<OWLClass> types = reasonerService.getInferredTypes(ontology, ind, type);
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", ind.getIRI().toString());
                        map.put("label", getLabel(ind, ontology));
                        map.put("type", "Individual");
                        map.put("inferredTypes", types.stream()
                                .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", getLabel(cls, ontology)))
                                .collect(Collectors.toList()));
                        return map;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "individuals", individualsList
            ));
        } catch (Exception e) {
            log.error("Error getting all inferred individuals", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred types for a single individual
     * GET /api/ontology/{projectId}/reasoner/inferred-individual-types?individualIri=...
     */
    @GetMapping("/{projectId}/reasoner/inferred-individual-types")
    public ResponseEntity<Map<String, Object>> getInferredTypesForIndividual(
            @PathVariable String projectId,
            @RequestParam String individualIri,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLNamedIndividual individual = df.getOWLNamedIndividual(IRI.create(individualIri));

            Set<OWLClass> assertedTypes = ontology.getClassAssertionAxioms(individual).stream()
                    .map(OWLClassAssertionAxiom::getClassExpression)
                    .filter(OWLClassExpression::isNamed)
                    .map(OWLClassExpression::asOWLClass)
                    .collect(Collectors.toSet());

            Set<OWLClass> inferredTypes = reasonerService.getInferredTypes(ontology, individual, type);
            OWLClass nothing = df.getOWLNothing();
            OWLClass thing = df.getOWLThing();

            List<Map<String, String>> typesList = inferredTypes.stream()
                    .filter(cls -> !cls.equals(nothing) && !cls.equals(thing))
                    .filter(cls -> !assertedTypes.contains(cls))
                    .map(cls -> Map.of(
                            "iri", cls.getIRI().toString(),
                            "label", getLabel(cls, ontology)
                    ))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "individualIri", individualIri,
                    "inferredTypes", typesList
            ));
        } catch (Exception e) {
            log.error("Error getting inferred types for individual {}", individualIri, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred annotation property hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-annotation-property-hierarchy
     */
    @GetMapping("/{projectId}/reasoner/inferred-annotation-property-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredAnnotationPropertyHierarchy(
            @PathVariable String projectId
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            
            List<Map<String, Object>> hierarchy = buildAnnotationPropertyHierarchy(ontology);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "hierarchy", hierarchy
            ));
        } catch (Exception e) {
            log.error("Error getting inferred annotation property hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred datatypes
     * GET /api/ontology/{projectId}/reasoner/inferred-datatypes
     */
    @GetMapping("/{projectId}/reasoner/inferred-datatypes")
    public ResponseEntity<Map<String, Object>> getInferredDatatypes(
            @PathVariable String projectId
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            
            List<Map<String, Object>> datatypes = ontology.getDatatypesInSignature().stream()
                    .map(dt -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", dt.getIRI().toString());
                        node.put("label", getLabel(dt, ontology));
                        node.put("type", "Datatype");
                        return node;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "datatypes", datatypes
            ));
        } catch (Exception e) {
            log.error("Error getting inferred datatypes", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred details for a class (superclasses, equivalent classes)
     * GET /api/ontology/{projectId}/reasoner/inferred-class-details?classIri=...
     */
    @GetMapping("/{projectId}/reasoner/inferred-class-details")
    public ResponseEntity<?> getInferredClassDetails(
            @PathVariable String projectId,
            @RequestParam String classIri,
            @RequestParam(defaultValue = "OPENLLET") ReasonerType type) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            OWLReasoner reasoner = reasonerService.getReasoner(ontology, type);
            
            OWLClass owlClass = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLClass(IRI.create(classIri));
            
            Map<String, Object> result = new HashMap<>();
            
            // Inferred Superclasses
            NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(owlClass, true);
            List<Map<String, String>> inferredSuperClasses = superClasses.getNodes().stream()
                .flatMap(node -> node.getEntities().stream())
                .filter(cls -> !cls.isOWLThing() && !cls.equals(owlClass))
                .map(cls -> Map.of(
                    "id", cls.getIRI().toString(),
                    "type", "SubClassOf",
                    "definition", getLabel(cls, ontology)
                ))
                .collect(Collectors.toList());
            result.put("inferredSubClassOfAxioms", inferredSuperClasses);
            
            // Inferred Equivalent Classes
            Node<OWLClass> equivalentClasses = reasoner.getEquivalentClasses(owlClass);
            List<Map<String, String>> inferredEquivalentClasses = equivalentClasses.getEntities().stream()
                .filter(cls -> !cls.equals(owlClass))
                .map(cls -> Map.of(
                    "id", cls.getIRI().toString(),
                    "type", "EquivalentTo",
                    "definition", getLabel(cls, ontology)
                ))
                .collect(Collectors.toList());
            result.put("inferredEquivalentClassesAxioms", inferredEquivalentClasses);
            
            // Check if unsatisfiable
            result.put("isUnsatisfiable", !reasoner.isSatisfiable(owlClass));
            
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            log.error("Error getting inferred class details", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Get inferred details for a property (superproperties, equivalent properties)
     * GET /api/ontology/{projectId}/reasoner/inferred-property-details?propertyIri=...
     */
    @GetMapping("/{projectId}/reasoner/inferred-property-details")
    public ResponseEntity<?> getInferredPropertyDetails(
            @PathVariable String projectId,
            @RequestParam String propertyIri,
            @RequestParam(defaultValue = "OPENLLET") ReasonerType type) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            OWLReasoner reasoner = reasonerService.getReasoner(ontology, type);
            
            IRI iri = IRI.create(propertyIri);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            
            Map<String, Object> result = new HashMap<>();
            
            if (ontology.containsObjectPropertyInSignature(iri)) {
                OWLObjectProperty property = df.getOWLObjectProperty(iri);
                
                // Super properties
                NodeSet<OWLObjectPropertyExpression> superProps = reasoner.getSuperObjectProperties(property, true);
                List<Map<String, String>> inferredSuperProps = superProps.getNodes().stream()
                    .flatMap(node -> node.getEntities().stream())
                    .filter(p -> !p.isOWLTopObjectProperty() && !p.equals(property))
                    .map(p -> Map.of(
                        "id", p.asOWLObjectProperty().getIRI().toString(),
                        "type", "SubPropertyOf",
                        "definition", getLabel(p.asOWLObjectProperty(), ontology)
                    ))
                    .collect(Collectors.toList());
                result.put("inferredSubPropertyOfAxioms", inferredSuperProps);
                
                // Equivalent properties
                Node<OWLObjectPropertyExpression> equivProps = reasoner.getEquivalentObjectProperties(property);
                List<Map<String, String>> inferredEquivProps = equivProps.getEntities().stream()
                    .filter(p -> !p.equals(property) && !p.isAnonymous())
                    .map(p -> {
                        OWLObjectProperty prop = p.asOWLObjectProperty();
                        return Map.of(
                            "id", prop.getIRI().toString(),
                            "type", "EquivalentTo",
                            "definition", getLabel(prop, ontology)
                        );
                    })
                    .collect(Collectors.toList());
                result.put("inferredEquivalentPropertiesAxioms", inferredEquivProps);
                
            } else if (ontology.containsDataPropertyInSignature(iri)) {
                OWLDataProperty property = df.getOWLDataProperty(iri);
                
                // Super properties
                NodeSet<OWLDataProperty> superProps = reasoner.getSuperDataProperties(property, true);
                List<Map<String, String>> inferredSuperProps = superProps.getNodes().stream()
                    .flatMap(node -> node.getEntities().stream())
                    .filter(p -> !p.isOWLTopDataProperty() && !p.equals(property))
                    .map(p -> Map.of(
                        "id", p.getIRI().toString(),
                        "type", "SubPropertyOf",
                        "definition", getLabel(p, ontology)
                    ))
                    .collect(Collectors.toList());
                result.put("inferredSubPropertyOfAxioms", inferredSuperProps);
                
                // Equivalent properties
                Node<OWLDataProperty> equivProps = reasoner.getEquivalentDataProperties(property);
                List<Map<String, String>> inferredEquivProps = equivProps.getEntities().stream()
                    .filter(p -> !p.equals(property))
                    .map(p -> Map.of(
                        "id", p.getIRI().toString(),
                        "type", "EquivalentTo",
                        "definition", getLabel(p, ontology)
                    ))
                    .collect(Collectors.toList());
                result.put("inferredEquivalentPropertiesAxioms", inferredEquivProps);
            }
            
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            log.error("Error getting inferred property details", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Get reasoner statistics
     * GET /api/ontology/{projectId}/reasoner/stats
     */
    @GetMapping("/{projectId}/reasoner/stats")
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
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Run full reasoning (consistency + classify + realize)
     * POST /api/ontology/{projectId}/reasoner/run
     */
    @PostMapping("/{projectId}/reasoner/run")
    public ResponseEntity<Map<String, Object>> runReasoner(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            ResponseEntity<Map<String, Object>> async = reasoningJobSubmitService.submit(
                    "REASONER_RUN", projectId, reasonerType, null);
            if (async != null) {
                return async;
            }

            log.info("Running full reasoning for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            Map<String, Object> result = new HashMap<>();
            long totalStartTime = System.currentTimeMillis();
            
            // Step 1: Consistency check
            long startTime = System.currentTimeMillis();
            boolean isConsistent = reasonerService.isConsistent(ontology, type);
            result.put("consistencyCheckMs", System.currentTimeMillis() - startTime);
            result.put("consistent", isConsistent);
            
            if (!isConsistent) {
                Set<OWLClass> unsatisfiable = reasonerService.getUnsatisfiableClasses(ontology, type);
                result.put("unsatisfiableClassCount", unsatisfiable.size());
                result.put("message", "Ontology is inconsistent. Found " + unsatisfiable.size() + " unsatisfiable classes.");
                result.put("success", false);
                return ResponseEntity.ok(result);
            }
            
            // Step 2: Classification
            startTime = System.currentTimeMillis();
            reasonerService.classify(ontology, type);
            result.put("classificationMs", System.currentTimeMillis() - startTime);
            
            // Step 3: Realization
            startTime = System.currentTimeMillis();
            reasonerService.realize(ontology, type);
            result.put("realizationMs", System.currentTimeMillis() - startTime);
            
            // Get inferred axioms count
            Set<OWLAxiom> inferredAxioms = reasonerService.getInferredAxioms(ontology, type);
            result.put("inferredAxiomsCount", inferredAxioms.size());
            
            result.put("totalDurationMs", System.currentTimeMillis() - totalStartTime);
            result.put("reasonerType", type.getDisplayName());
            result.put("success", true);
            result.put("message", "Reasoning completed successfully");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error running reasoner", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Clear reasoner cache
     * POST /api/ontology/reasoner/clear-cache
     */
    @PostMapping("/reasoner/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        try {
            editorReasonerCache.clearAll();
            
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
        // Convert axiom to a more readable format
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
