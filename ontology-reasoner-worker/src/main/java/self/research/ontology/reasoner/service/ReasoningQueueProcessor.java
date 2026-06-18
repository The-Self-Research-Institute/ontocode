package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ReasoningQueueProcessor {

    private final ReasoningQueueManager queueManager;
    private final ReasoningJobExecutor jobExecutor;
    private final Executor reasoningExecutor;

    public ReasoningQueueProcessor(ReasoningQueueManager queueManager,
                                   ReasoningJobExecutor jobExecutor,
                                   @Qualifier("reasoningExecutor") Executor reasoningExecutor) {
        this.queueManager = queueManager;
        this.jobExecutor = jobExecutor;
        this.reasoningExecutor = reasoningExecutor;
    }

    public void processNext() {
        if (!queueManager.canProcess()) {
            return;
        }

        reasoningExecutor.execute(() -> {
            ReasoningJob job = queueManager.dequeue();
            if (job == null) {
                return;
            }

            long start = System.currentTimeMillis();
            try {
                Map<String, Object> result = jobExecutor.execute(job);
                long duration = System.currentTimeMillis() - start;
                if (Boolean.FALSE.equals(result.get("success"))) {
                    String error = result.get("error") != null ? result.get("error").toString() : "Task failed";
                    queueManager.markFailed(job, ReasoningFriendlyErrors.forUser(error));
                } else {
                    queueManager.markCompleted(job, result, duration);
                }
            } catch (Exception e) {
                log.error("[Reasoning] Job {} failed", job.getJobId(), e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.toLowerCase(Locale.ROOT).contains("outofmemory")) {
                    msg = "OutOfMemoryError during reasoning";
                }
                queueManager.markFailed(job, ReasoningFriendlyErrors.forUser(msg));
            } finally {
                processNext();
            }
        });
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoning.retry-delay-ms:15000}")
    public void retryDeferredJobs() {
        if (queueManager.hasQueuedJobs() && queueManager.canProcess()) {
            processNext();
        }
    }
}
