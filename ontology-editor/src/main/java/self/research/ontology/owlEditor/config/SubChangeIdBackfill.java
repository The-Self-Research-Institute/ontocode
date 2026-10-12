package self.research.ontology.owlEditor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.HistorySyncService;

@Slf4j
@Component
public class SubChangeIdBackfill implements ApplicationRunner {

    private final HistorySyncService historySyncService;

    public SubChangeIdBackfill(HistorySyncService historySyncService) {
        this.historySyncService = historySyncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            long started = System.nanoTime();
            int updated = historySyncService.backfillSubChangeIds();
            if (updated > 0) {
                log.info("[HISTORY] Gave ids to {} older sub-changes in {}ms", updated, (System.nanoTime() - started) / 1_000_000);
            }
        } catch (Exception e) {
            log.warn("[HISTORY] Sub-change id backfill failed (older sub-changes can't be rolled back one by one): {}",
                    e.getMessage());
        }
    }
}
