package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;
    private final ThreadPoolExecutor checkExecutor;
    private final ScheduledExecutorService watchdog;

    @Value("${ontology.plugin-service.url:http://localhost:8087}")
    private String pluginServiceUrl;

    @Value("${assistant.consistency-check.wall-clock-budget-ms:60000}")
    private long wallClockBudgetMs;

    @Value("${assistant.consistency-check.max-dataset-size:150000}")
    private long maxDatasetSizeForCheck;

    public AssistantConsistencyCheckService(SparqlDatasetService datasetService, StorageManager storageManager,
                                            LineRangeSpliceWriter spliceWriter, CodeViewReimportPipeline reimportPipeline,
                                            AssistantEditGroupRepository groupRepository,
                                            SimpMessagingTemplate messagingTemplate) {
        this.datasetService = datasetService;
        this.storageManager = storageManager;
        this.spliceWriter = spliceWriter;
        this.reimportPipeline = reimportPipeline;
        this.groupRepository = groupRepository;
        this.messagingTemplate = messagingTemplate;
        this.restTemplate = buildRestTemplate();
        this.checkExecutor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20),
                namedDaemonFactory("assistant-consistency-check"), new ThreadPoolExecutor.AbortPolicy());
        this.watchdog = Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("assistant-consistency-watchdog"));
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

    public void startAsyncCheck(String serverGroupId, String projectId, String targetPath,
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

            Map<String, String> body = new HashMap<>();
            body.put("reasonerType", "HERMIT");
            body.put("whatIfKey", whatIfKey);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>)
                    restTemplate.postForEntity(pluginServiceUrl + "/api/reasoner/" + projectId + "/consistency",
                            body, Map.class);
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

    private void resolve(String serverGroupId, ConsistencyCheckState state, String detail) {
        groupRepository.findById(serverGroupId).ifPresent(group -> {
            ConsistencyCheckRecord current = group.getConsistencyCheck();
            if (current != null && current.getState() != null && current.getState() != ConsistencyCheckState.PENDING) {
                return;
            }
            group.setConsistencyCheck(ConsistencyCheckRecord.builder()
                    .jobId("whatif-" + serverGroupId)
                    .state(state)
                    .detail(detail)
                    .resolvedAt(Instant.now())
                    .build());
            groupRepository.save(group);
            publish(serverGroupId, state, detail);
        });
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
