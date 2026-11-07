package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.upload.UploadStagingSweeper;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class MergeTempProjectSweeper {

    private static final Logger log = LoggerFactory.getLogger(MergeTempProjectSweeper.class);
    static final String PREFIX = "merge-temp-";

    private final StorageManager storageManager;
    private final long ttlHours;

    public MergeTempProjectSweeper(StorageManager storageManager,
                                   @Value("${ontocode.merge.temp-project-ttl-hours:24}") long ttlHours) {
        this.storageManager = storageManager;
        this.ttlHours = Math.max(1, ttlHours);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepAtStartup() {
        int removed = sweep(storageManager.projectsRoot(), ttlHours);
        log.info("[MERGE] Startup sweep removed {} expired merge output folders", removed);
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 60 * 1000)
    public void sweepPeriodically() {
        int removed = sweep(storageManager.projectsRoot(), ttlHours);
        if (removed > 0) {
            log.info("[MERGE] Periodic sweep removed {} expired merge output folders", removed);
        }
    }

    public static int sweep(Path projectsRoot, long ttlHours) {
        return UploadStagingSweeper.sweep(projectsRoot, TimeUnit.HOURS.toMillis(ttlHours),
                child -> child.getFileName().toString().startsWith(PREFIX));
    }

    public static void discard(Path projectsRoot, String projectId) {
        if (projectId == null || !projectId.startsWith(PREFIX)) {
            return;
        }
        UploadStagingSweeper.sweep(projectsRoot, 0, child -> child.getFileName().toString().equals(projectId));
    }
}
