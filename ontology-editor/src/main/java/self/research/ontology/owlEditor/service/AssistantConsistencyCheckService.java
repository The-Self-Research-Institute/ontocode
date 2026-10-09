package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckRecord;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckState;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AssistantConsistencyCheckService {

    public static final String CONSISTENCY_PRESERVED_CHECK = "consistency_preserved";
    public static final String PENDING_STATUS = "pending";

    private final SparqlDatasetService datasetService;
    private final StorageManager storageManager;
    private final LineRangeSpliceWriter spliceWriter;
    private final CodeViewReimportPipeline reimportPipeline;
    private final AssistantEditGroupRepository groupRepository;
    private final MongoTemplate mongoTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;
    private final ThreadPoolExecutor checkExecutor;
    private final ScheduledExecutorService watchdog;
    private final ScheduledExecutorService debounceScheduler;
    private final CodeViewRangeMatcher rangeMatcher;
    private final ConcurrentMap<String, PendingBatch> pendingBatches = new ConcurrentHashMap<>();

    @Value("${ontology.plugin-service.url:http://localhost:8087}")
    private String pluginServiceUrl;

    @Value("${assistant.consistency-check.wall-clock-budget-ms:60000}")
    private long wallClockBudgetMs;

    @Value("${assistant.consistency-check.max-dataset-size:150000}")
    private long maxDatasetSizeForCheck;

    @Value("${assistant.consistency-check.debounce-enabled:true}")
    private boolean debounceEnabled;

    @Value("${assistant.consistency-check.debounce-quiet-ms:600}")
    private long debounceQuietMs;

    @Value("${assistant.consistency-check.debounce-max-wait-ms:2500}")
    private long debounceMaxWaitMs;

    @Value("${assistant.consistency-check.debounce-max-batch-size:10}")
    private int debounceMaxBatchSize;

    private static final class PendingBatch {
        final String projectId;
        final List<String> groupIds = new ArrayList<>();
        final long firstArrivalNanos = System.nanoTime();
        ScheduledFuture<?> flushTask;

        PendingBatch(String projectId) {
            this.projectId = projectId;
        }
    }

    public AssistantConsistencyCheckService(SparqlDatasetService datasetService, StorageManager storageManager,
                                            LineRangeSpliceWriter spliceWriter, CodeViewReimportPipeline reimportPipeline,
                                            AssistantEditGroupRepository groupRepository, MongoTemplate mongoTemplate,
                                            SimpMessagingTemplate messagingTemplate) {
        this.datasetService = datasetService;
        this.storageManager = storageManager;
        this.spliceWriter = spliceWriter;
        this.reimportPipeline = reimportPipeline;
        this.groupRepository = groupRepository;
        this.mongoTemplate = mongoTemplate;
        this.messagingTemplate = messagingTemplate;
        this.restTemplate = buildRestTemplate();
        this.checkExecutor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20),
                namedDaemonFactory("assistant-consistency-check"), new ThreadPoolExecutor.AbortPolicy());
        this.watchdog = Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("assistant-consistency-watchdog"));
        this.debounceScheduler = Executors.newSingleThreadScheduledExecutor(
                namedDaemonFactory("assistant-consistency-debounce"));
        this.rangeMatcher = new CodeViewRangeMatcher(storageManager);
    }

    private static java.util.concurrent.ThreadFactory namedDaemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(25_000);
        return new RestTemplate(f);
    }

    public boolean worthChecking(String targetPath, List<EditInput> edits) {
        return AssistantConsistencyCheckGate.worthChecking(targetPath, edits);
    }

    public AssistantEditProposalService.CheckResult gateCheck(String projectId, String targetPath, List<EditInput> edits) {
        if (!worthChecking(targetPath, edits)) {
            return new AssistantEditProposalService.CheckResult(CONSISTENCY_PRESERVED_CHECK, true,
                    "Skipped — this edit doesn't appear to touch class axioms.");
        }
        long datasetSize = safeDatasetSize(projectId);
        if (datasetSize > maxDatasetSizeForCheck) {
            return new AssistantEditProposalService.CheckResult(CONSISTENCY_PRESERVED_CHECK, true,
                    "Skipped — this ontology (" + datasetSize + " triples) is too large for an automatic "
                            + "what-if check; run check_consistency by hand after applying if this touches hierarchy "
                            + "or disjointness.");
        }
        return new AssistantEditProposalService.CheckResult(CONSISTENCY_PRESERVED_CHECK, true,
                "Checking whether this keeps the ontology logically consistent…", PENDING_STATUS);
    }

    private long safeDatasetSize(String projectId) {
        try {
            return datasetService.getDatasetSize(projectId);
        } catch (Exception e) {
            log.warn("[Assistant] Could not read dataset size for project {}, assuming small enough to check: {}",
                    projectId, e.getMessage());
            return 0;
        }
    }

    public Optional<ConsistencyCheckRecord> getRecord(String sessionId, String serverGroupId, String userEmail) {
        return groupRepository.findById(serverGroupId)
                .filter(g -> userEmail != null && userEmail.equals(g.getUserEmail())
                        && sessionId != null && sessionId.equals(g.getSessionId()))
                .map(AssistantEditGroupDocument::getConsistencyCheck);
    }

    private static final int MAX_REMEMBERED_CALLERS = 1000;

    private final ConcurrentMap<String, String> callerAuthorization = new ConcurrentHashMap<>();

    public void rememberCaller(String sessionId, String authorizationHeader) {
        if (sessionId == null || authorizationHeader == null || authorizationHeader.isBlank()) {
            return;
        }
        if (callerAuthorization.size() >= MAX_REMEMBERED_CALLERS) {
            callerAuthorization.clear();
        }
        callerAuthorization.put(sessionId, authorizationHeader);
    }

    private ResponseEntity<Map<String, Object>> postConsistency(String projectId, String serverGroupId,
                                                                 Map<String, String> body) {
        String sessionId = groupRepository.findById(serverGroupId)
                .map(AssistantEditGroupDocument::getSessionId).orElse(null);
        String authorization = sessionId == null ? null : callerAuthorization.get(sessionId);
        if (authorization == null) {
            throw new IllegalStateException("the reasoner needs your login and it wasn't available for this check");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>)
                restTemplate.exchange(pluginServiceUrl + "/api/reasoner/" + projectId + "/consistency",
                        HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return response;
    }

    public void startAsyncCheck(String serverGroupId, String projectId, String targetPath,
                                List<AssistantEditGroupDocument.EditEntry> editEntries,
                                StorageManager.ContentScope scope) {
        if (!debounceEnabled) {
            dispatchSingle(serverGroupId, projectId, targetPath, editEntries, scope);
            return;
        }
        enqueueForBatch(serverGroupId, projectId);
    }

    private void enqueueForBatch(String serverGroupId, String projectId) {
        String sessionId = groupRepository.findById(serverGroupId)
                .map(AssistantEditGroupDocument::getSessionId)
                .orElse(null);
        String batchKey = projectId + "::" + (sessionId != null ? sessionId : serverGroupId);
        AtomicBoolean flushNow = new AtomicBoolean(false);
        pendingBatches.compute(batchKey, (key, existing) -> {
            PendingBatch batch = existing != null ? existing : new PendingBatch(projectId);
            batch.groupIds.add(serverGroupId);
            if (batch.flushTask != null) {
                batch.flushTask.cancel(false);
            }
            if (batch.groupIds.size() >= debounceMaxBatchSize) {
                flushNow.set(true);
                return batch;
            }
            long elapsedMs = (System.nanoTime() - batch.firstArrivalNanos) / 1_000_000;
            long delay = Math.max(0, Math.min(debounceQuietMs, debounceMaxWaitMs - elapsedMs));
            batch.flushTask = debounceScheduler.schedule(() -> flush(batchKey), delay, TimeUnit.MILLISECONDS);
            return batch;
        });
        if (flushNow.get()) {
            flush(batchKey);
        }
    }

    private void flush(String batchKey) {
        PendingBatch batch = pendingBatches.remove(batchKey);
        if (batch == null || batch.groupIds.isEmpty()) {
            return;
        }
        checkExecutor.execute(() -> processBatch(batch.projectId, batch.groupIds));
    }

    private void processBatch(String projectId, List<String> groupIds) {
        List<AssistantEditGroupDocument> groups = new ArrayList<>();
        groupRepository.findAllById(groupIds).forEach(groups::add);
        List<AssistantEditGroupDocument> stillPending = groups.stream()
                .filter(g -> g.getConsistencyCheck() == null
                        || g.getConsistencyCheck().getState() == ConsistencyCheckState.PENDING)
                .toList();
        if (stillPending.isEmpty()) {
            return;
        }
        Map<String, List<AssistantEditGroupDocument>> byTargetPath = stillPending.stream()
                .collect(Collectors.groupingBy(AssistantEditGroupDocument::getTargetPath,
                        LinkedHashMap::new, Collectors.toList()));
        for (List<AssistantEditGroupDocument> samePathGroup : byTargetPath.values()) {
            if (samePathGroup.size() == 1) {
                dispatchSingleFromDocument(samePathGroup.get(0));
                continue;
            }
            StorageManager.ContentScope scope = scopeOf(samePathGroup.get(0));
            if (isMergeEligible(projectId, scope, samePathGroup)) {
                dispatchMergedBatch(projectId, scope, samePathGroup.get(0).getTargetPath(), samePathGroup);
            } else {
                samePathGroup.forEach(this::dispatchSingleFromDocument);
            }
        }
    }

    private boolean isMergeEligible(String projectId, StorageManager.ContentScope scope,
                                    List<AssistantEditGroupDocument> samePathGroup) {
        long currentVersion = storageManager.resolveGraphVersion(projectId, scope);
        for (AssistantEditGroupDocument group : samePathGroup) {
            Long versionAtPropose = group.getPublicGraphVersionAtPropose();
            if (versionAtPropose == null || currentVersion != versionAtPropose) {
                return false;
            }
        }
        List<AssistantEditGroupDocument.EditEntry> allEdits = samePathGroup.stream()
                .flatMap(g -> g.getEdits().stream())
                .sorted(Comparator.comparingLong(AssistantEditGroupDocument.EditEntry::getStartLine))
                .toList();
        if (!ProposedEdits.checkNoCrossGroupOverlap(allEdits).ok()) {
            return false;
        }
        String targetPath = samePathGroup.get(0).getTargetPath();
        List<CodeViewRangeMatcher.ExpectedRange> ranges = allEdits.stream()
                .map(e -> new CodeViewRangeMatcher.ExpectedRange(e.getStartLine(), e.getLineCount(), e.getOriginalText()))
                .toList();
        CodeViewRangeMatcher.MatchResult match = scope.draft()
                ? rangeMatcher.matchWithDetail(projectId, targetPath, ranges, scope)
                : rangeMatcher.matchWithDetail(projectId, targetPath, ranges);
        return match.matches();
    }

    private static StorageManager.ContentScope scopeOf(AssistantEditGroupDocument group) {
        return new StorageManager.ContentScope(group.isDraft(), group.getDraftUserId());
    }

    private void dispatchSingleFromDocument(AssistantEditGroupDocument group) {
        dispatchSingle(group.getId(), group.getProjectId(), group.getTargetPath(), group.getEdits(), scopeOf(group));
    }

    private void dispatchSingle(String serverGroupId, String projectId, String targetPath,
                                List<AssistantEditGroupDocument.EditEntry> editEntries,
                                StorageManager.ContentScope scope) {
        String whatIfKey = "assistant-whatif-" + serverGroupId;
        Future<?> future;
        try {
            future = checkExecutor.submit(() -> runCheck(serverGroupId, projectId, targetPath, editEntries, scope, whatIfKey));
        } catch (RejectedExecutionException rejected) {
            resolve(serverGroupId, ConsistencyCheckState.ERROR,
                    "Too many consistency checks running right now; this one was skipped.");
            return;
        }
        watchdog.schedule(() -> {
            if (future.cancel(true)) {
                resolve(serverGroupId, ConsistencyCheckState.TIMED_OUT,
                        "Couldn't verify in time — treated as inconclusive.");
            }
        }, wallClockBudgetMs, TimeUnit.MILLISECONDS);
    }

    private void dispatchMergedBatch(String projectId, StorageManager.ContentScope scope, String targetPath,
                                     List<AssistantEditGroupDocument> groups) {
        List<String> groupIds = groups.stream().map(AssistantEditGroupDocument::getId).toList();
        String whatIfKey = "assistant-whatif-batch-" + UUID.randomUUID();
        Future<?> future;
        try {
            future = checkExecutor.submit(() -> runMergedBatchCheck(projectId, targetPath, groups, scope, whatIfKey));
        } catch (RejectedExecutionException rejected) {
            groupIds.forEach(id -> resolve(id, ConsistencyCheckState.ERROR,
                    "Too many consistency checks running right now; this one was skipped."));
            return;
        }
        watchdog.schedule(() -> {
            if (future.cancel(true)) {
                groupIds.forEach(id -> resolve(id, ConsistencyCheckState.TIMED_OUT,
                        "Couldn't verify in time — treated as inconclusive."));
            }
        }, wallClockBudgetMs, TimeUnit.MILLISECONDS);
    }

    private void runCheck(String serverGroupId, String projectId, String targetPath,
                          List<AssistantEditGroupDocument.EditEntry> editEntries, StorageManager.ContentScope scope,
                          String whatIfKey) {
        boolean graphCopied = false;
        try {
            datasetService.copyMainGraphToDraft(projectId, whatIfKey);
            graphCopied = true;

            List<LineRangeSpliceWriter.SpliceEdit> spliceEdits = editEntries.stream()
                    .map(e -> new LineRangeSpliceWriter.SpliceEdit(e.getStartLine(), e.getLineCount(), e.getNewText()))
                    .toList();
            Path sourceFile = scope.draft()
                    ? storageManager.resolveCodeViewFile(projectId, targetPath, scope)
                    : storageManager.ensureCodeViewFile(projectId, targetPath);
            Path splicedFile = spliceWriter.splice(sourceFile, storageManager.extensionFor(targetPath), spliceEdits);
            try {
                reimportPipeline.reimport(new CodeViewReimportPipeline.ReimportRequest(
                        projectId, targetPath, splicedFile, true, whatIfKey, "assistant-consistency-check",
                        datasetService.getDraftGraphUri(projectId, whatIfKey), null, true, true));
            } finally {
                Files.deleteIfExists(splicedFile);
            }

            String ontologyContent = datasetService.exportDraftGraphContent(projectId, whatIfKey, RDFFormat.NTRIPLES);
            Map<String, String> body = new HashMap<>();
            body.put("reasonerType", "HERMIT");
            body.put("whatIfKey", whatIfKey);
            body.put("ontologyContent", ontologyContent);
            ResponseEntity<Map<String, Object>> response = postConsistency(projectId, serverGroupId, body);
            Map<String, Object> result = response.getBody();
            boolean consistent = result != null && Boolean.TRUE.equals(result.get("consistent"));
            if (consistent) {
                resolve(serverGroupId, ConsistencyCheckState.PASSED,
                        "This edit keeps the ontology logically consistent.");
            } else {
                resolve(serverGroupId, ConsistencyCheckState.FAILED,
                        "Applying this would make the ontology logically inconsistent.");
            }
        } catch (Exception e) {
            log.warn("[Assistant] What-if consistency check failed for group {}: {}", serverGroupId, e.getMessage());
            resolve(serverGroupId, ConsistencyCheckState.ERROR,
                    "Couldn't verify (" + e.getMessage() + ") — treated as inconclusive.");
        } finally {
            if (graphCopied) {
                try {
                    datasetService.clearDraftGraph(projectId, whatIfKey);
                } catch (Exception cleanupEx) {
                    log.warn("[Assistant] Failed to clear what-if scratch graph for group {}: {}",
                            serverGroupId, cleanupEx.getMessage());
                }
            }
        }
    }

    private void runMergedBatchCheck(String projectId, String targetPath, List<AssistantEditGroupDocument> groups,
                                     StorageManager.ContentScope scope, String whatIfKey) {
        List<String> groupIds = groups.stream().map(AssistantEditGroupDocument::getId).toList();
        boolean graphCopied = false;
        try {
            datasetService.copyMainGraphToDraft(projectId, whatIfKey);
            graphCopied = true;

            List<LineRangeSpliceWriter.SpliceEdit> spliceEdits = groups.stream()
                    .flatMap(g -> g.getEdits().stream())
                    .sorted(Comparator.comparingLong(AssistantEditGroupDocument.EditEntry::getStartLine))
                    .map(e -> new LineRangeSpliceWriter.SpliceEdit(e.getStartLine(), e.getLineCount(), e.getNewText()))
                    .toList();
            Path sourceFile = scope.draft()
                    ? storageManager.resolveCodeViewFile(projectId, targetPath, scope)
                    : storageManager.ensureCodeViewFile(projectId, targetPath);
            Path splicedFile = spliceWriter.splice(sourceFile, storageManager.extensionFor(targetPath), spliceEdits);
            try {
                reimportPipeline.reimport(new CodeViewReimportPipeline.ReimportRequest(
                        projectId, targetPath, splicedFile, true, whatIfKey, "assistant-consistency-check",
                        datasetService.getDraftGraphUri(projectId, whatIfKey), null, true, true));
            } finally {
                Files.deleteIfExists(splicedFile);
            }

            String ontologyContent = datasetService.exportDraftGraphContent(projectId, whatIfKey, RDFFormat.NTRIPLES);
            Map<String, String> body = new HashMap<>();
            body.put("reasonerType", "HERMIT");
            body.put("whatIfKey", whatIfKey);
            body.put("ontologyContent", ontologyContent);
            ResponseEntity<Map<String, Object>> response = postConsistency(projectId, groupIds.iterator().next(), body);
            Map<String, Object> result = response.getBody();
            boolean consistent = result != null && Boolean.TRUE.equals(result.get("consistent"));
            if (consistent) {
                groupIds.forEach(id -> resolve(id, ConsistencyCheckState.PASSED,
                        "This edit keeps the ontology logically consistent."));
            } else {
                groupIds.forEach(id -> resolve(id, ConsistencyCheckState.FAILED,
                        "Applying this would make the ontology logically inconsistent."));
            }
        } catch (Exception e) {
            log.warn("[Assistant] Batched what-if consistency check failed for groups {}: {}", groupIds, e.getMessage());
            groupIds.forEach(id -> resolve(id, ConsistencyCheckState.ERROR,
                    "Couldn't verify (" + e.getMessage() + ") — treated as inconclusive."));
        } finally {
            if (graphCopied) {
                try {
                    datasetService.clearDraftGraph(projectId, whatIfKey);
                } catch (Exception cleanupEx) {
                    log.warn("[Assistant] Failed to clear what-if scratch graph for batch {}: {}",
                            whatIfKey, cleanupEx.getMessage());
                }
            }
        }
    }

    private void resolve(String serverGroupId, ConsistencyCheckState state, String detail) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("id").is(serverGroupId),
                new Criteria().orOperator(
                        Criteria.where("consistencyCheck.state").is(null),
                        Criteria.where("consistencyCheck.state").is(ConsistencyCheckState.PENDING))));
        Update update = new Update()
                .set("consistencyCheck.jobId", "whatif-" + serverGroupId)
                .set("consistencyCheck.state", state)
                .set("consistencyCheck.detail", detail)
                .set("consistencyCheck.resolvedAt", Instant.now());
        AssistantEditGroupDocument updated = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), AssistantEditGroupDocument.class);
        if (updated != null) {
            publish(serverGroupId, state, detail);
        }
    }

    private void publish(String serverGroupId, ConsistencyCheckState state, String detail) {
        try {
            messagingTemplate.convertAndSend("/topic/assistant/consistency-check/" + serverGroupId,
                    Map.of("serverGroupId", serverGroupId, "state", state.name(), "detail", detail == null ? "" : detail));
        } catch (Exception e) {
            log.warn("[Assistant] Failed to publish consistency-check update for group {}: {}",
                    serverGroupId, e.getMessage());
        }
    }
}
