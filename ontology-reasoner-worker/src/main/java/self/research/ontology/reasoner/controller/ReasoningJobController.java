package self.research.ontology.reasoner.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
                    request.getOwnerEmail(),
                    request.getMaxJustifications(),
                    request.getExplanationMode(),
                    request.getEntailmentType(),
                    request.getSubjectIri(),
                    request.getTargetIri(),
                    request.getPropertyIri(),
                    request.getLiteralValue());
            queueProcessor.processNext();

            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("async", true);
            body.put("jobId", job.getJobId());
            body.put("jobType", job.getJobType().name());
            body.put("status", job.getStatus().name());
            body.put("queuePosition", job.getQueuePosition());
            body.put("estimatedWaitTimeMs", queueManager.getEstimatedWaitTimeMs(job));
            if (job.getJobType() == ReasoningJob.JobType.REASONER_SNAPSHOT
                    && job.getStatus() == ReasoningJob.Status.COMPLETED && job.getResult() != null) {
                body.put("async", false);
                body.put("projectId", job.getProjectId());
                body.put("executionTimeMs", job.getExecutionTimeMs());
                body.put("result", job.getResult());
                return ResponseEntity.ok(body);
            }
            return ResponseEntity.accepted().body(body);
        } catch (ReasoningQueueManager.ProjectQueueFullException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
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

    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String jobId) {
        ReasoningQueueManager.CancelResult result = queueProcessor.cancel(jobId);
        if (result.outcome() == ReasoningQueueManager.CancelOutcome.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        ReasoningJob job = result.job();
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("cancelled", result.outcome() == ReasoningQueueManager.CancelOutcome.CANCELLED_QUEUED
                || result.outcome() == ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING);
        body.put("jobId", job.getJobId());
        body.put("jobType", job.getJobType() != null ? job.getJobType().name() : null);
        body.put("projectId", job.getProjectId());
        body.put("status", job.getStatus().name());
        if (job.getError() != null) {
            body.put("error", ReasoningFriendlyErrors.forUser(job.getError()));
        }
        return ResponseEntity.ok(body);
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
        response.put("estimatedWaitTimeMs", queueManager.getEstimatedWaitTimeMs(job));
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
