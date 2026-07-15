package self.research.ontology.reasoner.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.model.ReasoningJobEvent;
import self.research.ontology.reasoner.model.SubmitReasoningJobRequest;
import self.research.ontology.reasoner.service.ReasoningQueueManager;
import self.research.ontology.reasoner.service.ReasoningQueueProcessor;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/reasoning")
@CrossOrigin(originPatterns = "*")
@RequiredArgsConstructor
public class ReasoningJobController {

    private final ReasoningQueueManager queueManager;
    private final ReasoningQueueProcessor queueProcessor;

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody SubmitReasoningJobRequest request) {
        try {
            ReasoningJob job = queueManager.enqueue(
                    request.getJobType(),
                    request.getProjectId(),
                    request.getExpression(),
                    request.getQueryTypes(),
                    request.getReasonerType(),
                    request.getOwnerEmail());
            queueProcessor.processNext();

            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("async", true);
            body.put("jobId", job.getJobId());
            body.put("jobType", job.getJobType().name());
            body.put("status", job.getStatus().name());
            body.put("queuePosition", job.getQueuePosition());
            body.put("estimatedWaitTimeMs", queueManager.getEstimatedWaitTimeMs(job.getJobId()));
            return ResponseEntity.accepted().body(body);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", ReasoningFriendlyErrors.forUser(e.getMessage())
            ));
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getJob(@PathVariable String jobId) {
        ReasoningJob job = queueManager.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(job));
    }

    @GetMapping("/stats")
    public ResponseEntity<ReasoningJobEvent.QueueStats> stats() {
        return ResponseEntity.ok(queueManager.getQueueStats());
    }

    private Map<String, Object> toResponse(ReasoningJob job) {
        Map<String, Object> response = new HashMap<>();
        response.put("async", true);
        response.put("jobId", job.getJobId());
        response.put("jobType", job.getJobType() != null ? job.getJobType().name() : null);
        response.put("projectId", job.getProjectId());
        response.put("status", job.getStatus().name());
        response.put("queuePosition", job.getQueuePosition());
        response.put("estimatedWaitTimeMs", queueManager.getEstimatedWaitTimeMs(job.getJobId()));
        response.put("executionTimeMs", job.getExecutionTimeMs());
        if (job.getResult() != null) {
            response.putAll(job.getResult());
        }
        if (job.getError() != null) {
            response.put("success", false);
            response.put("error", ReasoningFriendlyErrors.forUser(job.getError()));
        }
        return response;
    }
}
