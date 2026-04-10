package self.research.ontology.owlEditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.GraphDBDatasetService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Validates GraphDB connectivity on application startup
 */
@Component
public class GraphDBHealthCheck {
    
    private static final Logger log = LoggerFactory.getLogger(GraphDBHealthCheck.class);
    
    private final GraphDBDatasetService datasetService;
    
    @Value("${graphdb.url}")
    private String graphdbUrl;
    
    @Value("${graphdb.repository}")
    private String repositoryId;
    
    @Value("${ontocode.data.dir:./data}")
    private String dataDir;
    
    public GraphDBHealthCheck(GraphDBDatasetService datasetService) {
        this.datasetService = datasetService;
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void checkGraphDBConnection() {
        log.info("========================================");
        log.info("Checking GraphDB connectivity...");
        log.info("GraphDB URL: {}", graphdbUrl);
        log.info("Repository: {}", repositoryId);
        log.info("========================================");
        
        try {
            // Try to initialize connection
            datasetService.init();
            log.info("✓ GraphDB connection successful!");
            log.info("✓ Repository '{}' is accessible", repositoryId);
            
        } catch (Exception e) {
            log.error("========================================");
            log.error("✗ GraphDB connection FAILED!");
            log.error("========================================");
            log.error("");
            log.error("SETUP REQUIRED:");
            log.error("");
            log.error("1. Start GraphDB:");
            log.error("   - Download from: https://www.ontotext.com/products/graphdb/download/");
            log.error("   - Or run: docker run -d -p 7200:7200 ontotext/graphdb:10.7.0-free");
            log.error("");
            log.error("2. Create Repository:");
            log.error("   - Open GraphDB Workbench: {}/webapi", graphdbUrl);
            log.error("   - Navigate to: Setup → Repositories");
            log.error("   - Click: Create new repository");
            log.error("   - Set Repository ID: {}", repositoryId);
            log.error("   - Set Ruleset: OWL2-RL (Optimized)");
            log.error("   - Click: Create");
            log.error("");
            log.error("3. Verify Setup:");
            log.error("   - Check repositories: {}/rest/repositories", graphdbUrl);
            log.error("   - Should list: '{}'", repositoryId);
            log.error("");
            log.error("For detailed instructions, see: GRAPHDB_SETUP.md");
            log.error("========================================");
            log.warn("Application will continue but GraphDB operations will FAIL until setup is complete.");
        }
        
        log.info("========================================");
        
        cleanupStaleTempFiles();
    }
    
    /**
     * Remove stale multipart temp files older than 1 hour.
     * Tomcat writes upload data here and failed/interrupted requests can leave orphaned files.
     */
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
            long[] counts = {0, 0}; // [deleted, totalSize]
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
