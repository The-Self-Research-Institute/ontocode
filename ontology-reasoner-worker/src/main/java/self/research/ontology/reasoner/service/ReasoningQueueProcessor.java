package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class ReasoningQueueProcessor {

    private final ReasoningQueueManager queueManager;
    private final ReasoningJobExecutor jobExecutor;
    private final Executor reasoningExecutor;
    private final long jobTimeoutMs;

    public ReasoningQueueProcessor(ReasoningQueueManager queueManager,
                                   ReasoningJobExecutor jobExecutor,
                                   @Qualifier("reasoningExecutor") Executor reasoningExecutor,
                                   @Value("${ontocode.reasoning.job-timeout-ms:1800000}") long jobTimeoutMs) {
        this.queueManager = queueManager;
        this.jobExecutor = jobExecutor;
        this.reasoningExecutor = reasoningExecutor;
        this.jobTimeoutMs = jobTimeoutMs;
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
            // Run the actual job on its own thread so we can enforce a hard timeout
            ExecutorService jobThread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "reasoning-job-" + job.getJobId());
                t.setDaemon(true);
                return t;
            });
            try {
                Future<Map<String, Object>> future = jobThread.submit(() -> jobExecutor.execute(job));
                Map<String, Object> result;
                try {
                    result = future.get(jobTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    long minutes = jobTimeoutMs / 60_000;
                    log.error("[Reasoning] Job {} ({}) timed out after {} min — cancelled",
                            job.getJobId(), job.getJobType(), minutes);
                    queueManager.markFailed(job,
                            "Reasoning timed out after " + minutes + " minutes. "
                            + "The ontology may be too complex for the selected reasoner. "
                            + "Try ELK or reduce the ontology scope.");
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof Error err) throw err;
                    throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
                }

                long duration = System.currentTimeMillis() - start;
                if (Boolean.FALSE.equals(result.get("success"))) {
                    String error = result.get("error") != null ? result.get("error").toString() : "Task failed";
                    queueManager.markFailed(job, ReasoningFriendlyErrors.forUser(error));
                } else {
                    queueManager.markCompleted(job, result, duration);
                }
            } catch (Throwable e) {
                log.error("[Reasoning] Job {} failed", job.getJobId(), e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.toLowerCase(Locale.ROOT).contains("outofmemory")) {
                    msg = "OutOfMemoryError during reasoning";
                }
                queueManager.markFailed(job, ReasoningFriendlyErrors.forUser(msg));
            } finally {
                jobThread.shutdownNow();
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
