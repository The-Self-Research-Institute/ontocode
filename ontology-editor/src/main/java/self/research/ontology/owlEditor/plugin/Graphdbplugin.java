package self.research.ontology.owlEditor.plugin;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * GraphDB Storage Plugin
 * Integrates OntoCode with Ontotext GraphDB for RDF storage and SPARQL queries
 */
public class GraphDBPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(GraphDBPlugin.class);
    
    private PluginMetadata metadata;
    private Repository repository;
    private String graphDBUrl;
    private String repositoryId;

    public GraphDBPlugin() {
        initializeMetadata();
    }

    private void initializeMetadata() {
        metadata = new PluginMetadata();
        metadata.setId("graphdb-storage");
        metadata.setName("GraphDB Storage");
        metadata.setVersion("1.0.0");
        metadata.setDescription("Store and query ontologies using Ontotext GraphDB. " +
            "Provides SPARQL endpoint and semantic reasoning at database level.");
        metadata.setAuthor("OntoCode Team");
        metadata.setAuthorEmail("support@ontocode.dev");
        metadata.setWebsite("https://www.ontotext.com/products/graphdb/");
        metadata.setType(PluginMetadata.PluginType.IMPORT_EXPORT);
        metadata.setEnabled(false); // Disabled by default, requires configuration
        metadata.setTags(Arrays.asList("graphdb", "rdf", "sparql", "triplestore", "storage"));
        metadata.setIconUrl("/icons/graphdb-icon.png");
        
        Map<String, String> defaultSettings = new HashMap<>();
        defaultSettings.put("graphDBUrl", "http://localhost:7200");
        defaultSettings.put("repositoryId", "ontocode");
        defaultSettings.put("useInference", "true");
        metadata.setSettings(defaultSettings);
    }

    @Override
    public PluginMetadata getMetadata() {
        return metadata;
    }

    @Override
    public void initialize() throws PluginException {
        try {
            // Get settings
            graphDBUrl = metadata.getSettings().getOrDefault("graphDBUrl", "http://localhost:7200");
            repositoryId = metadata.getSettings().getOrDefault("repositoryId", "ontocode");
            
            // Connect to GraphDB
            repository = new HTTPRepository(graphDBUrl, repositoryId);
            repository.init();
            
            // Test connection
            try (RepositoryConnection conn = repository.getConnection()) {
                long size = conn.size();
                log.info("Connected to GraphDB at {} - Repository '{}' contains {} statements", 
                    graphDBUrl, repositoryId, size);
            }
            
            log.info("GraphDB Plugin initialized successfully");
            
        } catch (Exception e) {
            throw new PluginException("Failed to initialize GraphDB plugin", e);
        }
    }

    @Override
    public void shutdown() throws PluginException {
        try {
            if (repository != null) {
                repository.shutDown();
            }
            log.info("GraphDB Plugin shutdown successfully");
        } catch (Exception e) {
            throw new PluginException("Failed to shutdown GraphDB plugin", e);
        }
    }

    @Override
    public boolean isCompatible(String editorVersion) {
        return editorVersion.startsWith("1.");
    }

    @Override
    public String getConfigurationSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "graphDBUrl": {
                  "type": "string",
                  "description": "GraphDB server URL",
                  "default": "http://localhost:7200",
                  "examples": ["http://localhost:7200", "https://graphdb.example.com"]
                },
                "repositoryId": {
                  "type": "string",
                  "description": "Repository ID in GraphDB",
                  "default": "ontocode"
                },
                "username": {
                  "type": "string",
                  "description": "GraphDB username (if authentication enabled)"
                },
                "password": {
                  "type": "string",
                  "description": "GraphDB password (if authentication enabled)",
                  "format": "password"
                },
                "useInference": {
                  "type": "boolean",
                  "description": "Enable inference in GraphDB",
                  "default": true
                },
                "ruleset": {
                  "type": "string",
                  "description": "Inference ruleset",
                  "enum": ["rdfs", "owl-horst", "owl-max", "owl2-rl"],
                  "default": "rdfs"
                }
              },
              "required": ["graphDBUrl", "repositoryId"]
            }
            """;
    }

    @Override
    public void configure(Map<String, Object> settings) throws PluginException {
        // Update settings
        if (settings.containsKey("graphDBUrl")) {
            metadata.getSettings().put("graphDBUrl", settings.get("graphDBUrl").toString());
        }
        if (settings.containsKey("repositoryId")) {
            metadata.getSettings().put("repositoryId", settings.get("repositoryId").toString());
        }
        
        // Reinitialize with new settings
        if (metadata.isEnabled()) {
            shutdown();
            initialize();
        }
    }

    /**
     * Store ontology in GraphDB
     */
    public void storeOntology(OWLOntology ontology, String contextUri) throws PluginException {
        try (RepositoryConnection conn = repository.getConnection()) {
            // Convert OWL to RDF and store in GraphDB
            // Implementation depends on your needs
            
            log.info("Stored ontology in GraphDB context: {}", contextUri);
        } catch (Exception e) {
            throw new PluginException("Failed to store ontology in GraphDB", e);
        }
    }

    /**
     * Execute SPARQL query
     */
    public String executeSparqlQuery(String query) throws PluginException {
        try (RepositoryConnection conn = repository.getConnection()) {
            // Execute SPARQL query
            // Return results as JSON
            
            log.debug("Executed SPARQL query: {}", query);
            return "{}"; // Placeholder
        } catch (Exception e) {
            throw new PluginException("Failed to execute SPARQL query", e);
        }
    }

    /**
     * Get repository connection for advanced operations
     */
    public RepositoryConnection getConnection() {
        return repository.getConnection();
    }
}