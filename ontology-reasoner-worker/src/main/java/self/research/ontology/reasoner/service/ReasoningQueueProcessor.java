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
    private final long runawayThreadMaxWaitMs;

    public ReasoningQueueProcessor(ReasoningQueueManager queueManager,
                                   ReasoningJobExecutor jobExecutor,
                                   @Qualifier("reasoningExecutor") Executor reasoningExecutor,
                                   @Value("${ontocode.reasoning.job-timeout-ms:1800000}") long jobTimeoutMs,
                                   @Value("${ontocode.reasoning.runaway-thread-max-wait-ms:600000}") long runawayThreadMaxWaitMs) {
        this.queueManager = queueManager;
        this.jobExecutor = jobExecutor;
        this.reasoningExecutor = reasoningExecutor;
        this.jobTimeoutMs = jobTimeoutMs;
        this.runawayThreadMaxWaitMs = runawayThreadMaxWaitMs;
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

            Thread[] capturedThread = new Thread[1];
            ExecutorService jobThread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "reasoning-job-" + job.getJobId());
                t.setDaemon(true);
                capturedThread[0] = t;
                return t;
            });
            try {
                Future<Map<String, Object>> future = jobThread.submit(() -> jobExecutor.execute(job));
                Map<String, Object> result;
                try {
                    result = future.get(jobTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (capturedThread[0] != null) {
                        OntologySessionService.interruptReasonerOnThread(capturedThread[0].threadId());
                    }
                    future.cancel(true);
                    long minutes = jobTimeoutMs / 60_000;
                    log.error("[Reasoning] Job {} ({}) timed out after {} min — cancelled",
                            job.getJobId(), job.getJobType(), minutes);
                    queueManager.reportTimedOut(job,
                            "Reasoning timed out after " + minutes + " minutes. "
                            + "The ontology may be too complex for the selected reasoner. "
                            + "Try ELK or reduce the ontology scope.");
                    awaitRunawayThreadThenReleaseSlot(job, jobThread);
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

    private void awaitRunawayThreadThenReleaseSlot(ReasoningJob job, ExecutorService jobThread) {
        Thread watcher = new Thread(() -> {
            jobThread.shutdownNow();
            boolean stopped = false;
            try {
                stopped = jobThread.awaitTermination(runawayThreadMaxWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (stopped) {
                log.info("[Reasoning] Runaway thread for timed-out job {} has stopped, releasing its slot", job.getJobId());
            } else {
                log.warn("[Reasoning] Runaway thread for timed-out job {} did not stop within {} ms — "
                        + "releasing its slot anyway", job.getJobId(), runawayThreadMaxWaitMs);
            }
            queueManager.releaseAbandonedSlot(job.getJobId());
            processNext();
        }, "reasoning-reaper-" + job.getJobId());
        watcher.setDaemon(true);
        watcher.start();
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoning.retry-delay-ms:15000}")
    public void retryDeferredJobs() {
        if (queueManager.hasQueuedJobs() && queueManager.canProcess()) {
            processNext();
        }
    }
}
