package self.research.ontology.plugins.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.plugins.service.ReasonerService;
import self.research.ontology.plugins.service.ReasonerType;
import self.research.ontology.plugins.service.ReasonerWorkerClient;

import jakarta.annotation.PreDestroy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Controller for reasoning operations on ontologies.
 * Provides endpoints for consistency checking, classification, realization, and inference.
 */
@RestController("pluginReasonerController")
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

    private final RestTemplate restTemplate = buildRestTemplate();

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    private static RestTemplate buildRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(30_000);
        return new RestTemplate(f);
    }

    // Cache for loaded ontologies — bounded LRU, max 20 entries, thread-safe.
    // Eviction must release the per-project resources tied to the entry
    // (manager reference, warmed reasoners); otherwise every evicted ontology
    // stays pinned in managerRefs and heap grows without bound, and a later
    // reload would overwrite managerRefs while ReasonerService still caches a
    // reasoner wrapping the old ontology (stale results / "Manager on ontology
    // ... is null" once the old manager is collected).
    private final Map<String, OWLOntology> ontologyCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, OWLOntology> eldest) {
                if (size() > 20) {
                    releaseProjectResources(eldest.getKey(), eldest.getValue());
                    return true;
                }
                return false;
            }
        }
    );

    // Keeps a strong reference to each manager so the GC cannot collect it.
    // OWLAPI 5.x stores the manager as a WeakReference inside OWLOntologyImpl;
    // without this map the manager is eligible for GC as soon as the local variable
    // in loadOntologyFromStream() goes out of scope, which causes the
    // "Manager on ontology ... is null" error on the next OWLAPI call.
    private final Map<String, OWLOntologyManager> managerRefs = new ConcurrentHashMap<>();

    // Per-project load locks: prevents two concurrent requests from both missing
    // the ontologyCache, each creating a different OWLOntologyManager, and then
    // overwriting each other's managerRefs entry — which would make the first
    // manager GC-eligible while the ReasonerService cache still holds the first
    // OWLOntology (whose WeakReference to that manager then goes null).
    // Deliberately NOT evicted with the LRU: removing a lock while a load holds
    // it would reintroduce the concurrent-load race this lock exists to prevent,
    // and each entry is a bare Object (negligible memory).
    private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();

    // Async classification task tracking
    private final ConcurrentHashMap<String, Map<String, Object>> classifyTasks = new ConcurrentHashMap<>();
    private final Set<String> cleanupScheduledTaskIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService classifyExecutor = Executors.newFixedThreadPool(2);
    // Delayed task-entry removal runs on its own scheduler: parking a
    // Thread.sleep on classifyExecutor would occupy one of its two worker
    // threads for the full delay and starve real classification jobs.
    private final ScheduledExecutorService taskCleanupScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reasoner-task-cleanup");
            t.setDaemon(true);
            return t;
        });

    @PreDestroy
    void shutdownExecutors() {
        classifyExecutor.shutdownNow();
        taskCleanupScheduler.shutdownNow();
    }

    private void releaseProjectResources(String projectId, OWLOntology ontology) {
        managerRefs.remove(projectId);
        try {
            reasonerService.disposeReasoners(ontology);
        } catch (Exception e) {
            log.warn("Failed disposing reasoners for evicted project {}", projectId, e);
        }
    }

    /**
     * Extract parent project prefix from hierarchical IDs ({@code proj-xxx--fileId}).
     * Canonical project IDs are often the full string ({@code proj-xxx--uuid}); try that
     * first in {@link #loadOntology} and only fall back to this prefix when needed.
     */
    private String extractBaseProjectId(String projectId) {
        if (projectId == null || !projectId.contains("--")) {
            return projectId;
        }
        String baseId = projectId.substring(0, projectId.indexOf("--"));
        log.debug("Extracted base projectId '{}' from partition format '{}'", baseId, projectId);
        return baseId;
    }

    /**
     * Load ontology from multiple sources in priority order:
     * 1. Editor service (for ontologies being edited)
     * 2. GridFS (for uploaded ontologies)
     * 3. Local filesystem (development fallback)
     *
     * Tries the full {@code projectId} first (e.g. {@code proj-abc--uuid}), then the parent
     * prefix before the first {@code --} for legacy partition/file-scoped IDs.
     */
    private OWLOntology loadOntology(String projectId) throws Exception {
        log.info("Loading ontology for project: {}", projectId);

        OWLOntology ontology = tryLoadOntologyForId(projectId);
        if (ontology != null) {
            return ontology;
        }

        if (projectId != null && projectId.contains("--")) {
            String baseProjectId = extractBaseProjectId(projectId);
            if (!baseProjectId.equals(projectId)) {
                log.info("Ontology not found for full id {}; trying parent prefix {}", projectId, baseProjectId);
                ontology = tryLoadOntologyForId(baseProjectId);
                if (ontology != null) {
                    return ontology;
                }
            }
        }

        String triedIds = projectId;
        if (projectId != null && projectId.contains("--")) {
            triedIds = projectId + " and " + extractBaseProjectId(projectId);
        }
        log.error("Ontology file not found for project: {} (tried: editor service, GridFS, filesystem)", triedIds);
        throw new OntologyNotFoundException("Ontology file not found for project: " + triedIds +
            ". Make sure the ontology is either being edited in the IDE or has been uploaded to the system.");
    }

    /** No source (editor service, GridFS, filesystem) yielded an ontology for the project. */
    static class OntologyNotFoundException extends RuntimeException {
        OntologyNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Map an endpoint failure to a response: a missing ontology becomes a 404
     * with a diagnose hint, anything else a 500. Shared by every reasoning
     * endpoint so the mapping cannot drift between them.
     */
    private ResponseEntity<Map<String, Object>> reasoningFailure(Exception e, String projectId, String action) {
        log.error("Error {} for project {}", action, projectId, e);
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        // technicalDetail always carries the real exception class + message + first
        // in-app stack frame, independent of how "friendly" the top-level error text
        // is — so the desktop UI can show a "Details" toggle instead of a dead end.
        String technicalDetail = buildTechnicalDetail(e);

        if (e instanceof OntologyNotFoundException) {
            return ResponseEntity.status(404).body(Map.of(
                "success", false,
                "error", message,
                "errorType", "ONTOLOGY_NOT_FOUND",
                "projectId", projectId,
                "technicalDetail", technicalDetail,
                "suggestion", "Please upload an ontology file for this project. Use /api/reasoner/diagnose/" + projectId + " to investigate."
            ));
        }
        if (e instanceof IllegalArgumentException && message != null && message.contains("No enum constant")) {
            return ResponseEntity.status(400).body(Map.of(
                "success", false,
                "error", "Unknown reasoner type in request: " + message,
                "errorType", "INVALID_REASONER_TYPE",
                "projectId", projectId,
                "technicalDetail", technicalDetail,
                "suggestion", "Select a supported reasoner (HermiT, Openllet/Pellet, ELK, FaCT++, or Structural) and try again."
            ));
        }
        if (e instanceof ClassNotFoundException
                || e.getCause() instanceof NoClassDefFoundError || e.getCause() instanceof UnsatisfiedLinkError) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", "A required reasoner component is missing from this installation: " + message,
                "errorType", "REASONER_DEPENDENCY_MISSING",
                "projectId", projectId,
                "technicalDetail", technicalDetail,
                "suggestion", "This usually means the app was packaged without a required reasoner library. "
                        + "Try reinstalling the latest OntoCode Desktop build, or switch to a different reasoner "
                        + "(e.g. Structural) as a workaround."
            ));
        }
        if (e.getClass().getSimpleName().contains("InconsistentOntology")) {
            return ResponseEntity.status(200).body(Map.of(
                "success", false,
                "error", "The ontology is inconsistent, so " + action + " cannot complete.",
                "errorType", "ONTOLOGY_INCONSISTENT",
                "projectId", projectId,
                "technicalDetail", technicalDetail,
                "suggestion", "Use 'Explain Inconsistency' to find the conflicting axioms, fix them, then retry."
            ));
        }
        return ResponseEntity.status(500).body(Map.of(
            "success", false,
            "error", "Unexpected error while " + action + ": " + message,
            "errorType", "INTERNAL_ERROR",
            "projectId", projectId,
            "technicalDetail", technicalDetail
        ));
    }

    /** Exception class + message + first frame from our own code, for a "Details" panel — not for the headline message. */
    private String buildTechnicalDetail(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }
        for (StackTraceElement frame : e.getStackTrace()) {
            if (frame.getClassName().startsWith("self.research.ontology")) {
                sb.append(" (at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber()).append(')');
                break;
            }
        }
        if (e.getCause() != null && e.getCause() != e) {
            sb.append(" — caused by ").append(e.getCause().getClass().getName());
            if (e.getCause().getMessage() != null) {
                sb.append(": ").append(e.getCause().getMessage());
            }
        }
        return sb.toString();
    }

    private OWLOntology tryLoadOntologyForId(String lookupId) throws Exception {
        if (lookupId == null || lookupId.isBlank()) {
            return null;
        }
        // Fast path: return cached without acquiring a lock.
        OWLOntology cached = ontologyCache.get(lookupId);
        if (cached != null) {
            log.info("Returning cached ontology for project: {}", lookupId);
            return cached;
        }

        // Serialize concurrent loads for the same projectId. Without this, two
        // requests arriving simultaneously (e.g. via Promise.all) both miss the
        // cache, each create a separate OWLOntologyManager, and the second
        // overwrites managerRefs[lookupId] — leaving the first manager with no
        // strong reference so the GC collects it while the ReasonerService cache
        // still holds the first OWLOntology. That OWLOntology's WeakReference to
        // the now-collected manager then goes null, producing:
        // "Manager on ontology ... is null".
        Object lock = loadLocks.computeIfAbsent(lookupId, k -> new Object());
        synchronized (lock) {
            // Double-check inside the lock in case another thread just finished loading.
            cached = ontologyCache.get(lookupId);
            if (cached != null) {
                log.info("Returning cached ontology for project: {} (loaded by concurrent thread)", lookupId);
                return cached;
            }

            OWLOntology editorOntology = loadOntologyFromEditorService(lookupId);
            if (editorOntology != null) {
                ontologyCache.put(lookupId, editorOntology);
                return editorOntology;
            }

            GridFSFile file = gridfs.findOne(new Query(Criteria.where("metadata.projectId").is(lookupId)));
            if (file == null) {
                log.warn("File not found with metadata.projectId={}, trying filename", lookupId);
                file = gridfs.findOne(new Query(Criteria.where("filename").is(lookupId + ".owl")));
            }

            if (file != null) {
                log.info("Found ontology file in GridFS: {}", file.getFilename());
                GridFsResource resource = gridfs.getResource(file);
                try (InputStream inputStream = resource.getInputStream()) {
                    OWLOntology ontology = loadOntologyFromStream(lookupId, inputStream, "GridFS file " + file.getFilename());
                    if (ontology != null) {
                        ontologyCache.put(lookupId, ontology);
                        return ontology;
                    }
                }
            }

            OWLOntology filesystemOntology = loadOntologyFromFilesystem(lookupId);
            if (filesystemOntology != null) {
                ontologyCache.put(lookupId, filesystemOntology);
                return filesystemOntology;
            }

            return null;
        }
    }

    private String editorOntologyFileUrl(String projectId) {
        String encoded = java.net.URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        String base = editorServiceUrl.endsWith("/") ? editorServiceUrl.substring(0, editorServiceUrl.length() - 1) : editorServiceUrl;
        return base + "/api/ontology-file/" + encoded;
    }

    /**
     * Forward the caller's Bearer token to editor-service requests. The editor
     * enforces JWT on /api/** in cloud (require-jwt=true), so an unauthenticated
     * fetch 401s and silently hides ontologies that exist only in the editor.
     * Outside a request context (or without a bearer token) the entity carries
     * no Authorization header, matching desktop/dev where the editor exempts
     * localhost callers.
     */
    private org.springframework.http.HttpEntity<Void> editorAuthEntity() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        org.springframework.web.context.request.RequestAttributes attrs =
            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
            String auth = servletAttrs.getRequest().getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                headers.set("Authorization", auth);
            }
        }
        return new org.springframework.http.HttpEntity<>(headers);
    }

    /**
     * Fetch ontology from the editor service API
     * Uses configured editor URL: ${ontology.editor.url}
     */
    private OWLOntology loadOntologyFromEditorService(String projectId) {
        log.debug("Attempting to fetch from editor service at: {}", editorServiceUrl);
        try {
            String url = editorOntologyFileUrl(projectId);
            log.info("Fetching ontology from editor service: {}", url);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                url, org.springframework.http.HttpMethod.GET, editorAuthEntity(), byte[].class);
            
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
            managerRefs.put(projectId, manager);
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
            if (workerAvailable()) {
                return submitToWorker("REASONER_CONSISTENCY", projectId, reasonerType);
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
            return reasoningFailure(e, projectId, "checking consistency");
        }
    }

    /**
     * Classify the ontology (compute class hierarchy) — async version.
     * Returns immediately with a taskId; poll GET /api/reasoner/{projectId}/classify/status/{taskId} for results.
     * POST /api/reasoner/{projectId}/classify
     */
    @PostMapping("/{projectId}/classify")
    public ResponseEntity<Map<String, Object>> classify(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String reasonerType = request.getOrDefault("reasonerType", "HERMIT");
            if (workerAvailable()) {
                return submitToWorker("REASONER_CLASSIFY", projectId, reasonerType);
            }

            log.info("Classifying ontology for project: {} with {}", projectId, reasonerType);

            // Pre-validate reasoner type
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

            // Pre-load ontology on the request thread so errors surface immediately
            OWLOntology ontology = loadOntology(projectId);

            String taskId = UUID.randomUUID().toString();

            Map<String, Object> taskInfo = new ConcurrentHashMap<>();
            taskInfo.put("status", "RUNNING");
            taskInfo.put("startedAt", System.currentTimeMillis());
            taskInfo.put("reasonerType", type.getDisplayName());
            classifyTasks.put(taskId, taskInfo);

            classifyExecutor.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    reasonerService.classify(ontology, type);
                    long duration = System.currentTimeMillis() - startTime;

                    Map<String, Object> classificationData = reasonerService.getClassificationResults(ontology, type);

                    taskInfo.put("status", "COMPLETED");
                    taskInfo.put("success", true);
                    taskInfo.put("durationMs", duration);
                    taskInfo.put("message", "Classification completed successfully");
                    taskInfo.put("classHierarchy", classificationData.get("classHierarchy"));
                    taskInfo.put("objectPropertyHierarchy", classificationData.get("objectPropertyHierarchy"));
                    taskInfo.put("dataPropertyHierarchy", classificationData.get("dataPropertyHierarchy"));
                    taskInfo.put("equivalentClasses", classificationData.get("equivalentClasses"));
                    taskInfo.put("unsatisfiableClasses", classificationData.get("unsatisfiableClasses"));
                    taskInfo.put("totalClasses", classificationData.get("totalClasses"));
                } catch (Exception e) {
                    log.error("Async classification failed for project: {}", projectId, e);
                    taskInfo.put("status", "FAILED");
                    taskInfo.put("success", false);
                    taskInfo.put("error", e.getMessage());
                }
            });

            Map<String, Object> accepted = new HashMap<>();
            accepted.put("taskId", taskId);
            accepted.put("status", "RUNNING");
            accepted.put("pollUrl", "/api/reasoner/" + projectId + "/classify/status/" + taskId);
            return ResponseEntity.accepted().body(accepted);

        } catch (Exception e) {
            return reasoningFailure(e, projectId, "starting classification");
        }
    }

    /**
     * Poll for async classification results.
     * GET /api/reasoner/{projectId}/classify/status/{taskId}
     */
    @GetMapping("/{projectId}/classify/status/{taskId}")
    public ResponseEntity<Map<String, Object>> classifyStatus(
            @PathVariable String projectId,
            @PathVariable String taskId
    ) {
        Map<String, Object> taskInfo = classifyTasks.get(taskId);
        if (taskInfo == null) {
            if (reasonerWorkerEnabled && reasonerWorkerClient != null) {
                Map<String, Object> remote = reasonerWorkerClient.getJob(taskId);
                if (remote != null && remote.get("jobId") != null) {
                    return mapWorkerJobToClassifyStatus(taskId, remote);
                }
            }
            return ResponseEntity.status(404).body(Map.of(
                "success", false,
                "error", "Task not found: " + taskId
            ));
        }

        String status = (String) taskInfo.get("status");
        Map<String, Object> response = new HashMap<>(taskInfo);
        response.put("taskId", taskId);

        if (("COMPLETED".equals(status) || "FAILED".equals(status))
                && cleanupScheduledTaskIds.add(taskId)) {
            // Cleanup after first retrieval — keep for 60s in case of retry.
            // The set guards against re-scheduling on every poll.
            taskCleanupScheduler.schedule(() -> {
                classifyTasks.remove(taskId);
                cleanupScheduledTaskIds.remove(taskId);
            }, 60, TimeUnit.SECONDS);
        }

        return ResponseEntity.ok(response);
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
            if (workerAvailable()) {
                return submitToWorker("REASONER_REALIZE", projectId, reasonerType);
            }

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
            return reasoningFailure(e, projectId, "during realization");
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
            return reasoningFailure(e, projectId, "explaining inconsistency");
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
            if (workerAvailable()) {
                return submitToWorker("REASONER_RUN", projectId, reasonerType);
            }

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
            return reasoningFailure(e, projectId, "getting inferred axioms");
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
            return reasoningFailure(e, projectId, "getting reasoner stats");
        }
    }

    /**
     * Stop reasoning for a project — dispose warmed reasoner sessions.
     * POST /api/reasoner/{projectId}/stop
     */
    @PostMapping("/{projectId}/stop")
    public ResponseEntity<Map<String, Object>> stopReasoner(
            @PathVariable String projectId,
            @RequestParam(required = false) String reasonerType
    ) {
        try {
            if (reasonerType != null && !reasonerType.isBlank()) {
                reasonerService.disposeReasoner(projectId, ReasonerType.valueOf(reasonerType.toUpperCase()));
            } else {
                for (ReasonerType type : ReasonerType.values()) {
                    reasonerService.disposeReasoner(projectId, type);
                }
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Reasoner stopped",
                    "projectId", projectId
            ));
        } catch (Exception e) {
            log.error("Error stopping reasoner for {}", projectId, e);
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
            managerRefs.clear();
            
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
            diagnosis.put("editorServiceUrl", editorServiceUrl);

            // Check editor service for full ID and parent prefix (if applicable)
            List<String> idsToTry = new ArrayList<>();
            idsToTry.add(projectId);
            if (projectId != null && projectId.contains("--")) {
                String baseId = extractBaseProjectId(projectId);
                if (!baseId.equals(projectId)) {
                    idsToTry.add(baseId);
                }
            }
            Map<String, Object> editorChecks = new LinkedHashMap<>();
            for (String id : idsToTry) {
                Map<String, Object> check = new HashMap<>();
                try {
                    String url = editorOntologyFileUrl(id);
                    check.put("url", url);
                    ResponseEntity<byte[]> response = restTemplate.exchange(
                        url, org.springframework.http.HttpMethod.GET, editorAuthEntity(), byte[].class);
                    check.put("httpStatus", response.getStatusCode().value());
                    byte[] body = response.getBody();
                    check.put("bytes", body != null ? body.length : 0);
                    check.put("available", response.getStatusCode() == HttpStatus.OK && body != null && body.length > 0);
                } catch (Exception e) {
                    check.put("available", false);
                    check.put("error", e.getMessage());
                }
                editorChecks.put(id, check);
            }
            diagnosis.put("editorService", editorChecks);
            
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

    private boolean workerAvailable() {
        return reasonerWorkerEnabled && reasonerWorkerClient != null;
    }

    /**
     * Submit a reasoning job to the isolated reasoner worker and return the
     * async-accepted contract shared by all worker-backed endpoints.
     */
    private ResponseEntity<Map<String, Object>> submitToWorker(String jobType, String projectId, String reasonerType) {
        Map<String, Object> worker = reasonerWorkerClient.submit(jobType, projectId, reasonerType);
        if (Boolean.FALSE.equals(worker.get("success"))) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", ReasoningFriendlyErrors.forUser(String.valueOf(worker.get("error")))));
        }
        String jobId = String.valueOf(worker.get("jobId"));
        return ResponseEntity.accepted().body(Map.of(
                "async", true,
                "taskId", jobId,
                "jobId", jobId,
                "status", worker.getOrDefault("status", "QUEUED"),
                "pollUrl", "/api/dl-query/jobs/" + jobId));
    }

    private ResponseEntity<Map<String, Object>> mapWorkerJobToClassifyStatus(String taskId, Map<String, Object> remote) {
        Map<String, Object> response = new HashMap<>(remote);
        response.put("taskId", taskId);
        String status = String.valueOf(remote.getOrDefault("status", "QUEUED"));
        if ("COMPLETED".equals(status)) {
            response.put("status", "COMPLETED");
            response.put("success", true);
            if (!response.containsKey("message")) {
                response.put("message", "Classification completed successfully");
            }
        } else if ("FAILED".equals(status)) {
            response.put("status", "FAILED");
            response.put("success", false);
            response.put("error", ReasoningFriendlyErrors.forUser(String.valueOf(remote.get("error"))));
        } else {
            response.put("status", "RUNNING");
        }
        return ResponseEntity.ok(response);
    }

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
