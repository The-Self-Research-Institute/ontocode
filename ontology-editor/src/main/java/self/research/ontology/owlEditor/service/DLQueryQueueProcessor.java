package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DLQueryJob;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.Locale;

@Slf4j
@Service
public class DLQueryQueueProcessor {

    private final DLQueryQueueManager queueManager;
    private final DLQueryService dlQueryService;
    private final Executor dlQueryExecutor;

    public DLQueryQueueProcessor(DLQueryQueueManager queueManager,
                                 DLQueryService dlQueryService,
                                 @Qualifier("dlQueryExecutor") Executor dlQueryExecutor) {
        this.queueManager = queueManager;
        this.dlQueryService = dlQueryService;
        this.dlQueryExecutor = dlQueryExecutor;
    }

    public void processNext() {
        if (!queueManager.canProcess()) {
            return;
        }

        dlQueryExecutor.execute(() -> {
            DLQueryJob job = queueManager.dequeue();
            if (job == null) {
                return;
            }

            long start = System.currentTimeMillis();
            try {
                Map<String, Object> result = dlQueryService.executeQuery(
                        job.getProjectId(),
                        job.getExpression(),
                        job.getQueryTypes());
                long duration = System.currentTimeMillis() - start;
                if (Boolean.FALSE.equals(result.get("success"))) {
                    String error = result.get("error") != null ? result.get("error").toString() : "DL Query failed";
                    queueManager.markFailed(job, error);
                } else {
                    queueManager.markCompleted(job, result, duration);
                }
            } catch (Exception e) {
                log.error("[DLQuery] Job {} failed", job.getJobId(), e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.toLowerCase(Locale.ROOT).contains("outofmemory")) {
                    msg = DLQueryQueueManager.userFriendlyError(msg);
                }
                queueManager.markFailed(job, msg);
            } finally {
                processNext();
            }
        });
    }

    /** Retry when jobs were deferred due to heap pressure or import overlap. */
    @Scheduled(fixedDelayString = "${ontocode.dlquery.retry-delay-ms:15000}")
    public void retryDeferredJobs() {
        if (queueManager.hasQueuedJobs() && queueManager.canProcess()) {
            log.debug("[DLQuery] Retrying deferred queue after capacity became available");
            processNext();
        }
    }
}
