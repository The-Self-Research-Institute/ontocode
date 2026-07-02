package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.model.collaboration.DLQueryJobMessage;
import self.research.ontology.owlEditor.service.DLQueryQueueManager;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/dl-query")
@CrossOrigin(originPatterns = "*")
@RequiredArgsConstructor
public class DLQueryJobController {

    private final DLQueryQueueManager queueManager;

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Autowired(required = false)
    private ReasoningJobRelayService reasoningJobRelayService;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getJob(@PathVariable String jobId) {
        if (reasonerWorkerEnabled && reasoningJobRelayService != null) {
            DLQueryJob relayed = reasoningJobRelayService.getJob(jobId);
            if (relayed != null) {
                return ResponseEntity.ok(toResponse(relayed));
            }
            if (reasonerWorkerClient != null) {
                Map<String, Object> remote = reasonerWorkerClient.getJob(jobId);
                if (remote != null && remote.get("jobId") != null) {
                    return ResponseEntity.ok(remote);
                }
            }
            return ResponseEntity.notFound().build();
        }

        DLQueryJob job = queueManager.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(job));
    }

    @GetMapping("/stats")
    public ResponseEntity<DLQueryJobMessage.DLQueryQueueStats> getStats() {
        return ResponseEntity.ok(queueManager.getQueueStats());
    }

    private Map<String, Object> toResponse(DLQueryJob job) {
        Map<String, Object> response = new HashMap<>();
        response.put("async", true);
        response.put("jobId", job.getJobId());
        response.put("projectId", job.getProjectId());
        response.put("status", job.getStatus().name());
        response.put("queuePosition", job.getQueuePosition());
        response.put("estimatedWaitTimeMs",
                reasonerWorkerEnabled ? job.getEstimatedWaitTimeMs() : queueManager.getEstimatedWaitTimeMs(job.getJobId()));
        response.put("executionTimeMs", job.getExecutionTimeMs());
        if (job.getResult() != null) {
            response.putAll(job.getResult());
        }
        if (job.getError() != null) {
            response.put("success", false);
            response.put("error", DLQueryQueueManager.userFriendlyError(job.getError()));
        }
        return response;
    }
}
