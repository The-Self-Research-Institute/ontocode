package self.research.ontology.owlEditor.controller;

import self.research.ontology.owlEditor.service.Limit;
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
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.common.ReasoningSnapshot;
import java.util.concurrent.atomic.AtomicReference;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;

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
    private ReasoningJobRelayService reasoningJobRelayService;

    @Autowired(required = false)
    private StorageManager storageManager;

    @Autowired(required = false)
    private self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext owlApiContext;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    // Ontologies above this triple count are rejected before export to prevent OOM
    @Value("${ontocode.reasoner.max-triples:5000000}")
    private long maxReasonerTriples;

    @Value("${ontocode.reasoner.operation-timeout-ms:45000}")
    private long snapshotTimeoutMs = 45_000L;

    @Autowired(required = false)
    private MainGraphRevisionService mainGraphRevisionService;

    static final String SNAPSHOT_JOB_TYPE = "REASONER_SNAPSHOT";

    private static final Set<String> WORKER_JOB_KEYS = Set.of(
            "async", "jobId", "jobType", "projectId", "status", "queuePosition",
            "estimatedWaitTimeMs", "executionTimeMs", "error", "rateLimited");

    private static final class LocalSnapshotRun {
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        final AtomicReference<Future<?>> task = new AtomicReference<>();
        private final List<OWLReasoner> reasoners = new ArrayList<>();
        private boolean stopped;

        synchronized void attach(OWLReasoner reasoner) {
            reasoners.add(reasoner);
            if (stopped) {
                reasoner.interrupt();
            }
        }

        synchronized void stop() {
            stopped = true;
            Future<?> running = task.get();
            if (running != null) {
                running.cancel(true);
            }
            for (OWLReasoner reasoner : reasoners) {
                try {
                    reasoner.interrupt();
                } catch (Exception ignored) {
                }
            }
        }

        synchronized boolean isStopped() {
            return stopped;
        }
    }

    private final ConcurrentHashMap<String, LocalSnapshotRun> snapshotsInFlight = new ConcurrentHashMap<>();

    private final ExecutorService snapshotExecutor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10), r -> {
                Thread t = new Thread(r, "reasoner-snapshot-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

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
        long loadedVersion = publicGraphVersion(projectId);

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
                    editorReasonerCache.putOntology(projectId, snapshot, loadedVersion);
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
            long tripleCount = datasetService.getDatasetSizeOrUnknown(projectId);
            if (tripleCount < 0) {
                throw new IllegalArgumentException("Could not determine the ontology's size for project " + projectId
                        + ", so it was not loaded for in-memory reasoning. Please try again shortly.");
            }
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
                    editorReasonerCache.putOntology(projectId, ontology, loadedVersion);
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
            editorReasonerCache.putOntology(projectId, ontology, loadedVersion);
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
    static final class WorkerQueueFullException extends RuntimeException {
        WorkerQueueFullException(String message) {
            super(message);
        }
    }

    private ResponseEntity<Map<String, Object>> queueFull(String projectId, WorkerQueueFullException e) {
        return ResponseEntity.status(429).body(Map.of(
                "success", false,
                "projectId", projectId,
                "error", e.getMessage()));
    }

    private String submitHierarchyJob(String jobType, String projectId, String reasonerType) {
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return null;
        }
        Map<String, Object> submitted = reasonerWorkerClient.submitJob(jobType, projectId, null, null, reasonerType, null);
        if (Boolean.TRUE.equals(submitted.get("rateLimited"))) {
            throw new WorkerQueueFullException(String.valueOf(submitted.get("error")));
        }
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
        Map<String, Object> response = new HashMap<>(withNestedSnapshotResult(job));
        response.put("projectId", projectId);
        return ResponseEntity.ok(response);
    }

    static Map<String, Object> withNestedSnapshotResult(Map<String, Object> job) {
        if (!SNAPSHOT_JOB_TYPE.equals(job.get("jobType")) || !"COMPLETED".equals(job.get("status"))
                || job.get("result") instanceof Map) {
            return job;
        }
        Map<String, Object> response = new HashMap<>();
        response.put("result", snapshotPart(job));
        job.forEach((key, value) -> {
            if (WORKER_JOB_KEYS.contains(key)) {
                response.put(key, value);
            }
        });
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshotPart(Map<String, Object> job) {
        if (job.get("result") instanceof Map<?, ?> nested) {
            return new LinkedHashMap<>((Map<String, Object>) nested);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        job.forEach((key, value) -> {
            if (!WORKER_JOB_KEYS.contains(key) && !"success".equals(key)) {
                snapshot.put(key, value);
            }
        });
        snapshot.put("success", true);
        return snapshot;
    }

    @GetMapping("/{projectId}/reasoner/snapshot")
    public ResponseEntity<Map<String, Object>> getReasonerSnapshot(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            String requested = reasonerType.trim().toUpperCase(Locale.ROOT);
            ReasonerType requestedType = ReasonerType.valueOf(requested);
            String resolvedUserId = resolveUserId(projectId, userId, request);
            boolean isDraft = draft && resolvedUserId != null && !resolvedUserId.isBlank();
            if (!isDraft && reasonerWorkerEnabled && reasonerWorkerClient != null) {
                ResponseEntity<Map<String, Object>> fromWorker = snapshotFromWorker(projectId, requested);
                if (fromWorker != null) {
                    return fromWorker;
                }
            }
            return localSnapshot(projectId, requestedType, isDraft ? resolvedUserId : null);
        } catch (WorkerQueueFullException e) {
            return queueFull(projectId, e);
        } catch (Exception e) {
            log.error("Error building reasoner snapshot for project {}", projectId, e);
            return ResponseEntity.ok(snapshotError(projectId, e));
        }
    }

    private ResponseEntity<Map<String, Object>> snapshotFromWorker(String projectId, String requested) {
        Map<String, Object> submitted = reasonerWorkerClient.submitJob(SNAPSHOT_JOB_TYPE, projectId, null, null, requested, null);
        if (Boolean.TRUE.equals(submitted.get("rateLimited"))) {
            throw new WorkerQueueFullException(String.valueOf(submitted.get("error")));
        }
        Object jobId = submitted.get("jobId");
        if (Boolean.FALSE.equals(submitted.get("success")) || jobId == null) {
            log.warn("Worker did not take the snapshot job for {} ({}); building it in the editor",
                    projectId, submitted.get("error"));
            return null;
        }
        String status = String.valueOf(submitted.getOrDefault("status", "QUEUED"));
        if ("COMPLETED".equals(status)) {
            Map<String, Object> body = new LinkedHashMap<>(snapshotPart(submitted));
            body.put("success", true);
            body.put("projectId", projectId);
            body.put("async", false);
            return ResponseEntity.ok(body);
        }
        if ("FAILED".equals(status)) {
            Map<String, Object> body = new HashMap<>();
            body.put("success", false);
            body.put("projectId", projectId);
            body.put("error", ReasoningFriendlyErrors.forUser(String.valueOf(submitted.get("error"))));
            return ResponseEntity.ok(body);
        }
        if (reasoningJobRelayService != null) {
            reasoningJobRelayService.rememberSubmittedJob(String.valueOf(jobId), projectId, null, SNAPSHOT_JOB_TYPE, submitted);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("projectId", projectId);
        body.put("async", true);
        body.put("jobId", String.valueOf(jobId));
        body.put("status", status);
        if (submitted.get("queuePosition") != null) {
            body.put("queuePosition", submitted.get("queuePosition"));
        }
        if (submitted.get("estimatedWaitTimeMs") != null) {
            body.put("estimatedWaitTimeMs", submitted.get("estimatedWaitTimeMs"));
        }
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> localSnapshot(String projectId, ReasonerType requestedType,
                                                              String draftUserId) throws Exception {
        boolean isDraft = draftUserId != null;
        long draftVersion = isDraft ? draftGraphVersion(projectId, draftUserId) : 0L;
        long draftWriteStamp = isDraft ? datasetService.getDraftWriteStamp(projectId, draftUserId) : 0L;
        long revision = isDraft ? -1L : mainGraphRevision(projectId);
        String cacheKey = isDraft
                ? EditorReasonerCacheService.draftSnapshotKey(projectId, requestedType.name(), draftUserId,
                        draftVersion + "." + draftWriteStamp)
                : EditorReasonerCacheService.snapshotKey(projectId, requestedType.name(), revision);

        Optional<Map<String, Object>> cached = editorReasonerCache.getSnapshot(cacheKey);
        if (cached.isPresent()) {
            return ResponseEntity.ok(snapshotBody(projectId, cached.get(), true));
        }

        LocalSnapshotRun run = snapshotsInFlight.computeIfAbsent(cacheKey, key -> startLocalSnapshot(
                projectId, requestedType, draftUserId, draftVersion, revision, key));
        run.result.whenComplete((snapshot, error) -> snapshotsInFlight.remove(cacheKey, run));
        try {
            Map<String, Object> snapshot = run.result.get(snapshotTimeoutMs, TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(snapshotBody(projectId, snapshot, false));
        } catch (TimeoutException e) {
            run.stop();
            snapshotsInFlight.remove(cacheKey, run);
            throw new TimeoutException("Reasoning timed out after " + (snapshotTimeoutMs / 1000)
                    + "s — the ontology may be too large or complex for this reasoner.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
        } catch (CancellationException e) {
            throw new TimeoutException("Reasoning was stopped before it finished.");
        }
    }

    private LocalSnapshotRun startLocalSnapshot(String projectId, ReasonerType requestedType, String draftUserId,
                                                long draftVersion, long revision, String cacheKey) {
        LocalSnapshotRun run = new LocalSnapshotRun();
        try {
            run.task.set(snapshotExecutor.submit(() -> {
                try {
                    Map<String, Object> snapshot = computeSnapshot(projectId, requestedType, draftUserId,
                            draftVersion, revision, run);
                    editorReasonerCache.putSnapshot(cacheKey, snapshot);
                    run.result.complete(snapshot);
                } catch (Throwable t) {
                    run.result.completeExceptionally(t);
                }
            }));
        } catch (RejectedExecutionException e) {
            run.result.completeExceptionally(
                    new RuntimeException("Too many reasoning requests in progress — please try again shortly."));
        }
        return run;
    }

    private Map<String, Object> computeSnapshot(String projectId, ReasonerType requestedType, String draftUserId,
                                                long draftVersion, long revision, LocalSnapshotRun run) throws Exception {
        boolean isDraft = draftUserId != null;
        OWLOntology ontology = isDraft
                ? datasetService.loadDraftAsOntology(projectId, draftUserId, draftVersion)
                : loadOntology(projectId);

        ReasonerType classType = requestedType;
        String downgradedWarning = null;
        int axiomCount = ontology.getAxiomCount();
        if (axiomCount > Limit.REASONER_ELK_ABOVE_AXIOMS.value() && axiomCount <= Limit.REASONER_STRUCTURAL_ABOVE_AXIOMS.value()
                && classType != ReasonerType.ELK && classType != ReasonerType.STRUCTURAL) {
            classType = ReasonerType.ELK;
            downgradedWarning = String.format("This ontology has %,d axioms, so ELK was used instead of %s "
                    + "to keep reasoning fast.", axiomCount, requestedType.getDisplayName());
        }
        if (axiomCount > Limit.REASONER_STRUCTURAL_ABOVE_AXIOMS.value() && classType != ReasonerType.STRUCTURAL) {
            classType = ReasonerType.STRUCTURAL;
            downgradedWarning = String.format("This ontology has %,d axioms, so only the asserted (structural) "
                    + "hierarchy is shown instead of %s.", axiomCount, requestedType.getDisplayName());
        }

        OWLReasoner classReasoner;
        try {
            classReasoner = reasonerService.getClassHierarchyReasoner(ontology, classType);
        } catch (RuntimeException e) {
            if (classType != ReasonerType.HERMIT || run.isStopped() || isStopFailure(e)) {
                throw e;
            }
            log.warn("HermiT could not be started for project {}; using Openllet instead", projectId, e);
            classType = ReasonerType.OPENLLET;
            downgradedWarning = "HermiT could not be started for this ontology (" + rootMessage(e)
                    + "), so Openllet was used instead.";
            classReasoner = reasonerService.getClassHierarchyReasoner(ontology, classType);
        }
        run.attach(classReasoner);
        ReasonerType propertyType = classType == ReasonerType.ELK ? ReasonerType.OPENLLET : classType;
        OWLReasoner propertyReasoner = null;
        if (propertyType != classType) {
            propertyReasoner = reasonerService.getUnwarmedReasoner(ontology, propertyType);
            run.attach(propertyReasoner);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("success", true);
        try {
            snapshot.putAll(ReasoningSnapshot.build(ontology, classReasoner, propertyReasoner));
        } catch (RuntimeException e) {
            if (run.isStopped()) {
                reasonerService.disposeReasoner(ontology, classType);
                if (propertyReasoner != null) {
                    reasonerService.disposeReasoner(ontology, propertyType);
                }
            }
            throw e;
        }
        snapshot.put("reasonerType", classType.getDisplayName());
        snapshot.put("propertyReasonerType", propertyType.getDisplayName());
        snapshot.put("requestedReasoner", requestedType.name());
        snapshot.put("ontologyRevision", revision);
        snapshot.put("draft", isDraft);
        if (downgradedWarning != null) {
            snapshot.put("downgradedWarning", downgradedWarning);
        }
        return snapshot;
    }

    private static Map<String, Object> snapshotBody(String projectId, Map<String, Object> snapshot, boolean cached) {
        Map<String, Object> body = new LinkedHashMap<>(snapshot);
        body.put("success", true);
        body.put("projectId", projectId);
        body.put("async", false);
        body.put("cached", cached);
        return body;
    }

    private static boolean isStopFailure(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.util.concurrent.CancellationException
                    || t instanceof org.semanticweb.owlapi.reasoner.ReasonerInterruptedException
                    || t instanceof org.semanticweb.owlapi.reasoner.TimeOutException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    private long mainGraphRevision(String projectId) {
        if (mainGraphRevisionService == null) {
            return -1L;
        }
        try {
            return mainGraphRevisionService.getRevision(projectId);
        } catch (Exception e) {
            log.debug("Could not read the main graph revision for {}: {}", projectId, e.getMessage());
            return -1L;
        }
    }

    private Map<String, Object> snapshotError(String projectId, Exception e) {
        Map<String, Object> body = friendlyError(e);
        body.put("projectId", projectId);
        body.put("error", body.get("message"));
        StringBuilder detail = new StringBuilder(e.getClass().getName());
        if (e.getMessage() != null) {
            detail.append(": ").append(e.getMessage());
        }
        body.put("technicalDetail", detail.toString());
        if (e instanceof TimeoutException) {
            body.put("timeout", true);
        }
        return body;
    }

    @DeleteMapping("/{projectId}/reasoner/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelReasonerJob(
            @PathVariable String projectId,
            @PathVariable String jobId
    ) {
        if (!reasonerWorkerEnabled || reasonerWorkerClient == null) {
            return ResponseEntity.ok(Map.of("success", true, "cancelled", false));
        }
        DLQueryJob relayed = reasoningJobRelayService != null ? reasoningJobRelayService.getJob(jobId) : null;
        if (relayed != null && relayed.getProjectId() != null && !relayed.getProjectId().equals(projectId)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Job not found"));
        }
        Map<String, Object> result = reasonerWorkerClient.cancelJob(jobId);
        if (Boolean.TRUE.equals(result.get("cancelled")) && reasoningJobRelayService != null) {
            reasoningJobRelayService.markStopped(jobId, DLQueryJobController.STOPPED_MESSAGE);
        }
        return ResponseEntity.ok(result);
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
            @RequestParam(defaultValue = "false") boolean direct,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            OWLOntology ontology = loadOntologyForRequest(projectId, userId, draft, request);
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "OPENLLET" : reasonerType;
           
            if (effectiveType.equalsIgnoreCase("ELK")) {
                effectiveType = "OPENLLET";
            }
            int axiomCount = ontology.getAxiomCount();
            if (axiomCount > Limit.REASONER_ELK_ABOVE_AXIOMS.value() && axiomCount <= Limit.REASONER_STRUCTURAL_ABOVE_AXIOMS.value()
                    && !effectiveType.equalsIgnoreCase("ELK") && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                effectiveType = "ELK";
            }
            if (axiomCount > Limit.REASONER_STRUCTURAL_ABOVE_AXIOMS.value() && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                effectiveType = "STRUCTURAL";
            }
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            OWLReasoner reasoner = reasonerService.getClassHierarchyReasoner(ontology, type);

            Set<OWLClass> subClasses = reasonerService.getInferredSubClasses(reasoner, owlClass, direct);

            List<Map<String, Object>> subClassesList = subClasses.stream()
                .map(cls -> {
                    boolean hasChildren = reasoner.getSubClasses(cls, true).getNodes().stream()
                            .anyMatch(n -> !n.isBottomNode() && !n.contains(cls));
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
            @RequestParam(defaultValue = "OPENLLET") String reasonerType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            String resolvedUserId = resolveUserId(projectId, userId, request);
            boolean isDraft = draft && resolvedUserId != null && !resolvedUserId.isBlank();
            String jobId = isDraft ? null : submitHierarchyJob("REASONER_HIERARCHY", projectId, reasonerType);
            if (jobId != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "async", true, "jobId", jobId, "lazy", true
                ));
            }
            long draftVersion = isDraft ? draftGraphVersion(projectId, resolvedUserId) : 0L;
            long draftWriteStamp = isDraft ? datasetService.getDraftWriteStamp(projectId, resolvedUserId) : 0L;
            OWLOntology ontology = isDraft
                    ? datasetService.loadDraftAsOntology(projectId, resolvedUserId, draftVersion)
                    : loadOntology(projectId);
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "OPENLLET" : reasonerType;
            if (effectiveType.equalsIgnoreCase("ELK")) {
                effectiveType = "OPENLLET";
            }

            int axiomCount = ontology.getAxiomCount();
            // Medium ontologies (10k-100k): switch to ELK (fast EL reasoner, 10-100x faster than HermiT)
            if (axiomCount > Limit.REASONER_ELK_ABOVE_AXIOMS.value()
                    && axiomCount <= Limit.REASONER_STRUCTURAL_ABOVE_AXIOMS.value()
                    && !effectiveType.equalsIgnoreCase("ELK")
                    && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                log.info("Medium ontology ({} axioms) — switching to ELK for project {}", axiomCount, projectId);
                effectiveType = "ELK";
            }
            // Very large ontologies (>100k): STRUCTURAL is safest (no reasoning, just asserted)
            if (axiomCount > Limit.REASONER_STRUCTURAL_ABOVE_AXIOMS.value() && !effectiveType.equalsIgnoreCase("STRUCTURAL")) {
                log.warn("Very large ontology ({} axioms) — switching to STRUCTURAL for project {}", axiomCount, projectId);
                effectiveType = "STRUCTURAL";
            }

            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            // Return cached hierarchy immediately if still fresh (Caffeine handles TTL)
            String cacheKey = isDraft
                    ? EditorReasonerCacheService.draftHierarchyKey(projectId, type, resolvedUserId,
                            draftVersion + "." + draftWriteStamp)
                    : projectId + "-" + type.name();
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
                        Map<String, Object> nothingNode = buildClassNode(ontology, reasoner, nothing, visited, INITIAL_HIERARCHY_DEPTH);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> rootChildren = (List<Map<String, Object>>) root.get("children");
                        rootChildren.add(0, nothingNode);
                        root.put("hasChildren", true);
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
                        "success", false, "projectId", projectId,
                        "reasonerType", type.getDisplayName(),
                        "hierarchy", List.of(),
                        "timeout", true,
                        "lazy", true
                ));
            } finally {
                executor.shutdown();
            }
        } catch (WorkerQueueFullException e) {
            return queueFull(projectId, e);
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
            hasAnyChildren = !equivNode.getEntities().isEmpty()
                    && equivNode.getEntities().stream().anyMatch(c -> !c.equals(owlClass));
            if (maxDepth > 0) {
                for (OWLClass equivClass : equivNode.getEntities()) {
                    if (equivClass.equals(owlClass)) continue;
                    children.add(buildClassNode(ontology, reasoner, equivClass, visited, maxDepth - 1, true));
                }
                children.sort(Comparator.comparing(m -> m.get("label").toString()));
            }
        } else {
            NodeSet<OWLClass> subClassesNodeSet = reasoner.getSubClasses(owlClass, true);
            hasAnyChildren = subClassesNodeSet.getNodes().stream()
                    .anyMatch(n -> !n.isBottomNode() && !n.contains(owlClass));

            if (maxDepth > 0) {
                for (Node<OWLClass> subClassNode : subClassesNodeSet) {
                    if (subClassNode.isBottomNode()) continue;
                    OWLClass representative = subClassNode.getRepresentativeElement();
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
            @RequestParam(defaultValue = "OPENLLET") String reasonerType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            String resolvedUserId = resolveUserId(projectId, userId, request);
            boolean isDraft = draft && resolvedUserId != null && !resolvedUserId.isBlank();
            String jobId = isDraft ? null : submitHierarchyJob("REASONER_OBJ_PROP_HIERARCHY", projectId, reasonerType);
            if (jobId != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "async", true, "jobId", jobId
                ));
            }
            OWLOntology ontology = isDraft
                    ? loadDraftOntology(projectId, resolvedUserId)
                    : loadOntology(projectId);
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
        } catch (WorkerQueueFullException e) {
            return queueFull(projectId, e);
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
            @RequestParam(defaultValue = "OPENLLET") String reasonerType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            String resolvedUserId = resolveUserId(projectId, userId, request);
            boolean isDraft = draft && resolvedUserId != null && !resolvedUserId.isBlank();
            String jobId = isDraft ? null : submitHierarchyJob("REASONER_DATA_PROP_HIERARCHY", projectId, reasonerType);
            if (jobId != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true, "projectId", projectId,
                        "async", true, "jobId", jobId
                ));
            }
            OWLOntology ontology = isDraft
                    ? loadDraftOntology(projectId, resolvedUserId)
                    : loadOntology(projectId);
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
        } catch (WorkerQueueFullException e) {
            return queueFull(projectId, e);
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
            @RequestParam(defaultValue = "HERMIT") String reasonerType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            OWLOntology ontology = loadOntologyForRequest(projectId, userId, draft, request);
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
            @RequestParam(defaultValue = "HERMIT") String reasonerType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            OWLOntology ontology = loadOntologyForRequest(projectId, userId, draft, request);
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
            @PathVariable String projectId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            OWLOntology ontology = loadOntologyForRequest(projectId, userId, draft, request);
            
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
            @PathVariable String projectId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            OWLOntology ontology = loadOntologyForRequest(projectId, userId, draft, request);
            
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
            @RequestParam(name = "reasonerType", defaultValue = "OPENLLET") ReasonerType type,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request) {
        try {
            OWLOntology ontology = loadOntologyForRequest(projectId, userId, draft, request);
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
            @RequestParam(defaultValue = "OPENLLET") ReasonerType type,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request) {
        try {
            OWLOntology ontology = loadOntologyForRequest(projectId, userId, draft, request);
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
            @RequestParam(defaultValue = "HERMIT") String reasonerType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        try {
            String resolvedUserId = resolveUserId(projectId, userId, request);
            OWLOntology ontology = (draft && resolvedUserId != null && !resolvedUserId.isBlank())
                    ? loadDraftOntology(projectId, resolvedUserId)
                    : loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

            Map<String, Object> stats = reasonerService.getReasonerStats(ontology, type);
            stats.put("success", !stats.containsKey("error"));
            stats.put("projectId", projectId);

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            return ResponseEntity.ok(friendlyError(e));
        }
    }

    private String resolveUserId(String projectId, String clientSuppliedUserId, HttpServletRequest request) {
        if (clientSuppliedUserId == null || clientSuppliedUserId.isBlank()) {
            return clientSuppliedUserId;
        }
        Optional<String> verified = JwtIdentityExtractor.extractUserId(request);
        if (verified.isPresent() && !verified.get().isBlank()) {
            if (!verified.get().equals(clientSuppliedUserId)) {
                log.warn("Client-supplied userId {} did not match verified identity {} for project {} — using verified identity",
                        clientSuppliedUserId, verified.get(), projectId);
            }
            return verified.get();
        }
        return clientSuppliedUserId;
    }

    private OWLOntology loadOntologyForRequest(String projectId, String userId, boolean draft, HttpServletRequest request) throws Exception {
        String resolvedUserId = resolveUserId(projectId, userId, request);
        if (draft && resolvedUserId != null && !resolvedUserId.isBlank()) {
            return loadDraftOntology(projectId, resolvedUserId);
        }
        return loadOntology(projectId);
    }

    private long publicGraphVersion(String projectId) {
        if (storageManager == null) {
            return -1L;
        }
        try {
            return storageManager.getPublicGraphVersion(projectId);
        } catch (Exception e) {
            return -1L;
        }
    }

    private long draftGraphVersion(String projectId, String userId) {
        return storageManager != null ? storageManager.getDraftGraphVersion(projectId, userId) : 0L;
    }

    private OWLOntology loadDraftOntology(String projectId, String userId) throws Exception {
        return datasetService.loadDraftAsOntology(projectId, userId, draftGraphVersion(projectId, userId));
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
