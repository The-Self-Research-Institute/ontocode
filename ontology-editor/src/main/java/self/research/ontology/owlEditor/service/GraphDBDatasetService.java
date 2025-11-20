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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
     * Execute a SPARQL SELECT query and return materialized results
     */
    public TupleQueryResult execSelect(String projectId, String sparqlQuery) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            // Inject FROM clause if not present
            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE", 
                    "FROM <" + graphUri + "> WHERE");
            }
            
            System.out.println("=== EXECUTING QUERY FOR PROJECT: " + projectId + " ===");
            System.out.println("Graph URI: " + graphUri);
            System.out.println("Query: " + sparqlQuery);
            log.debug("Executing SELECT query for project: {}", projectId);
            TupleQuery query = conn.prepareTupleQuery(sparqlQuery);
            
            // Materialize results into a list before closing connection
            List<BindingSet> results = new ArrayList<>();
            List<String> bindingNames = new ArrayList<>();
            try (TupleQueryResult result = query.evaluate()) {
                bindingNames.addAll(result.getBindingNames());
                while (result.hasNext()) {
                    results.add(result.next());
                }
            }
            System.out.println("=== QUERY EXECUTED, GOT " + results.size() + " RESULTS ===");
            
            // Return a simple iterator-based implementation
            return new SimpleTupleQueryResult(bindingNames, results);
            
        } catch (Exception e) {
            log.error("SPARQL SELECT query failed for project: {}", projectId, e);
            throw new RuntimeException("SPARQL query execution failed", e);
        }
    }
    
    /**
     * Simple in-memory TupleQueryResult implementation
     */
    private static class SimpleTupleQueryResult implements TupleQueryResult {
        private final List<String> bindingNames;
        private final List<BindingSet> bindings;
        private int currentIndex = -1;
        
        public SimpleTupleQueryResult(List<String> bindingNames, List<BindingSet> bindings) {
            this.bindingNames = bindingNames;
            this.bindings = bindings;
        }
        
        @Override
        public List<String> getBindingNames() {
            return bindingNames;
        }
        
        @Override
        public void close() {
            // No-op, already materialized
        }
        
        @Override
        public boolean hasNext() {
            return currentIndex < bindings.size() - 1;
        }
        
        @Override
        public BindingSet next() {
            currentIndex++;
            return bindings.get(currentIndex);
        }
        
        @Override
        public void remove() {
            throw new UnsupportedOperationException();
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
            
            // Read all bytes
            byte[] data = inputStream.readAllBytes();
            
            // Skip BOM if present
            int startIndex = 0;
            java.nio.charset.Charset charset = java.nio.charset.StandardCharsets.UTF_8;
            
            if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) {
                startIndex = 3; // Skip UTF-8 BOM
                charset = java.nio.charset.StandardCharsets.UTF_8;
                log.info("UTF-8 BOM detected");
            } else if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF) {
                startIndex = 2; // Skip UTF-16 BE BOM
                charset = java.nio.charset.StandardCharsets.UTF_16BE;
                log.info("UTF-16 BE BOM detected");
            } else if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE) {
                startIndex = 2; // Skip UTF-16 LE BOM
                charset = java.nio.charset.StandardCharsets.UTF_16LE;
                log.info("UTF-16 LE BOM detected");
            } else {
                // No BOM - try to detect encoding from first bytes or assume ISO-8859-1/Windows-1252
                // These encodings are supersets of ASCII and won't fail on any byte value
                charset = java.nio.charset.StandardCharsets.ISO_8859_1;
                log.info("No BOM detected, using ISO-8859-1 for reading");
            }
            
            // Convert bytes to String using detected/assumed charset, then back to UTF-8 bytes
            String content = new String(data, startIndex, data.length - startIndex, charset);
            
            // Remove any leading whitespace before XML declaration
            content = content.trim();
            
            // Convert to UTF-8 bytes
            byte[] utf8Data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            log.info("Converted {} bytes ({}) to {} UTF-8 bytes", data.length, charset.name(), utf8Data.length);
            
            // Log first few characters for debugging
            if (content.length() > 0) {
                String preview = content.substring(0, Math.min(50, content.length())).replace("\n", "\\n").replace("\r", "\\r");
                log.info("Content preview: {}", preview);
            }
            
            // Create input stream from UTF-8 data
            InputStream cleanStream = new java.io.ByteArrayInputStream(utf8Data);
            
            try (RepositoryConnection conn = repo.getConnection()) {
                
                // Clear existing data for this project
                conn.clear(conn.getValueFactory().createIRI(graphUri));
                
                // Load new data into named graph
                conn.add(cleanStream, graphUri, rdfFormat, 
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
