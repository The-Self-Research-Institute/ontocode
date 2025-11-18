package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing GraphDB repositories.
 * Provides SPARQL query/update and bulk loading capabilities for large ontologies.
 * 
 * Each project gets its own named graph in GraphDB repository.
 */
@Service
public class GraphDBDatasetService {
    
    private static final Logger log = LoggerFactory.getLogger(GraphDBDatasetService.class);
    
    @Value("${graphdb.url:http://localhost:7200}")
    private String graphdbUrl;
    
    @Value("${graphdb.repository:ontocode}")
    private String repositoryId;
    
    @Value("${ontocode.data.dir:./data}")
    private String dataDir;
    
    // Shared repository connection
    private Repository repository;
    
    // Cache of graph URIs per project (projectId -> graphUri)
    private final Map<String, String> graphUriCache = new ConcurrentHashMap<>();
    
    /**
     * Initialize GraphDB repository connection
     */
    public void init() {
        if (repository == null) {
            log.info("Initializing GraphDB repository connection: {} / {}", graphdbUrl, repositoryId);
            try {
                repository = new HTTPRepository(graphdbUrl, repositoryId);
                repository.init();
                
                // Test connection
                try (RepositoryConnection conn = repository.getConnection()) {
                    log.info("Successfully connected to GraphDB repository at {}", graphdbUrl);
                }
            } catch (Exception e) {
                log.error("Failed to connect to GraphDB at {} with repository '{}'", graphdbUrl, repositoryId, e);
                log.error("Please ensure:");
                log.error("  1. GraphDB is running on {}", graphdbUrl);
                log.error("  2. Repository '{}' exists in GraphDB", repositoryId);
                log.error("  3. You can access GraphDB Workbench at {}/webapi", graphdbUrl);
                throw new RuntimeException("GraphDB connection failed: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Get repository instance (lazy init)
     */
    public Repository getRepository() {
        if (repository == null) {
            init();
        }
        return repository;
    }
    
    /**
     * Get graph URI for a project
     */
    public String getGraphUri(String projectId) {
        return graphUriCache.computeIfAbsent(projectId, 
            id -> "http://ontocode.org/project/" + id);
    }
    
    /**
     * Get the project directory path
     */
    public Path getProjectPath(String projectId) {
        return Paths.get(dataDir, "projects", projectId);
    }
    
    /**
     * Execute a SPARQL SELECT query
     */
    public TupleQueryResult execSelect(String projectId, String sparqlQuery) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try {
            RepositoryConnection conn = repo.getConnection();
            
            // Inject FROM clause if not present
            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE", 
                    "FROM <" + graphUri + "> WHERE");
            }
            
            log.debug("Executing SELECT query for project: {}", projectId);
            TupleQuery query = conn.prepareTupleQuery(sparqlQuery);
            return query.evaluate();
            
        } catch (Exception e) {
            log.error("SPARQL SELECT query failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL query execution failed", e);
        }
    }
    
    /**
     * Execute a SPARQL CONSTRUCT query
     */
    public GraphQueryResult execConstruct(String projectId, String sparqlQuery) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try {
            RepositoryConnection conn = repo.getConnection();
            
            // Inject FROM clause if not present
            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE", 
                    "FROM <" + graphUri + "> WHERE");
            }
            
            log.debug("Executing CONSTRUCT query for project: {}", projectId);
            GraphQuery query = conn.prepareGraphQuery(sparqlQuery);
            return query.evaluate();
            
        } catch (Exception e) {
            log.error("SPARQL CONSTRUCT query failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL CONSTRUCT execution failed", e);
        }
    }
    
    /**
     * Execute a SPARQL ASK query
     */
    public boolean execAsk(String projectId, String sparqlQuery) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            // Inject FROM clause if not present
            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE", 
                    "FROM <" + graphUri + "> WHERE");
            }
            
            log.debug("Executing ASK query for project: {}", projectId);
            BooleanQuery query = conn.prepareBooleanQuery(sparqlQuery);
            return query.evaluate();
            
        } catch (Exception e) {
            log.error("SPARQL ASK query failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL ASK execution failed", e);
        }
    }
    
    /**
     * Execute a SPARQL UPDATE operation
     */
    public void execUpdate(String projectId, String sparqlUpdate) {
        Repository repo = getRepository();
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            log.debug("Executing UPDATE for project: {}", projectId);
            Update update = conn.prepareUpdate(sparqlUpdate);
            update.execute();
            
            log.debug("SPARQL UPDATE executed successfully for project: {}", projectId);
            
        } catch (Exception e) {
            log.error("SPARQL UPDATE failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL UPDATE execution failed", e);
        }
    }
    
    /**
     * Bulk load RDF data from input stream into GraphDB
     * Supports: RDF/XML, Turtle, N-Triples, JSON-LD
     */
    public void bulkLoad(String projectId, InputStream inputStream, RDFFormat rdfFormat) {
        try {
            Repository repo = getRepository();
            String graphUri = getGraphUri(projectId);
            
            log.info("Starting bulk load for project: {} with format: {}", projectId, rdfFormat);
            
            try (RepositoryConnection conn = repo.getConnection()) {
                
                // Clear existing data for this project
                conn.clear(conn.getValueFactory().createIRI(graphUri));
                
                // Load new data into named graph
                conn.add(inputStream, graphUri, rdfFormat, 
                        conn.getValueFactory().createIRI(graphUri));
                
                // Get size after loading
                long tripleCount = conn.size(conn.getValueFactory().createIRI(graphUri));
                
                log.info("Bulk load completed for project: {} - loaded {} triples", projectId, tripleCount);
            }
            
        } catch (org.eclipse.rdf4j.repository.RepositoryException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("not found")) {
                log.error("GraphDB repository '{}' not found at {}", repositoryId, graphdbUrl);
                log.error("Please create the repository via GraphDB Workbench: {}/repository", graphdbUrl);
                throw new RuntimeException("GraphDB repository '" + repositoryId + "' not found. Please create it first.", e);
            } else {
                log.error("Bulk load failed for project: {}", projectId, e);
                throw new RuntimeException("Bulk load failed: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("Unexpected error during bulk load for project: {}", projectId, e);
            throw new RuntimeException("Bulk load failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Clear all data for a project
     */
    public void clearDataset(String projectId) {
        try {
            Repository repo = getRepository();
            String graphUri = getGraphUri(projectId);
            
            log.info("Clearing dataset for project: {} (graph: {})", projectId, graphUri);
            
            try (RepositoryConnection conn = repo.getConnection()) {
                // Clear specific named graph
                conn.clear(conn.getValueFactory().createIRI(graphUri));
                log.info("Dataset cleared for project: {}", projectId);
            }
            
        } catch (org.eclipse.rdf4j.repository.RepositoryException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("not found")) {
                log.error("GraphDB repository not found. Please ensure:");
                log.error("  1. GraphDB is running: {}", graphdbUrl);
                log.error("  2. Repository '{}' exists", repositoryId);
                log.error("  3. Create repository via GraphDB Workbench: {}/repository", graphdbUrl);
                throw new RuntimeException("GraphDB repository '" + repositoryId + "' not found at " + graphdbUrl + ". Please create it first.", e);
            } else {
                log.error("Failed to clear dataset for project: {}", projectId, e);
                throw new RuntimeException("Failed to clear dataset: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("Unexpected error clearing dataset for project: {}", projectId, e);
            throw new RuntimeException("Failed to clear dataset: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get prefix mappings from the dataset
     */
    public Map<String, String> getPrefixes(String projectId) {
        Map<String, String> prefixes = new HashMap<>();
        
        try (RepositoryConnection conn = getRepository().getConnection()) {
            
            // GraphDB typically stores prefixes in the repository namespace
            for (org.eclipse.rdf4j.model.Namespace ns : conn.getNamespaces()) {
                prefixes.put(ns.getPrefix(), ns.getName());
            }
            
        } catch (Exception e) {
            log.error("Failed to get prefixes for project: {}", projectId, e);
        }
        
        return prefixes;
    }
    
    /**
     * Set prefix mappings in the dataset
     */
    public void setPrefixes(String projectId, Map<String, String> prefixes) {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            
            // Add prefix mappings to repository
            for (Map.Entry<String, String> entry : prefixes.entrySet()) {
                conn.setNamespace(entry.getKey(), entry.getValue());
            }
            
            log.debug("Set {} prefixes for project: {}", prefixes.size(), projectId);
            
        } catch (Exception e) {
            log.error("Failed to set prefixes for project: {}", projectId, e);
            throw new RuntimeException("Failed to set prefixes", e);
        }
    }
    
    /**
     * Get dataset size (triple count) for a project
     */
    public long getDatasetSize(String projectId) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            return conn.size(conn.getValueFactory().createIRI(graphUri));
        } catch (Exception e) {
            log.error("Failed to get dataset size for project: {}", projectId, e);
            return 0;
        }
    }
    
    /**
     * Check if dataset exists for a project
     */
    public boolean datasetExists(String projectId) {
        return getDatasetSize(projectId) > 0;
    }
    
    /**
     * Export dataset as RDF string
     */
    public String exportDataset(String projectId, RDFFormat format) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            StringWriter writer = new StringWriter();
            conn.export(Rio.createWriter(format, writer), 
                       conn.getValueFactory().createIRI(graphUri));
            
            return writer.toString();
            
        } catch (Exception e) {
            log.error("Failed to export dataset for project: {}", projectId, e);
            throw new RuntimeException("Failed to export dataset", e);
        }
    }
    
    /**
     * Create a connection to the repository
     * Note: Caller is responsible for closing the connection
     */
    public RepositoryConnection getConnection() {
        return getRepository().getConnection();
    }
    
    /**
     * Execute custom SPARQL query with connection
     */
    public TupleQueryResult executeQuery(RepositoryConnection conn, String projectId, String sparqlQuery) {
        String graphUri = getGraphUri(projectId);
        
        // Inject FROM clause if not present
        if (!sparqlQuery.toUpperCase().contains("FROM")) {
            sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE", 
                "FROM <" + graphUri + "> WHERE");
        }
        
        TupleQuery query = conn.prepareTupleQuery(sparqlQuery);
        return query.evaluate();
    }
    
    /**
     * Shutdown repository connections
     */
    @PreDestroy
    public void shutdown() {
        if (repository != null && repository.isInitialized()) {
            log.info("Shutting down GraphDB repository connection");
            repository.shutDown();
            log.info("GraphDB repository connection closed");
        }
    }
}
