package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningHeapMonitor;

@Slf4j
@Service
public class ReasoningMemoryJanitor {

    private final ReasoningQueueManager queueManager;
    private final ReasoningResultRetention retention;

    @Value("${ontocode.reasoning.suggest-gc-on-pressure:true}")
    private boolean suggestGcOnPressure;

    public ReasoningMemoryJanitor(ReasoningQueueManager queueManager,
                                  ReasoningResultRetention retention) {
        this.queueManager = queueManager;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoning.memory-janitor-interval-ms:60000}")
    public void runRetentionPass() {
        if (queueManager.activeJobCount() > 0) {
            return;
        }

        long before = ReasoningHeapMonitor.usedMb();
        int evicted = retention.evictStaleIdleEntries();
        if (evicted == 0) {
            return;
        }

        long after = ReasoningHeapMonitor.usedMb();
        log.info("[ReasoningMemory] Stale-idle pass evicted {} result(s), heap {} MB → {} MB ({}% used)",
                evicted, before, after, Math.round(ReasoningHeapMonitor.usedRatio() * 100));

        if (suggestGcOnPressure && ReasoningHeapMonitor.usedRatio() >= 0.70) {
            System.gc();
        }
    }
}
