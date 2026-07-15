package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningFriendlyErrors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class ReasoningJobSubmitService {

    private static final Set<String> HEAVY_REASONERS = Set.of("HERMIT", "PELLET", "OPENLLET", "FACTPLUSPLUS");

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    @Value("${ontocode.reasoning.large-triple-threshold:500000}")
    private long heavyReasonerThreshold;

    @Autowired
    private SparqlDatasetService datasetService;

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Autowired(required = false)
    private ReasoningJobRelayService reasoningJobRelayService;

    public boolean isWorkerEnabled() {
        return reasonerWorkerEnabled && reasonerWorkerClient != null && reasoningJobRelayService != null;
    }

    public ResponseEntity<Map<String, Object>> submit(String jobType,
                                                      String projectId,
                                                      String reasonerType,
                                                      String ownerEmail) {
        if (!isWorkerEnabled()) {
            return null;
        }

        // Size guard: block heavy reasoners on large ontologies immediately, before queuing
        if (reasonerType != null && HEAVY_REASONERS.contains(reasonerType.toUpperCase())) {
            try {
                long tripleCount = datasetService.getDatasetSize(projectId);
                if (tripleCount > heavyReasonerThreshold) {
                    log.warn("[ReasonerGuard] Blocked {} on project {} ({} triples > {} threshold)",
                            reasonerType, projectId, tripleCount, heavyReasonerThreshold);
                    return ResponseEntity.ok(Map.of(
                            "success", false,
                            "tooLargeForReasoner", true,
                            "suggestedReasoner", "ELK",
                            "tripleCount", tripleCount,
                            "error", "This ontology has " + tripleCount + " triples and is too large for "
                                    + reasonerType + ". Only ELK is supported for ontologies of this size."
                    ));
                }
            } catch (Exception e) {
                log.warn("[ReasonerGuard] Could not check size for project {}: {} — proceeding without guard",
                        projectId, e.getMessage());
            }
        }

        Map<String, Object> worker = reasonerWorkerClient.submitJob(
                jobType, projectId, null, null, reasonerType, ownerEmail);
        if (Boolean.FALSE.equals(worker.get("success"))) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", ReasoningFriendlyErrors.forUser(string(worker.get("error")))
            ));
        }

        String jobId = string(worker.get("jobId"));
        reasoningJobRelayService.rememberSubmittedJob(jobId, projectId, jobType, jobType, worker);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("async", true);
        body.put("jobId", jobId);
        body.put("taskId", jobId);
        body.put("status", worker.getOrDefault("status", "QUEUED"));
        body.put("queuePosition", worker.getOrDefault("queuePosition", 1));
        body.put("estimatedWaitTimeMs", worker.getOrDefault("estimatedWaitTimeMs", 0));
        body.put("pollUrl", "/api/dl-query/jobs/" + jobId);
        return ResponseEntity.accepted().body(body);
    }

    private static String string(Object value) {
        return value != null ? value.toString() : null;
    }
}
