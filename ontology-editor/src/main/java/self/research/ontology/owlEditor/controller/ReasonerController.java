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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.common.ReasoningFriendlyErrors;
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

    // Wall-clock cap for the getAllInferredIndividuals() loop — see comment at its call site.
    // Desktop is single-user/single-request, so it can afford a much longer budget than a
    // shared cloud deployment (see application-desktop.properties overrides).
    @Value("${ontocode.reasoner.inferred-individuals-budget-ms:20000}")
    private long INFERRED_INDIVIDUALS_BUDGET_MS;

    @Autowired
    private GridFsTemplate gridfs;

    @Autowired
    private ReasonerService reasonerService;

    @Autowired
    private SparqlDatasetService datasetService;

    @Autowired
    private EditorReasonerCacheService editorReasonerCache;

    @Autowired(required = false)
    private self.research.ontology.owlEditor.service.ProjectImportService projectImportService;

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Autowired(required = false)
    private self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext owlApiContext;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    // Ontologies above this triple count are rejected before export to prevent OOM
    @Value("${ontocode.reasoner.max-triples:5000000}")
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

        // Desktop OWLAPI-first: reuse the model already cached in memory instead of exporting
        // to Fuseki and re-parsing from scratch — this is how Protege reasons too, with no
        // external triple store involved at all. Fuseki on desktop is lazy/deferred (may not
        // even be started), so the old unconditional sync-then-stream path below would fail or
        // time out whenever it wasn't already running, surfacing as "We could not find this
        // project's ontology" even though the ontology was sitting in memory the whole time.
        // copyOntology(DEEP) gives the reasoner its own independent snapshot in a fresh manager —
        // editorReasonerCache has always held an isolated copy (not the live, mutable model), so
        // a concurrent edit elsewhere can't invalidate axioms out from under an in-flight
        // reasoning call; this only changes where that copy's source content comes from.
        if (owlApiContext != null && owlApiContext.hasOntology(projectId)) {
            Optional<OWLOntology> live = owlApiContext.ontology(projectId);
            if (live.isPresent()) {
                try {
                    OWLOntologyManager snapshotManager = OWLManager.createOWLOntologyManager();
                    OWLOntology snapshot = snapshotManager.copyOntology(live.get(), org.semanticweb.owlapi.model.parameters.OntologyCopy.DEEP);
                    log.info("Ontology loaded from OWLAPI in-memory cache for project {}: {} axioms, {} classes",
                            projectId, snapshot.getAxiomCount(), snapshot.getClassesInSignature().size());
                    editorReasonerCache.putOntology(projectId, snapshot);
                    return snapshot;
                } catch (Exception e) {
                    log.warn("Failed to snapshot in-memory OWLAPI model for {}, falling back to Fuseki/GridFS: {}",
                            projectId, e.getMessage());
                }
            }
        }

        // Cache miss and no in-memory model available (cloud, or desktop before first warm) —
        // stream from Fuseki. On desktop, Fuseki sync after a mutation is deferred (debounced up
        // to 20s+), so force it fresh first. No-ops on cloud and when already in sync.
        if (projectImportService != null) {
            projectImportService.syncProjectToFuseki(projectId);
        }

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
        // Uses N-Triples format (same as the reasoner worker) with explicit OWLAPI format
        // declaration to avoid auto-detect overhead and ensure correct parsing of large exports.
        Path tempFile = null;
        try {
            log.info("Attempting to load ontology from Fuseki for project: {}", projectId);
            tempFile = Files.createTempFile("reasoner-" + projectId + "-", ".nt");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                datasetService.exportDatasetToStream(projectId, RDFFormat.NTRIPLES, out);
            }
            long tempBytes = Files.size(tempFile);
            log.info("Streamed ontology to temp file: {} bytes", tempBytes);
            if (tempBytes > 0) {
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                try (InputStream in = Files.newInputStream(tempFile)) {
                    org.semanticweb.owlapi.io.StreamDocumentSource source =
                        new org.semanticweb.owlapi.io.StreamDocumentSource(
                            in,
                            IRI.create("urn:ontocode:stats:" + projectId),
                            new org.semanticweb.owlapi.formats.NTriplesDocumentFormat(),
                            "application/n-triples");
                    OWLOntology ontology = manager.loadOntologyFromOntologyDocument(source);
                    log.info("Ontology loaded from Fuseki stream: {} axioms, {} classes", ontology.getAxiomCount(), ontology.getClassesInSignature().size());
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

    /**
     * Submit a hierarchy job to the reasoner-worker and return immediately — no blocking wait.
     * Returns the jobId on success, or null if the worker is unavailable/rejected the job, in
     * which case the caller should fall back to local (synchronous, bounded) reasoning.
     *
     * The caller's response carries this jobId back to the client, which polls
     * GET /{projectId}/reasoner/jobs/{jobId} until the job reaches COMPLETED/FAILED. This
     * replaces the previous blocking poll-and-wait here, which tied up an owl-editor request
     * thread for the whole wait window and still risked losing the race against the ALB's
     * idle timeout on a genuinely slow reasoning run — moving the wait to client-side polling
     * means no single HTTP request ever needs to stay open longer than a status check.
     */
    private String submitHierarchyJob(String jobType, String projectId, String reasonerType) {
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return null;
        }
        Map<String, Object> submitted = reasonerWorkerClient.submitJob(jobType, projectId, null, null, reasonerType, null);
        if (Boolean.FALSE.equals(submitted.get("success"))) {
            log.warn("Worker rejected {} job for {}: {}", jobType, projectId, submitted.get("error"));
            return null;
        }
        Object jobId = submitted.get("jobId");
        return jobId != null ? String.valueOf(jobId) : null;
    }

    /**
     * Poll status/result of an async reasoner-worker job submitted by submitHierarchyJob.
     * GET /api/ontology/{projectId}/reasoner/jobs/{jobId}
     */
    @GetMapping("/{projectId}/reasoner/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getReasonerJob(
            @PathVariable String projectId,
            @PathVariable String jobId
    ) {
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false, "error", "Reasoner worker is not enabled on this deployment"));
        }
        Map<String, Object> job = reasonerWorkerClient.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Job not found"));
        }
        Map<String, Object> response = new HashMap<>(job);
        response.put("projectId", projectId);
        return ResponseEntity.ok(response);
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
            return ResponseEntity.ok(friendlyError(e));
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
            // Mirror the mapping from getInferredClassHierarchy: HermiT → OPENLLET so that
            // child queries use the same reasoner as the initial hierarchy load, preventing
            // expand-arrow inconsistencies where OPENLLET marks a node hasChildren:true
            // but ELK returns empty for the same node on expand.
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "OPENLLET" : reasonerType;
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
            return ResponseEntity.ok(friendlyError(e));
        }
    }

    /**
     * Get inferred class hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-class-hierarchy
     */
    private static final int MEDIUM_ONTOLOGY_THRESHOLD = 10_000;   // ELK kicks in above this
    private static final int LARGE_ONTOLOGY_AXIOM_THRESHOLD = 100_000; // STRUCTURAL fallback above this
    // Desktop is single-user/single-request, so it can afford a much longer timeout than a
    // shared cloud deployment (see application-desktop.properties overrides).
    @Value("${ontocode.reasoner.hierarchy-timeout-seconds:5}")
    private int HIERARCHY_TIMEOUT_SECONDS;
    // Pre-populate three levels (owl:Thing → children → grandchildren) so the UI can
    // render and expand the first levels without a lazy fetch. A deeper initial tree
    // closes the window where the user expands a node before its children are loaded.
    private static final int INITIAL_HIERARCHY_DEPTH = 3;

    @GetMapping("/{projectId}/reasoner/inferred-class-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredClassHierarchy(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "OPENLLET") String reasonerType
    ) {
        try {
            String jobId = submitHierarchyJob("REASONER_HIERARCHY", projectId, reasonerType);
            if (jobId != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "async", true, "jobId", jobId, "lazy", true
                ));
            }
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

                    // Inconsistent ontologies cannot be classified — every reasoner call
                    // throws InconsistentOntologyException. Return an explicit signal so
                    // the UI can tell the user instead of rendering an empty tree.
                    if (finalType != ReasonerType.STRUCTURAL && !reasoner.isConsistent()) {
                        Map<String, Object> inconsistent = new HashMap<>();
                        inconsistent.put("hierarchy", List.of());
                        inconsistent.put("reasonerType", finalType.getDisplayName());
                        inconsistent.put("totalClasses", 0);
                        inconsistent.put("inconsistent", true);
                        return inconsistent;
                    }

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
                if (Boolean.TRUE.equals(result.get("inconsistent"))) {
                    log.warn("Ontology for project {} is inconsistent — cannot build inferred hierarchy", projectId);
                    return ResponseEntity.ok(Map.of(
                            "success", false, "projectId", projectId,
                            "reasonerType", result.get("reasonerType"),
                            "hierarchy", List.of(),
                            "inconsistent", true,
                            "message", "The ontology is inconsistent — reasoning cannot proceed. "
                                    + "Use 'Explain inconsistency' to find the conflicting axioms."
                    ));
                }
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
            return ResponseEntity.ok(friendlyError(e));
        }
    }

    private Map<String, Object> buildClassNode(OWLOntology ontology, OWLReasoner reasoner,
                                               OWLClass owlClass, Set<String> visited, int maxDepth) {
        return buildClassNode(ontology, reasoner, owlClass, visited, maxDepth, false);
    }

    private Map<String, Object> buildClassNode(OWLOntology ontology, OWLReasoner reasoner,
                                               OWLClass owlClass, Set<String> visited, int maxDepth,
                                               boolean suppressEquivalentClassesLabel) {
        String iri = owlClass.getIRI().toString();

        Node<OWLClass> equivNode = reasoner.getEquivalentClasses(owlClass);
        List<Map<String, String>> equivalentClasses = suppressEquivalentClassesLabel
                ? List.of()
                : equivNode.getEntities().stream()
                    .filter(cls -> !cls.equals(owlClass))
                    .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", getLabel(cls, ontology)))
                    .collect(Collectors.toList());

        if (visited.contains(iri) && !owlClass.isOWLThing() && !owlClass.isOWLNothing()) {
            return Map.of("id", iri, "label", getLabel(owlClass, ontology),
                    "children", List.of(), "hasChildren", false, "equivalentClasses", equivalentClasses);
        }
        visited.add(iri);

        List<Map<String, Object>> children = new ArrayList<>();
        boolean hasAnyChildren;

        if (owlClass.isOWLNothing()) {
            // Nothing has no real "subclasses" under OWL semantics — getSubClasses(Nothing)
            // is always empty (nothing can be more specific than the empty class). The
            // classes that actually belong "under" Nothing in an inferred hierarchy are the
            // ones EQUIVALENT to it — every unsatisfiable class — which is exactly what
            // getEquivalentClasses(Nothing) returns. Build each as its own child node (like
            // Protege does: individually red, individually clickable) instead of leaving them
            // squashed into Nothing's inline "≡ A, B, C..." label with no way to see/select
            // them one at a time.
            hasAnyChildren = !equivNode.getEntities().isEmpty()
                    && equivNode.getEntities().stream().anyMatch(c -> !c.equals(owlClass));
            if (maxDepth > 0) {
                for (OWLClass equivClass : equivNode.getEntities()) {
                    if (equivClass.equals(owlClass)) continue;
                    // Suppress each unsatisfiable class's own equivalentClasses label — every
                    // member of this set is mutually equivalent to every other by definition,
                    // so repeating the same ~10-name list on every single row would be noisy;
                    // isUnsatisfiable (red) already conveys what actually matters here.
                    children.add(buildClassNode(ontology, reasoner, equivClass, visited, maxDepth - 1, true));
                }
                children.sort(Comparator.comparing(m -> m.get("label").toString()));
            }
        } else {
            NodeSet<OWLClass> subClassesNodeSet = reasoner.getSubClasses(owlClass, true);
            hasAnyChildren = subClassesNodeSet.getFlattened().stream()
                    .anyMatch(c -> !c.isOWLNothing() && !c.equals(owlClass));

            if (maxDepth > 0) {
                for (Node<OWLClass> subClassNode : subClassesNodeSet) {
                    OWLClass representative = subClassNode.getRepresentativeElement();
                    if (representative.isOWLNothing() && !owlClass.isOWLThing()) continue;
                    if (representative.equals(owlClass)) continue;
                    children.add(buildClassNode(ontology, reasoner, representative, visited, maxDepth - 1));
                }
                children.sort(Comparator.comparing(m -> m.get("label").toString()));
            }
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
            String jobId = submitHierarchyJob("REASONER_OBJ_PROP_HIERARCHY", projectId, reasonerType);
            if (jobId != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "async", true, "jobId", jobId
                ));
            }
            OWLOntology ontology = loadOntology(projectId);
            // ELK has no property hierarchy support — everything else (including HERMIT) is
            // used as requested so this reuses whatever reasoner is already classified for
            // this ontology instead of paying for a second, independent classification.
            String effectiveType = reasonerType.equalsIgnoreCase("ELK") ? "OPENLLET" : reasonerType;
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            log.info("========== Object Property Hierarchy Request ==========");
            log.info("Project ID: {}", projectId);
            log.info("Ontology loaded - Total axioms: {}", ontology.getAxiomCount());
            log.info("Object properties in signature: {}", ontology.getObjectPropertiesInSignature().size());

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLObjectProperty topProperty = df.getOWLTopObjectProperty();

            // Openllet's classify() runs full SAT-based classification, which can take many
            // minutes on a huge ontology (e.g. FoodOn's 39k+ classes) — unlike class hierarchy,
            // this endpoint had no timeout at all and could hang the request indefinitely.
            // Bound it the same way, and fall back to asserted (non-inferred) properties on
            // timeout — cheap since it's just reading the ontology signature.
            log.info("Ensuring classification for project {} with {} (Object Properties)", projectId, type);
            ExecutorService objPropExecutor = Executors.newSingleThreadExecutor();
            Map<String, Object> root;
            try {
                ReasonerType finalType = type;
                Future<Map<String, Object>> future = objPropExecutor.submit(() -> {
                    reasonerService.classify(ontology, finalType);
                    OWLReasoner reasoner = reasonerService.getReasoner(ontology, finalType);
                    // ELK does not support property hierarchy inference
                    // Catch UnsupportedOperationException and fall back to asserted properties
                    try {
                        return buildObjectPropertyNode(ontology, reasoner, topProperty, new HashSet<>());
                    } catch (UnsupportedOperationException e) {
                        log.warn("Reasoner {} does not support object property hierarchy. Falling back to asserted properties.", finalType.getDisplayName());
                        return null;
                    }
                });
                root = future.get(HIERARCHY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                objPropExecutor.shutdownNow();
                log.warn("Object property hierarchy classification timed out after {}s for project {} — "
                        + "falling back to asserted properties", HIERARCHY_TIMEOUT_SECONDS, projectId);
                root = null;
            } finally {
                objPropExecutor.shutdown();
            }
            if (root == null) {
                root = new HashMap<>();
                root.put("id", topProperty.getIRI().toString());
                root.put("label", "owl:topObjectProperty");
                root.put("children", List.of());
                root.put("hasChildren", false);
                root.put("type", "ObjectProperty");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");

            log.info("Inferred object property hierarchy built for project: {}. Root node has {} children.",
                projectId, children.size());
            
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
            return ResponseEntity.ok(friendlyError(e));
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
            String jobId = submitHierarchyJob("REASONER_DATA_PROP_HIERARCHY", projectId, reasonerType);
            if (jobId != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "async", true, "jobId", jobId
                ));
            }
            OWLOntology ontology = loadOntology(projectId);
            // HERMIT → OPENLLET (binary compat); ELK → OPENLLET (ELK has no property hierarchy support)
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") || reasonerType.equalsIgnoreCase("ELK")
                    ? "OPENLLET" : reasonerType;
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());
            log.info("========== Data Property Hierarchy Request ==========");
            log.info("Project ID: {}", projectId);
            log.info("Ontology loaded - Total axioms: {}", ontology.getAxiomCount());
            log.info("Data properties in signature: {}", ontology.getDataPropertiesInSignature().size());

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLDataProperty topProperty = df.getOWLTopDataProperty();

            // Same unbounded-classification risk as object property hierarchy — bound it and
            // fall back to asserted (non-inferred) properties on timeout.
            log.info("Ensuring classification for project {} with {} (Data Properties)", projectId, type);
            ExecutorService dataPropExecutor = Executors.newSingleThreadExecutor();
            Map<String, Object> root;
            try {
                ReasonerType finalType = type;
                Future<Map<String, Object>> future = dataPropExecutor.submit(() -> {
                    reasonerService.classify(ontology, finalType);
                    OWLReasoner reasoner = reasonerService.getReasoner(ontology, finalType);
                    // ELK does not support property hierarchy inference
                    // Catch UnsupportedOperationException and fall back to asserted properties
                    try {
                        return buildDataPropertyNode(ontology, reasoner, topProperty, new HashSet<>());
                    } catch (UnsupportedOperationException e) {
                        log.warn("Reasoner {} does not support data property hierarchy. Falling back to asserted properties.", finalType.getDisplayName());
                        return null;
                    }
                });
                root = future.get(HIERARCHY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                dataPropExecutor.shutdownNow();
                log.warn("Data property hierarchy classification timed out after {}s for project {} — "
                        + "falling back to asserted properties", HIERARCHY_TIMEOUT_SECONDS, projectId);
                root = null;
            } finally {
                dataPropExecutor.shutdown();
            }
            if (root == null) {
                root = new HashMap<>();
                root.put("id", topProperty.getIRI().toString());
                root.put("label", "owl:topDataProperty");
                root.put("children", List.of());
                root.put("hasChildren", false);
                root.put("type", "DataProperty");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");

            log.info("Inferred data property hierarchy built for project: {}. Root node has {} children.",
                projectId, children.size());
            
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
            return ResponseEntity.ok(friendlyError(e));
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

            // Each getInferredTypes() call costs O(classCount) against HermiT's
            // InstanceManager — with hundreds of individuals over a large class
            // hierarchy (e.g. FoodOn's 39k+ classes) that adds up to many minutes
            // in aggregate even though no single call hangs. Cap the whole loop's
            // wall-clock time and return whatever was computed so far.
            long deadline = System.currentTimeMillis() + INFERRED_INDIVIDUALS_BUDGET_MS;
            boolean truncated = false;
            List<Map<String, Object>> individualsList = new ArrayList<>();
            for (OWLNamedIndividual ind : ontology.getIndividualsInSignature()) {
                if (!ind.isNamed()) continue;
                if (System.currentTimeMillis() > deadline) {
                    log.warn("Inferred-individuals computation exceeded {} ms budget with {} of {} individuals "
                            + "processed for project {} — returning partial results",
                        INFERRED_INDIVIDUALS_BUDGET_MS, individualsList.size(),
                        ontology.getIndividualsInSignature().size(), projectId);
                    truncated = true;
                    break;
                }
                Set<OWLClass> types = reasonerService.getInferredTypes(ontology, ind, type);
                Map<String, Object> map = new HashMap<>();
                map.put("id", ind.getIRI().toString());
                map.put("label", getLabel(ind, ontology));
                map.put("type", "Individual");
                map.put("inferredTypes", types.stream()
                        .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", getLabel(cls, ontology)))
                        .collect(Collectors.toList()));
                individualsList.add(map);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "truncated", truncated,
                    "projectId", projectId,
                    "individuals", individualsList
            ));
        } catch (Exception e) {
            log.error("Error getting all inferred individuals", e);
            return ResponseEntity.ok(friendlyError(e));
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
            return ResponseEntity.ok(friendlyError(e));
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
            return ResponseEntity.ok(friendlyError(e));
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
            return ResponseEntity.ok(friendlyError(e));
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
            return ResponseEntity.ok(friendlyError(e));
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
            return ResponseEntity.ok(friendlyError(e));
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
            return ResponseEntity.ok(friendlyError(e));
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

    private Map<String, Object> friendlyError(Exception e) {
        String msg = ReasoningFriendlyErrors.forUser(e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", msg);
        String raw = e.getMessage() != null ? e.getMessage().toLowerCase(java.util.Locale.ROOT) : "";
        if (raw.contains("too large") || (raw.contains("triples") && raw.contains("limit"))) {
            body.put("tooLargeForReasoner", true);
        }
        return body;
    }

}
