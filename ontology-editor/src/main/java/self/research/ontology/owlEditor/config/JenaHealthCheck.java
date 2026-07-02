package self.research.ontology.owlEditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class JenaHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(JenaHealthCheck.class);

    private final SparqlDatasetService datasetService;

    @Value("${ontocode.fuseki.queryEndpoint:http://localhost:3030/ontocode/query}")
    private String fusekiQueryEndpoint;

    @Value("${ontocode.data.dir:./data}")
    private String dataDir;

    public JenaHealthCheck(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkFusekiConnection() {
        log.info("========================================");
        log.info("Checking Fuseki connectivity...");
        log.info("Fuseki query endpoint: {}", fusekiQueryEndpoint);
        log.info("========================================");

        try {
            datasetService.init();
            log.info("✓ Fuseki connection successful!");

        } catch (Exception e) {
            log.error("========================================");
            log.error("✗ Fuseki connection FAILED!");
            log.error("========================================");
            log.error("");
            log.error("SETUP REQUIRED:");
            log.error("");
            log.error("1. Start Fuseki via Docker:");
            log.error("   docker compose up fuseki");
            log.error("");
            log.error("2. Verify dataset is available:");
            log.error("   curl http://localhost:3030/$/datasets");
            log.error("");
            log.error("========================================");
            log.warn("Application will continue but SPARQL operations will FAIL until Fuseki is running.");
        }

        log.info("========================================");

        cleanupStaleTempFiles();
    }

    private void cleanupStaleTempFiles() {
        Path tmpDir = Paths.get(dataDir, "tmp");
        if (!Files.isDirectory(tmpDir)) {
            try {
                Files.createDirectories(tmpDir);
                log.info("Created multipart temp directory: {}", tmpDir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("Could not create temp directory {}: {}", tmpDir, e.getMessage());
            }
            return;
        }

        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        try (var stream = Files.list(tmpDir)) {
            long[] counts = {0, 0};
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    if (modified.isBefore(cutoff)) {
                        long size = Files.size(file);
                        Files.deleteIfExists(file);
                        counts[0]++;
                        counts[1] += size;
                    }
                } catch (IOException ignored) {}
            });
            if (counts[0] > 0) {
                log.info("Cleaned up {} stale temp files ({} MB)", counts[0], counts[1] / (1024 * 1024));
            }
        } catch (IOException e) {
            log.warn("Could not clean temp directory {}: {}", tmpDir, e.getMessage());
        }
    }
}
