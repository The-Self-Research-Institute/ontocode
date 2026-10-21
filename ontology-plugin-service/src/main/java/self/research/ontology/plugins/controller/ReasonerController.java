package self.research.ontology.plugins.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
import java.util.concurrent.Future;
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

    @Value("${ontocode.internal.token:ontocode-internal}")
    private String internalToken;

    private final RestTemplate restTemplate = buildRestTemplate();

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    @Value("${ontocode.reasoner.operation-timeout-ms:45000}")
    private long statsSnapshotWaitMs = 45_000L;

    private long statsSnapshotPollMs = 500L;

    @Value("${ontocode.reasoner.justification-timeout-ms:55000}")
    private long explainEntailmentWaitMs = 55_000L;

    private static final java.util.Set<String> WORKER_JOB_KEYS = java.util.Set.of(
            "async", "jobId", "jobType", "projectId", "status", "queuePosition",
            "estimatedWaitTimeMs", "executionTimeMs", "error", "rateLimited", "success");

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

    @Value("${ontocode.reasoner.draft-cache-max:10}")
    private int maxDraftOntologies;

    private final Map<String, OWLOntology> lastDraftOntology = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, OWLOntology> eldest) {
                if (size() > maxDraftOntologies) {
                    OWLOntology evicted = eldest.getValue();
                    synchronized (draftDisposalLock) {
                        java.util.concurrent.atomic.AtomicInteger count = draftOntologyUseCount.get(evicted);
                        if (count == null || count.get() <= 0) {
                            draftOntologyUseCount.remove(evicted);
                            disposeDraftOntology(evicted);
                        } else {
                            draftOntologyPendingDisposal.add(evicted);
                        }
                    }
                    return true;
                }
                return false;
            }
        }
    );

    private final Map<OWLOntology, java.util.concurrent.atomic.AtomicInteger> draftOntologyUseCount =
        new java.util.IdentityHashMap<>();
    private final Set<OWLOntology> draftOntologyPendingDisposal =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Object draftDisposalLock = new Object();

    // Identity-keyed, unlike managerRefs (keyed by the draft's string cache key, which gets
    // overwritten on every reload — including while the previous ontology object is still in
    // use by a concurrent request, which would let its manager be GC'd mid-use and reproduce
    // the "Manager on ontology ... is null" bug). Registered when a draft ontology starts being
    // tracked, removed only once it's actually disposed (immediately or deferred via endDraftUse).
    private final Map<OWLOntology, OWLOntologyManager> draftManagerRefs = new java.util.IdentityHashMap<>();

    private final Map<OWLOntology, String> draftVersionOf = new java.util.IdentityHashMap<>();

    private void beginDraftUse(OWLOntology ontology) {
        synchronized (draftDisposalLock) {
            draftOntologyUseCount.computeIfAbsent(ontology, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            draftManagerRefs.putIfAbsent(ontology, ontology.getOWLOntologyManager());
        }
    }

    private void endDraftUse(OWLOntology ontology) {
        if (ontology == null) {
            return;
        }
        synchronized (draftDisposalLock) {
            java.util.concurrent.atomic.AtomicInteger count = draftOntologyUseCount.get(ontology);
            if (count == null) {
                return;
            }
            if (count.decrementAndGet() <= 0) {
                draftOntologyUseCount.remove(ontology);
                if (draftOntologyPendingDisposal.remove(ontology)) {
                    disposeDraftOntology(ontology);
                }
            }
        }
    }

    private void disposeDraftOntology(OWLOntology ontology) {
        draftVersionOf.remove(ontology);
        reasonerService.disposeReasoners(ontology);
        OWLOntologyManager manager = draftManagerRefs.remove(ontology);
        if (manager != null) {
            managerRefs.values().remove(manager);
        }
    }

    // Async classification task tracking
    private final ConcurrentHashMap<String, Map<String, Object>> classifyTasks = new ConcurrentHashMap<>();
    private final Set<String> cleanupScheduledTaskIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService classifyExecutor = Executors.newFixedThreadPool(2);

    private record ClassifyRun(Future<?> future, OWLReasoner reasoner) {}

    // Holds the live reasoner + Future for each in-flight classify task, so a user-initiated
    // stop (or the timeout below) can call reasoner.interrupt() — the real OWLAPI cancellation
    // hook — instead of just abandoning the thread to run to completion unseen.
    private final ConcurrentHashMap<String, ClassifyRun> classifyRuns = new ConcurrentHashMap<>();

    @Value("${ontocode.reasoner.classify-timeout-ms:600000}")
    private long classifyTimeoutMs;
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

 
    private String resolveUserId(String clientSuppliedUserId) {
        if (clientSuppliedUserId == null || clientSuppliedUserId.isBlank()) {
            return clientSuppliedUserId;
        }
        org.springframework.web.context.request.RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            Object verified = servletAttrs.getRequest().getAttribute(
                self.research.ontology.plugins.config.JwtAuthenticationFilter.AUTHENTICATED_USER_ID_ATTRIBUTE);
            if (verified instanceof String verifiedUserId && !verifiedUserId.isBlank()) {
                if (!verifiedUserId.equals(clientSuppliedUserId)) {
                    log.warn("Ignoring client-supplied userId '{}' for draft load — using authenticated userId '{}' instead",
                        clientSuppliedUserId, verifiedUserId);
                }
                return verifiedUserId;
            }
        }
        return clientSuppliedUserId;
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
    private final ThreadLocal<Throwable> lastLoadFailure = new ThreadLocal<>();

    private OWLOntology loadOntology(String projectId, String clientSuppliedUserId) throws Exception {
        lastLoadFailure.remove();
        try {
            String userId = resolveUserId(clientSuppliedUserId);
            log.info("Loading ontology for project: {} (userId={})", projectId, userId);

            OWLOntology ontology = tryLoadOntologyForId(projectId, userId);
            if (ontology != null) {
                return ontology;
            }

            if (projectId != null && projectId.contains("--")) {
                String baseProjectId = extractBaseProjectId(projectId);
                if (!baseProjectId.equals(projectId)) {
                    log.info("Ontology not found for full id {}; trying parent prefix {}", projectId, baseProjectId);
                    ontology = tryLoadOntologyForId(baseProjectId, userId);
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
                ". Make sure the ontology is either being edited in the IDE or has been uploaded to the system.",
                lastLoadFailure.get());
        } finally {
            lastLoadFailure.remove();
        }
    }

    /** No source (editor service, GridFS, filesystem) yielded an ontology for the project. */
    static class OntologyNotFoundException extends RuntimeException {
        OntologyNotFoundException(String message) {
            super(message);
        }

        OntologyNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class OntologyImportInProgressException extends RuntimeException {
        OntologyImportInProgressException(String message) {
            super(message);
        }
    }

    /**
     * Map an endpoint failure to a response: a missing ontology becomes a 404
     * with a diagnose hint, anything else a 500. Shared by every reasoning
     * endpoint so the mapping cannot drift between them.
     */
    private ResponseEntity<Map<String, Object>> reasoningFailure(Throwable e, String projectId, String action) {
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
        if (e instanceof OntologyImportInProgressException) {
            return ResponseEntity.status(202).body(Map.of(
                "success", false,
                "error", message,
                "errorType", "ONTOLOGY_IMPORT_IN_PROGRESS",
                "projectId", projectId,
                "suggestion", "The ontology is still being imported. Wait a few seconds and retry."
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

    /** Draft and published loads must never share a cache slot — see {@link #loadOntology(String, String)}. */
    private String cacheKeyFor(String lookupId, String userId) {
        return (userId == null || userId.isBlank()) ? lookupId : lookupId + "::draft::" + userId;
    }

    private final ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<OWLOntology>> draftLoadsInFlight =
        new ConcurrentHashMap<>();

    private OWLOntology tryLoadOntologyForId(String lookupId, String userId) throws Exception {
        if (lookupId == null || lookupId.isBlank()) {
            return null;
        }
        if (userId != null && !userId.isBlank()) {
            return loadDraftOntology(lookupId, userId);
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

            OWLOntology editorOntology = loadOntologyFromEditorService(lookupId, null);
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

    private OWLOntology loadDraftOntology(String lookupId, String userId) throws Exception {
        String draftKey = cacheKeyFor(lookupId, userId);
        String version = currentDraftVersion(lookupId, userId);
        if (version != null) {
            synchronized (draftDisposalLock) {
                OWLOntology cached = lastDraftOntology.get(draftKey);
                if (cached != null && version.equals(draftVersionOf.get(cached))) {
                    log.info("Reusing draft ontology for {} (draft version {})", draftKey, version);
                    beginDraftUse(cached);
                    return cached;
                }
            }
        }
        String flightKey = draftKey + "@" + (version != null ? version : "unknown");
        while (true) {
            java.util.concurrent.CompletableFuture<OWLOntology> mine = new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.CompletableFuture<OWLOntology> inFlight = draftLoadsInFlight.putIfAbsent(flightKey, mine);
            if (inFlight == null) {
                try {
                    OWLOntology fresh = fetchDraftOntology(lookupId, userId, draftKey, version);
                    mine.complete(fresh);
                    return fresh;
                } catch (Throwable t) {
                    mine.completeExceptionally(t);
                    throw t;
                } finally {
                    draftLoadsInFlight.remove(flightKey, mine);
                }
            }
            OWLOntology shared;
            try {
                shared = inFlight.join();
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
            }
            if (shared == null) {
                return null;
            }
            synchronized (draftDisposalLock) {
                if (lastDraftOntology.get(draftKey) == shared || draftOntologyUseCount.containsKey(shared)) {
                    beginDraftUse(shared);
                    return shared;
                }
            }
        }
    }

    private OWLOntology fetchDraftOntology(String lookupId, String userId, String draftKey, String version) {
        OWLOntology fresh = loadOntologyFromEditorService(lookupId, userId);
        if (fresh == null) {
            return null;
        }
        synchronized (draftDisposalLock) {
            beginDraftUse(fresh);
            if (version != null) {
                draftVersionOf.put(fresh, version);
            }
            OWLOntology previous = lastDraftOntology.put(draftKey, fresh);
            if (previous != null && previous != fresh) {
                java.util.concurrent.atomic.AtomicInteger previousCount = draftOntologyUseCount.get(previous);
                if (previousCount == null || previousCount.get() <= 0) {
                    draftOntologyUseCount.remove(previous);
                    disposeDraftOntology(previous);
                } else {
                    draftOntologyPendingDisposal.add(previous);
                }
            }
        }
        return fresh;
    }

    private String currentDraftVersion(String projectId, String userId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    editorDraftVersionUrl(projectId, userId), org.springframework.http.HttpMethod.GET,
                    editorInternalAuthEntity(), Map.class);
            if (response == null || response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return null;
            }
            Object version = response.getBody().get("version");
            return version instanceof String text && !text.isBlank() ? text : null;
        } catch (Exception e) {
            log.debug("Could not read the draft version for {} (user {}): {}", projectId, userId, e.getMessage());
            return null;
        }
    }

    private String editorDraftVersionUrl(String projectId, String userId) {
        String encodedProject = java.net.URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedUser = java.net.URLEncoder.encode(userId, StandardCharsets.UTF_8).replace("+", "%20");
        String base = editorServiceUrl.endsWith("/") ? editorServiceUrl.substring(0, editorServiceUrl.length() - 1) : editorServiceUrl;
        return base + "/internal/reasoning/" + encodedProject + "/draft-version?userId=" + encodedUser;
    }

    private String editorOntologyFileUrl(String projectId) {
        String encoded = java.net.URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        String base = editorServiceUrl.endsWith("/") ? editorServiceUrl.substring(0, editorServiceUrl.length() - 1) : editorServiceUrl;
        return base + "/api/ontology-file/" + encoded + "?forceExport=true";
    }

    private String editorDraftExportUrl(String projectId, String userId) {
        String encodedProject = java.net.URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedUser = java.net.URLEncoder.encode(userId, StandardCharsets.UTF_8).replace("+", "%20");
        String base = editorServiceUrl.endsWith("/") ? editorServiceUrl.substring(0, editorServiceUrl.length() - 1) : editorServiceUrl;
        return base + "/internal/reasoning/" + encodedProject + "/export.ttl?userId=" + encodedUser + "&draft=true";
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
    private OWLOntology loadOntologyFromEditorService(String projectId, String userId) {
        log.debug("Attempting to fetch from editor service at: {}", editorServiceUrl);
        boolean draft = userId != null && !userId.isBlank();
        try {
            String url = draft ? editorDraftExportUrl(projectId, userId) : editorOntologyFileUrl(projectId);
            log.info("Fetching ontology from editor service: {}", url);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                url, org.springframework.http.HttpMethod.GET,
                draft ? editorInternalAuthEntity() : editorAuthEntity(), byte[].class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                try (InputStream inputStream = new java.io.ByteArrayInputStream(response.getBody())) {
                    OWLOntology ontology = loadOntologyFromStream(cacheKeyFor(projectId, userId), inputStream, "editor service");
                    if (ontology != null) {
                        log.info("Successfully loaded ontology from editor service");
                        return ontology;
                    }
                }
            } else if (response.getStatusCode() == HttpStatus.ACCEPTED) {
                log.info("Editor service reports import still in progress for project {}; not falling back", projectId);
                throw new OntologyImportInProgressException(
                    "Ontology import is still in progress for project " + projectId + "; retry once it completes.");
            } else {
                log.warn("Editor service returned status {} for project {}", response.getStatusCode(), projectId);
            }
        } catch (OntologyImportInProgressException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not fetch ontology from editor service for project {}: {}", projectId, e.getMessage());
            lastLoadFailure.set(e);
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
            lastLoadFailure.set(e);
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
            String whatIfKey = request.get("whatIfKey");
            if (whatIfKey != null && !whatIfKey.isBlank()) {
                return checkWhatIfConsistency(projectId, whatIfKey, reasonerType, request.get("ontologyContent"));
            }
            String userId = request.get("userId");
            if (workerAvailable() && (userId == null || userId.isBlank())) {
                return submitToWorker("REASONER_CONSISTENCY", projectId, reasonerType);
            }

            log.info("Checking consistency for project: {} with {}", projectId, reasonerType);

            OWLOntology ontology = loadOntology(projectId, userId);
            try {
                ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

                long startTime = System.currentTimeMillis();
                boolean isConsistent = reasonerService.isConsistent(ontology, type);
                long duration = System.currentTimeMillis() - startTime;

                Map<String, Object> result = new HashMap<>();
                result.put("consistent", isConsistent);
                result.put("reasonerType", type.getDisplayName());
                result.put("durationMs", duration);
                result.put("projectId", projectId);

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
            } finally {
                endDraftUse(ontology);
            }

        } catch (Exception e) {
            return reasoningFailure(e, projectId, "checking consistency");
        }
    }

    private ResponseEntity<Map<String, Object>> checkWhatIfConsistency(String projectId, String whatIfKey,
                                                                        String reasonerType, String ontologyContent) {
        OWLOntology ontology = null;
        try {
            log.info("Checking what-if consistency for project {} key {} with {}", projectId, whatIfKey, reasonerType);
            ontology = ontologyContent != null && !ontologyContent.isBlank()
                    ? loadOntologyFromContent(ontologyContent)
                    : loadWhatIfOntology(projectId, whatIfKey);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

            long startTime = System.currentTimeMillis();
            boolean isConsistent = reasonerService.isConsistent(ontology, type);
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new HashMap<>();
            result.put("consistent", isConsistent);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("projectId", projectId);

            if (!isConsistent) {
                OWLOntology loaded = ontology;
                Set<OWLClass> unsatisfiable = reasonerService.getUnsatisfiableClasses(ontology, type);
                List<Map<String, String>> unsatisfiableList = unsatisfiable.stream()
                        .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", getLabel(cls, loaded)))
                        .collect(Collectors.toList());
                result.put("unsatisfiableClasses", unsatisfiableList);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return reasoningFailure(e, projectId, "checking what-if consistency");
        } finally {
            if (ontology != null) {
                try {
                    reasonerService.disposeReasoners(ontology);
                } catch (Exception disposeEx) {
                    log.warn("Failed to dispose what-if reasoner for project {} key {}: {}",
                            projectId, whatIfKey, disposeEx.getMessage());
                }
            }
        }
    }

    private OWLOntology loadOntologyFromContent(String content) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream inputStream = new java.io.ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8))) {
            return manager.loadOntologyFromOntologyDocument(inputStream);
        }
    }

    private OWLOntology loadWhatIfOntology(String projectId, String whatIfKey) throws Exception {
        String url = editorWhatIfExportUrl(projectId, whatIfKey);
        log.info("Fetching what-if ontology from editor service: {}", url);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, org.springframework.http.HttpMethod.GET, editorInternalAuthEntity(), byte[].class);
        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new OntologyNotFoundException("Editor service returned " + response.getStatusCode()
                    + " for what-if export of project " + projectId);
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream inputStream = new java.io.ByteArrayInputStream(response.getBody())) {
            return manager.loadOntologyFromOntologyDocument(inputStream);
        }
    }

    private String editorWhatIfExportUrl(String projectId, String whatIfKey) {
        String encodedProject = java.net.URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedKey = java.net.URLEncoder.encode(whatIfKey, StandardCharsets.UTF_8).replace("+", "%20");
        String base = editorServiceUrl.endsWith("/") ? editorServiceUrl.substring(0, editorServiceUrl.length() - 1) : editorServiceUrl;
        return base + "/internal/reasoning/" + encodedProject + "/whatif/" + encodedKey + "/export.nt";
    }

    private org.springframework.http.HttpEntity<Void> editorInternalAuthEntity() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Ontocode-Internal-Token", internalToken);
        org.springframework.web.context.request.RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            String auth = servletAttrs.getRequest().getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                headers.set("Authorization", auth);
            }
        }
        return new org.springframework.http.HttpEntity<>(headers);
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
            String userId = request.get("userId");
            if (workerAvailable() && (userId == null || userId.isBlank())) {
                return submitToWorker("REASONER_CLASSIFY", projectId, reasonerType);
            }

            log.info("Classifying ontology for project: {} with {}", projectId, reasonerType);

            // Pre-validate reasoner type
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

            // Pre-load ontology on the request thread so errors surface immediately
            OWLOntology ontology = loadOntology(projectId, userId);

            // Obtain the reasoner here (not inside the async task) so this thread can hold
            // onto the same instance and call reasoner.interrupt() on it later — either from
            // a user-initiated stop or the timeout below — to actually cancel work in progress
            // instead of just abandoning an orphaned thread to run to completion unseen.
            OWLReasoner reasoner = reasonerService.getReasoner(ontology, type);

            String taskId = UUID.randomUUID().toString();

            Map<String, Object> taskInfo = new ConcurrentHashMap<>();
            taskInfo.put("status", "RUNNING");
            taskInfo.put("startedAt", System.currentTimeMillis());
            taskInfo.put("reasonerType", type.getDisplayName());
            classifyTasks.put(taskId, taskInfo);

            Future<?> future = classifyExecutor.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    reasonerService.classify(reasoner, ontology, type);
                    long duration = System.currentTimeMillis() - startTime;

                    Map<String, Object> classificationData = reasonerService.getClassificationResults(ontology, type);

                    if (taskInfo.replace("status", "RUNNING", "COMPLETED")) {
                        taskInfo.put("success", true);
                        taskInfo.put("durationMs", duration);
                        taskInfo.put("message", "Classification completed successfully");
                        for (String key : List.of("classHierarchy", "objectPropertyHierarchy", "dataPropertyHierarchy",
                                "equivalentClasses", "unsatisfiableClasses", "totalClasses", "stats")) {
                            Object value = classificationData.get(key);
                            if (value != null) {
                                taskInfo.put(key, value);
                            }
                        }
                    }
                } catch (Throwable e) {
                    log.error("Async classification failed for project: {}", projectId, e);
                    if (taskInfo.replace("status", "RUNNING", "FAILED")) {
                        taskInfo.put("success", false);
                        taskInfo.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    }
                } finally {
                    classifyRuns.remove(taskId);
                    endDraftUse(ontology);
                }
            });

            classifyRuns.put(taskId, new ClassifyRun(future, reasoner));
            taskCleanupScheduler.schedule(() -> {
                ClassifyRun run = classifyRuns.remove(taskId);
                if (run != null && !run.future().isDone() && taskInfo.replace("status", "RUNNING", "FAILED")) {
                    log.warn("Classification for project {} exceeded {}ms, interrupting", projectId, classifyTimeoutMs);
                    run.reasoner().interrupt();
                    run.future().cancel(true);
                    taskInfo.put("success", false);
                    taskInfo.put("error", "Classification timed out after " + (classifyTimeoutMs / 1000)
                            + "s — the ontology may be too large or complex for this reasoner. Try a different reasoner or stop and retry.");
                }
            }, classifyTimeoutMs, TimeUnit.MILLISECONDS);

            Map<String, Object> accepted = new HashMap<>();
            accepted.put("taskId", taskId);
            accepted.put("status", "RUNNING");
            accepted.put("async", true);
            accepted.put("pollUrl", "/api/reasoner/" + projectId + "/classify/status/" + taskId);
            accepted.put("cancelUrl", "/api/reasoner/" + projectId + "/classify/" + taskId);
            return ResponseEntity.accepted().body(accepted);

        } catch (Exception e) {
            return reasoningFailure(e, projectId, "starting classification");
        }
    }

    /**
     * Cancel an in-progress classification — interrupts the actual reasoner computation
     * (not just the HTTP/polling side), so a stuck run can genuinely be stopped.
     * DELETE /api/reasoner/{projectId}/classify/{taskId}
     */
    @DeleteMapping("/{projectId}/classify/{taskId}")
    public ResponseEntity<Map<String, Object>> cancelClassify(
            @PathVariable String projectId,
            @PathVariable String taskId
    ) {
        ClassifyRun run = classifyRuns.remove(taskId);
        Map<String, Object> taskInfo = classifyTasks.get(taskId);
        if (run == null) {
            boolean alreadyTerminal = taskInfo != null
                    && ("COMPLETED".equals(taskInfo.get("status")) || "FAILED".equals(taskInfo.get("status")));
            return ResponseEntity.ok(Map.of("success", true, "cancelled", false,
                    "message", alreadyTerminal ? "Classification already finished" : "No running classification found for this task"));
        }
        run.reasoner().interrupt();
        run.future().cancel(true);
        if (taskInfo != null) {
            taskInfo.replace("status", "RUNNING", "FAILED");
            taskInfo.putIfAbsent("success", false);
            taskInfo.putIfAbsent("error", "Stopped by user");
        }
        log.info("Classification for project {} (task {}) cancelled by user", projectId, taskId);
        return ResponseEntity.ok(Map.of("success", true, "cancelled", true));
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
            String userId = request.get("userId");
            if (workerAvailable() && (userId == null || userId.isBlank())) {
                return submitToWorker("REASONER_REALIZE", projectId, reasonerType);
            }

            log.info("Realizing ontology for project: {} with {}", projectId, reasonerType);

            OWLOntology ontology = loadOntology(projectId, userId);
            try {
                ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

                long startTime = System.currentTimeMillis();
                reasonerService.realize(ontology, type);
                long duration = System.currentTimeMillis() - startTime;

                Map<String, Object> realizationData = reasonerService.getRealizationResults(ontology, type);

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("reasonerType", type.getDisplayName());
                result.put("durationMs", duration);
                result.put("message", "Realization completed successfully");
                result.put("instances", realizationData.get("instances"));
                result.put("totalInstances", realizationData.get("totalInstances"));

                return ResponseEntity.ok(result);
            } finally {
                endDraftUse(ontology);
            }

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
        String requestedReasonerType = request.getOrDefault("reasonerType", "HERMIT");

        int maxJustifications = 10;
        if (request.containsKey("maxJustifications")) {
            maxJustifications = Integer.parseInt(request.get("maxJustifications"));
        }

        String mode = request.getOrDefault("mode", "regular");
        String userId = request.get("userId");

        // Same worker-offload pattern as consistency/classify/realize/inferred-axioms —
        // this endpoint used to be the one gap that always fell through to this
        // controller's own long-lived ontologyCache, so it could keep answering from a
        // stale pre-edit (or, right after upload, pre-import) snapshot even once the
        if (workerAvailable() && (userId == null || userId.isBlank())) {
            return submitExplainInconsistencyToWorker(projectId, requestedReasonerType, mode, maxJustifications);
        }

        ReasonerType requestedType = ReasonerType.valueOf(requestedReasonerType.toUpperCase());
        ReasonerType type = (requestedType == ReasonerType.ELK || requestedType == ReasonerType.STRUCTURAL)
            ? ReasonerType.HERMIT
            : requestedType;

        if (type != requestedType) {
            log.info("Explain-inconsistency: upgrading reasoner {} -> {} for project {}",
                requestedType, type, projectId);
        }

        log.info("Explaining inconsistency for project: {} with {} (mode={})", projectId, type, mode);

        OWLOntology ontology = loadOntology(projectId, userId);
        try {
            Map<String, Object> explanation = reasonerService.explainInconsistency(ontology, type, maxJustifications, mode);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("usedReasoner", type.getDisplayName());
            if (type != requestedType) {
                result.put("reasonerUpgraded", true);
            }
            result.putAll(explanation);

            return ResponseEntity.ok(result);
        } finally {
            endDraftUse(ontology);
        }

    } catch (Exception e) {
        return reasoningFailure(e, projectId, "explaining inconsistency");
    }
}

    @PostMapping("/{projectId}/explain-entailment")
    public ResponseEntity<Map<String, Object>> explainEntailment(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String entailmentType = request.get("entailmentType");
            String subjectIri = request.get("subjectIri");
            if (entailmentType == null || subjectIri == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "entailmentType and subjectIri are required"));
            }
            String targetIri = request.get("targetIri");
            String propertyIri = request.get("propertyIri");
            String literalValue = request.get("literalValue");

            String requestedReasonerType = request.getOrDefault("reasonerType", "HERMIT");
            ReasonerType requestedType = ReasonerType.valueOf(requestedReasonerType.toUpperCase());
            ReasonerType type = (requestedType == ReasonerType.ELK || requestedType == ReasonerType.STRUCTURAL)
                ? ReasonerType.HERMIT
                : requestedType;

            int maxJustifications = 10;
            if (request.containsKey("maxJustifications")) {
                maxJustifications = Integer.parseInt(request.get("maxJustifications"));
            }
            String mode = request.getOrDefault("mode", "regular");
            String userId = request.get("userId");

            boolean isEquivalentVariant = "EQUIVALENT_CLASSES".equals(entailmentType)
                || "EQUIVALENT_OBJECT_RESTRICTION".equals(entailmentType)
                || "EQUIVALENT_DATA_RESTRICTION".equals(entailmentType);

            // This search is an unbounded black-box justification search — the single
            // heaviest operation on this controller — and should run on the isolated
            // reasoner worker when one is available, same worker-offload gate as
            // consistency/classify/realize/inferred-axioms. Drafts still run in-process:
            // the worker's EditorClient has no way to prove the caller's draft access to
            // the editor (it only carries ownerEmail, not a verifiable bearer token), so
            // editor's /internal/reasoning/.../export.nt correctly 403s a draft fetch from
            // the worker. Offloading drafts needs that token forwarded through the job
            // first — tracked separately, not done here.
            if (!isEquivalentVariant && workerAvailable() && (userId == null || userId.isBlank())) {
                return submitExplainEntailmentToWorker(projectId, requestedReasonerType, userId, maxJustifications, mode,
                    entailmentType, subjectIri, targetIri, propertyIri, literalValue);
            }

            log.info("Explaining entailment {} ({} -> {}) for project: {} with {} (mode={})",
                entailmentType, subjectIri, targetIri, projectId, type, mode);

            OWLOntology ontology = loadOntology(projectId, userId);
            try {
                List<Map<String, Object>> justifications;
                List<String> warnings = null;
                if ("EQUIVALENT_CLASSES".equals(entailmentType)) {
                    if (targetIri == null) {
                        return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "targetIri is required for EQUIVALENT_CLASSES"));
                    }
                    ReasonerService.EquivalentJustificationsResult equivalentResult = reasonerService.findEquivalentClassesJustifications(
                        ontology, subjectIri, targetIri, type, maxJustifications, mode);
                    justifications = equivalentResult.justifications();
                    warnings = equivalentResult.warnings();
                } else if ("EQUIVALENT_OBJECT_RESTRICTION".equals(entailmentType) || "EQUIVALENT_DATA_RESTRICTION".equals(entailmentType)) {
                    if (targetIri == null || propertyIri == null) {
                        return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "targetIri and propertyIri are required for EQUIVALENT_OBJECT_RESTRICTION/EQUIVALENT_DATA_RESTRICTION"));
                    }
                    ReasonerService.EquivalentJustificationsResult equivalentResult = reasonerService.findEquivalentRestrictionJustifications(
                        ontology, subjectIri, propertyIri, targetIri,
                        "EQUIVALENT_DATA_RESTRICTION".equals(entailmentType), literalValue, type, maxJustifications, mode);
                    justifications = equivalentResult.justifications();
                    warnings = equivalentResult.warnings();
                } else {
                    OWLAxiom entailment = reasonerService.buildEntailmentAxiom(
                        entailmentType, subjectIri, targetIri, propertyIri, literalValue);
                    justifications =
                        reasonerService.findEntailmentJustifications(ontology, entailment, type, maxJustifications, mode);
                }

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("usedReasoner", type.getDisplayName());
                if (type != requestedType) {
                    result.put("reasonerUpgraded", true);
                }
                result.put("justifications", justifications);
                if (warnings != null && !warnings.isEmpty()) {
                    result.put("warnings", warnings);
                }
                return ResponseEntity.ok(result);
            } finally {
                endDraftUse(ontology);
            }

        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", iae.getMessage() != null ? iae.getMessage() : "Invalid request"));
        } catch (Throwable e) {
            // Throwable, not Exception: a mismatched/incompatible owlexplanation classpath
            // (or any other library-level LinkageError) must still come back as a real,
            // readable message instead of leaking out as Spring's generic, detail-free
            // "Internal Server Error" default error page.
            return reasoningFailure(e, projectId, "explaining entailment");
        }
    }

    /**
     * Get inferred axioms
     * GET /api/reasoner/{projectId}/inferred-axioms
     */
    @GetMapping("/{projectId}/inferred-axioms")
    public ResponseEntity<Map<String, Object>> getInferredAxioms(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType,
            @RequestParam(required = false) String userId
    ) {
        try {
            if (workerAvailable() && (userId == null || userId.isBlank())) {
                return submitToWorker("REASONER_INFERRED_AXIOMS", projectId, reasonerType);
            }

            log.info("Getting inferred axioms for project: {} with {}", projectId, reasonerType);

            OWLOntology ontology = loadOntology(projectId, userId);
            try {
                ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

                long startTime = System.currentTimeMillis();
                Set<OWLAxiom> inferredAxioms = reasonerService.getInferredAxioms(ontology, type);
                long duration = System.currentTimeMillis() - startTime;

                List<Map<String, Object>> axiomsList = inferredAxioms.stream()
                    .limit(self.research.ontology.common.ReasoningSnapshot.DEFAULT_INFERRED_AXIOM_LIMIT)
                    .map(axiom -> {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("axiomType", axiom.getAxiomType().getName());
                        entry.put("readable", formatAxiom(axiom, ontology));
                        entry.put("axiom", axiom.toString());
                        Map<String, Object> entailment = reasonerService.describeEntailment(axiom);
                        if (entailment != null) {
                            entry.put("entailment", entailment);
                        }
                        return entry;
                    })
                    .collect(Collectors.toList());

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("axioms", axiomsList);
                result.put("totalInferredAxioms", inferredAxioms.size());
                result.put("durationMs", duration);

                return ResponseEntity.ok(result);
            } finally {
                endDraftUse(ontology);
            }

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
            @RequestParam(defaultValue = "HERMIT") String reasonerType,
            @RequestParam(required = false) String userId
    ) {
        try {
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            if (workerAvailable() && (userId == null || userId.isBlank())) {
                Map<String, Object> worker = reasonerWorkerClient.submit("REASONER_SNAPSHOT", projectId, type.name());
                if (Boolean.TRUE.equals(worker.get("rateLimited"))) {
                    return workerRejected(worker);
                }
                Map<String, Object> snapshot = awaitSnapshot(projectId, worker);
                if (snapshot != null && Boolean.TRUE.equals(snapshot.get("consistent"))) {
                    return ResponseEntity.ok(statsFromSnapshot(projectId, type, snapshot));
                }
            }

            OWLOntology ontology = loadOntology(projectId, userId);
            try {
                Map<String, Object> stats = reasonerService.getReasonerStats(ontology, type);
                stats.put("success", true);
                stats.put("projectId", projectId);

                return ResponseEntity.ok(stats);
            } finally {
                endDraftUse(ontology);
            }

        } catch (Exception e) {
            return reasoningFailure(e, projectId, "getting reasoner stats");
        }
    }

    private Map<String, Object> awaitSnapshot(String projectId, Map<String, Object> submitted) throws InterruptedException {
        if (Boolean.FALSE.equals(submitted.get("success")) || submitted.get("jobId") == null) {
            log.warn("Worker did not take the stats snapshot for {} ({}); computing stats here",
                    projectId, submitted.get("error"));
            return null;
        }
        String jobId = String.valueOf(submitted.get("jobId"));
        Map<String, Object> job = submitted;
        long deadline = System.currentTimeMillis() + statsSnapshotWaitMs;
        while (true) {
            String status = String.valueOf(job.getOrDefault("status", "QUEUED"));
            if ("COMPLETED".equals(status)) {
                return snapshotOf(job);
            }
            if ("FAILED".equals(status)) {
                log.warn("Stats snapshot job {} failed for {} ({}); computing stats here", jobId, projectId, job.get("error"));
                return null;
            }
            if (System.currentTimeMillis() >= deadline) {
                reasonerWorkerClient.cancelJob(jobId);
                throw new IllegalStateException("Reasoning timed out after " + (statsSnapshotWaitMs / 1000)
                        + "s while collecting reasoner stats — the ontology may be too large or complex for this reasoner.");
            }
            Thread.sleep(statsSnapshotPollMs);
            Map<String, Object> polled = reasonerWorkerClient.getJob(jobId);
            if (polled != null && polled.get("jobId") != null) {
                job = polled;
            } else if (polled != null && String.valueOf(polled.get("error")).contains("404")) {
                log.warn("Worker no longer has stats snapshot job {} for {}; computing stats here", jobId, projectId);
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshotOf(Map<String, Object> job) {
        if (job.get("result") instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        Map<String, Object> snapshot = new HashMap<>();
        job.forEach((key, value) -> {
            if (!WORKER_JOB_KEYS.contains(key)) {
                snapshot.put(key, value);
            }
        });
        return snapshot;
    }

    private Map<String, Object> statsFromSnapshot(String projectId, ReasonerType requested, Map<String, Object> snapshot) {
        Map<?, ?> counts = snapshot.get("stats") instanceof Map<?, ?> m ? m : Map.of();
        int classCount = intValue(snapshot.get("totalClasses"), intValue(counts.get("classHierarchyNodes"), 0));
        int objectPropertyCount = intValue(counts.get("objectPropertyNodes"), 0);
        int dataPropertyCount = intValue(counts.get("dataPropertyNodes"), 0);
        String reasonerName = snapshot.get("reasonerType") != null
                ? String.valueOf(snapshot.get("reasonerType")) : requested.getDisplayName();

        Map<String, Object> stats = new HashMap<>();
        stats.put("reasonerType", reasonerName);
        stats.put("reasonerName", reasonerName);
        stats.put("isConsistent", true);
        stats.put("classCount", classCount);
        stats.put("objectPropertyCount", objectPropertyCount);
        stats.put("dataPropertyCount", dataPropertyCount);
        stats.put("propertyCount", objectPropertyCount + dataPropertyCount);
        stats.put("individualCount", intValue(counts.get("individuals"), 0));
        stats.put("unsatisfiableClasses", intValue(counts.get("unsatisfiableClasses"), -1));
        stats.put("satisfiableClasses", intValue(counts.get("satisfiableClasses"), classCount));
        OWLOntology loaded = ontologyCache.get(projectId);
        if (loaded != null) {
            stats.put("logicalAxiomCount", loaded.getLogicalAxiomCount());
            stats.put("totalAxiomCount", loaded.getAxiomCount());
        }
        stats.put("supportsIncrementalReasoning", false);
        stats.put("supportsDatatypeReasoning", requested != ReasonerType.STRUCTURAL);
        stats.put("supportsOWL2DL", requested == ReasonerType.HERMIT || requested == ReasonerType.PELLET);
        if (snapshot.get("downgradedWarning") != null) {
            stats.put("downgradedWarning", snapshot.get("downgradedWarning"));
        }
        stats.put("success", true);
        stats.put("projectId", projectId);
        return stats;
    }

    private static int intValue(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
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
     * Clear the reasoner cache for a single project only (targeted eviction).
     * Called by ontology-editor after every save so a stale, pre-edit cached
     * ontology can never keep serving reasoning results — without dropping
     * every other project's/user's cached reasoner state the way the global
     * clear-cache above does. IDs can be hierarchical (proj-xxx--uuid); evict
     * both the full id and the base prefix since either could be the actual
     * cache key depending on which one loadOntology() resolved against.
     * POST /api/reasoner/clear-cache/{projectId}
     */
    @PostMapping("/clear-cache/{projectId}")
    public ResponseEntity<Map<String, Object>> clearCacheForProject(@PathVariable String projectId) {
        try {
            int evicted = 0;
            OWLOntology removed = ontologyCache.remove(projectId);
            if (removed != null) {
                releaseProjectResources(projectId, removed);
                evicted++;
            }
            if (projectId != null && projectId.contains("--")) {
                String baseProjectId = extractBaseProjectId(projectId);
                if (!baseProjectId.equals(projectId)) {
                    OWLOntology removedBase = ontologyCache.remove(baseProjectId);
                    if (removedBase != null) {
                        releaseProjectResources(baseProjectId, removedBase);
                        evicted++;
                    }
                }
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Cache cleared for project",
                "evicted", evicted
            ));

        } catch (Exception e) {
            log.error("Error clearing cache for project {}", projectId, e);
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
    private ResponseEntity<Map<String, Object>> workerRejected(Map<String, Object> worker) {
        if (Boolean.TRUE.equals(worker.get("rateLimited"))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "success", false,
                    "error", String.valueOf(worker.get("error"))));
        }
        return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", ReasoningFriendlyErrors.forUser(String.valueOf(worker.get("error")))));
    }

    private ResponseEntity<Map<String, Object>> submitToWorker(String jobType, String projectId, String reasonerType) {
        Map<String, Object> worker = reasonerWorkerClient.submit(jobType, projectId, reasonerType);
        if (Boolean.FALSE.equals(worker.get("success"))) {
            return workerRejected(worker);
        }
        String jobId = String.valueOf(worker.get("jobId"));
        return ResponseEntity.accepted().body(Map.of(
                "async", true,
                "taskId", jobId,
                "jobId", jobId,
                "status", worker.getOrDefault("status", "QUEUED"),
                "pollUrl", "/api/dl-query/jobs/" + jobId));
    }

    /** Same async-accepted contract as {@link #submitToWorker}, plus the explanation-only fields. */
    private ResponseEntity<Map<String, Object>> submitExplainInconsistencyToWorker(
            String projectId, String reasonerType, String explanationMode, int maxJustifications) {
        Map<String, Object> worker = reasonerWorkerClient.submit(
                "REASONER_EXPLAIN_INCONSISTENCY", projectId, reasonerType, explanationMode, maxJustifications);
        if (Boolean.FALSE.equals(worker.get("success"))) {
            return workerRejected(worker);
        }
        String jobId = String.valueOf(worker.get("jobId"));
        return ResponseEntity.accepted().body(Map.of(
                "async", true,
                "taskId", jobId,
                "jobId", jobId,
                "status", worker.getOrDefault("status", "QUEUED"),
                "pollUrl", "/api/dl-query/jobs/" + jobId));
    }

    /**
     * Unlike the other worker-backed endpoints, this blocks the request thread until the
     * worker finishes instead of returning the async-accepted contract — the frontend's
     * explain-entailment call expects a synchronous {justifications} response and was never
     * built to poll a job. Blocking a thread is cheap; the whole point of routing here is to
     * keep the actual unbounded justification search out of this service's own heap.
     */
    private ResponseEntity<Map<String, Object>> submitExplainEntailmentToWorker(
            String projectId, String reasonerType, String userId, int maxJustifications, String mode,
            String entailmentType, String subjectIri, String targetIri, String propertyIri, String literalValue) {
        Map<String, Object> worker = reasonerWorkerClient.submitExplainEntailment(
                projectId, reasonerType, userId, maxJustifications, mode,
                entailmentType, subjectIri, targetIri, propertyIri, literalValue);
        if (Boolean.FALSE.equals(worker.get("success"))) {
            return workerRejected(worker);
        }
        try {
            Map<String, Object> finished = awaitExplainEntailmentJob(projectId, worker);
            if (finished == null) {
                return ResponseEntity.status(500).body(Map.of("success", false,
                        "error", "The reasoning worker did not return a result for this explanation request."));
            }
            if (Boolean.FALSE.equals(finished.get("success"))) {
                return ResponseEntity.status(500).body(Map.of("success", false,
                        "error", ReasoningFriendlyErrors.forUser(String.valueOf(finished.get("error")))));
            }
            return ResponseEntity.ok(finished);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of("success", false,
                    "error", "Interrupted while waiting for the reasoning worker."));
        } catch (IllegalStateException timedOut) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("success", false,
                    "error", timedOut.getMessage()));
        }
    }

    private Map<String, Object> awaitExplainEntailmentJob(String projectId, Map<String, Object> submitted)
            throws InterruptedException {
        if (Boolean.FALSE.equals(submitted.get("success")) || submitted.get("jobId") == null) {
            return null;
        }
        String jobId = String.valueOf(submitted.get("jobId"));
        Map<String, Object> job = submitted;
        long deadline = System.currentTimeMillis() + explainEntailmentWaitMs;
        while (true) {
            String status = String.valueOf(job.getOrDefault("status", "QUEUED"));
            if ("COMPLETED".equals(status)) {
                return snapshotOf(job);
            }
            if ("FAILED".equals(status)) {
                return Map.of("success", false,
                        "error", job.get("error") != null ? job.get("error") : "Explanation job failed");
            }
            if (System.currentTimeMillis() >= deadline) {
                reasonerWorkerClient.cancelJob(jobId);
                throw new IllegalStateException("Explanation search timed out after "
                        + (explainEntailmentWaitMs / 1000) + "s — the reasoner may still be warming up; try again.");
            }
            Thread.sleep(statsSnapshotPollMs);
            Map<String, Object> polled = reasonerWorkerClient.getJob(jobId);
            if (polled != null && polled.get("jobId") != null) {
                job = polled;
            } else if (polled != null && String.valueOf(polled.get("error")).contains("404")) {
                return Map.of("success", false,
                        "error", "The reasoning worker no longer has this job (it may have restarted).");
            }
        }
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
