package self.research.ontology.owlEditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.GraphDBDatasetService;

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
    }
}
