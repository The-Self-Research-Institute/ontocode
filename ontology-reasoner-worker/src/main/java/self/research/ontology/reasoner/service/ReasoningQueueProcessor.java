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

    private static final long RUNAWAY_RECHECK_MS = 30_000;

    private final ReasoningQueueManager queueManager;
    private final ReasoningJobExecutor jobExecutor;
    private final Executor reasoningExecutor;
    private final RunawayReasoningHealthIndicator runawayHealth;
    private final long jobTimeoutMs;
    private final long runawayThreadMaxWaitMs;
    private final Map<String, RunningJob> runningJobs = new ConcurrentHashMap<>();

    private record RunningJob(Thread thread, Future<?> future) {}

    public ReasoningQueueProcessor(ReasoningQueueManager queueManager,
                                   ReasoningJobExecutor jobExecutor,
                                   @Qualifier("reasoningExecutor") Executor reasoningExecutor,
                                   RunawayReasoningHealthIndicator runawayHealth,
                                   @Value("${ontocode.reasoning.job-timeout-ms:1800000}") long jobTimeoutMs,
                                   @Value("${ontocode.reasoning.runaway-thread-max-wait-ms:600000}") long runawayThreadMaxWaitMs) {
        this.queueManager = queueManager;
        this.jobExecutor = jobExecutor;
        this.reasoningExecutor = reasoningExecutor;
        this.runawayHealth = runawayHealth;
        this.jobTimeoutMs = jobTimeoutMs;
        this.runawayThreadMaxWaitMs = runawayThreadMaxWaitMs;
    }

    public void processNext() {
        if (!queueManager.canProcess()) {
            return;
        }
        reasoningExecutor.execute(this::runNextJob);
    }

    private void runNextJob() {
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
        boolean handedToReaper = false;
        try {
            Future<Map<String, Object>> future = jobThread.submit(() -> jobExecutor.execute(job));
            jobThread.shutdown();
            runningJobs.put(job.getJobId(), new RunningJob(capturedThread[0], future));
            if (!queueManager.isStillProcessing(job)) {
                future.cancel(true);
                interruptJobThread(capturedThread[0]);
            }
            Map<String, Object> result;
            try {
                result = future.get(jobTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                interruptJobThread(capturedThread[0]);
                long minutes = jobTimeoutMs / 60_000;
                log.error("[Reasoning] Job {} ({}) timed out after {} min — cancelled",
                        job.getJobId(), job.getJobType(), minutes);
                queueManager.reportTimedOut(job,
                        "Reasoning timed out after " + minutes + " minutes. "
                        + "The ontology may be too complex for the selected reasoner. "
                        + "Try ELK or reduce the ontology scope.");
                awaitRunawayThreadThenReleaseSlot(job, jobThread, capturedThread[0]);
                handedToReaper = true;
                return;
            } catch (CancellationException e) {
                log.info("[Reasoning] Job {} ({}) was cancelled while running", job.getJobId(), job.getJobType());
                awaitRunawayThreadThenReleaseSlot(job, jobThread, capturedThread[0]);
                handedToReaper = true;
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
            if (!handedToReaper) {
                runningJobs.remove(job.getJobId());
                jobThread.shutdownNow();
            }
            processNext();
        }
    }

    public ReasoningQueueManager.CancelResult cancel(String jobId) {
        ReasoningQueueManager.CancelResult result = queueManager.cancel(jobId);
        if (result.outcome() == ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING) {
            RunningJob running = runningJobs.get(jobId);
            if (running != null) {
                running.future().cancel(true);
                interruptJobThread(running.thread());
            }
        }
        return result;
    }

    private static void interruptJobThread(Thread thread) {
        if (thread == null) {
            return;
        }
        OntologySessionService.interruptReasonerOnThread(thread.threadId());
        thread.interrupt();
    }

    private void awaitRunawayThreadThenReleaseSlot(ReasoningJob job, ExecutorService jobThread, Thread thread) {
        String jobId = job.getJobId();
        Thread watcher = new Thread(() -> {
            boolean flagged = false;
            try {
                jobThread.shutdownNow();
                if (!jobThread.awaitTermination(runawayThreadMaxWaitMs, TimeUnit.MILLISECONDS)) {
                    flagged = true;
                    runawayHealth.markStuck(jobId);
                    log.error("[Reasoning] Thread for job {} is still running {} ms after it was told to stop — "
                            + "keeping its slot and reporting the worker as unhealthy until it ends",
                            jobId, runawayThreadMaxWaitMs);
                    while (!jobThread.awaitTermination(RUNAWAY_RECHECK_MS, TimeUnit.MILLISECONDS)) {
                        interruptJobThread(thread);
                    }
                }
                log.info("[Reasoning] Thread for stopped job {} has ended, releasing its slot", jobId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                if (flagged) {
                    runawayHealth.clear(jobId);
                }
            }
            runningJobs.remove(jobId);
            queueManager.releaseAbandonedSlot(jobId);
            processNext();
        }, "reasoning-reaper-" + jobId);
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
