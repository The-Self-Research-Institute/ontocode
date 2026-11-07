package self.research.ontology.owlEditor.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Component
public class UploadStagingSweeper {

    private static final Logger log = LoggerFactory.getLogger(UploadStagingSweeper.class);

    private final UploadStagingStats stats;
    private final UploadSettings settings;

    public UploadStagingSweeper(UploadStagingStats stats, UploadSettings settings) {
        this.stats = stats;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepAtStartup() {
        int removed = sweep(stats.tmpDir(), settings.staleAgeMs()) + sweep(stats.chunkDir(), settings.staleAgeMs());
        log.info("[Upload] Startup sweep removed {} stale staging entries", removed);
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void sweepPeriodically() {
        int removed = sweep(stats.tmpDir(), settings.staleAgeMs());
        if (removed > 0) {
            log.info("[Upload] Periodic sweep removed {} stale temp entries", removed);
        }
    }

    public static int sweep(Path root, long maxAgeMs) {
        return sweep(root, maxAgeMs, child -> true);
    }

    public static int sweep(Path root, long maxAgeMs, Predicate<Path> eligible) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        long now = System.currentTimeMillis();
        List<Path> children;
        try (Stream<Path> list = Files.list(root)) {
            children = list.toList();
        } catch (IOException e) {
            log.warn("[Upload] Cannot list {}: {}", root, e.getMessage());
            return 0;
        }
        for (Path child : children) {
            if (!eligible.test(child)) {
                continue;
            }
            try {
                if (newestModified(child) > now - maxAgeMs) {
                    continue;
                }
                deleteTree(child);
                removed++;
            } catch (IOException e) {
                log.warn("[Upload] Could not sweep {}: {}", child, e.getMessage());
            }
        }
        return removed;
    }

    private static long newestModified(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return Files.getLastModifiedTime(path).toMillis();
        }
        long newest = Files.getLastModifiedTime(path).toMillis();
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
            }
        }
        return newest;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
