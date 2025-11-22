package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
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
     * Uses streaming to handle large files without loading entire content into memory
     */
    public void bulkLoad(String projectId, InputStream inputStream, RDFFormat rdfFormat) {
        long bulkLoadStart = System.nanoTime();
        try {
            Repository repo = getRepository();
            String graphUri = getGraphUri(projectId);

            log.info("Starting bulk load for project: {} with format: {} (GraphDB: {} repo: {})",
                    projectId, rdfFormat, graphdbUrl, repositoryId);

            // Use buffered input stream for better performance
            // RDF4J handles BOM detection and charset conversion automatically
            InputStream bufferedStream = new java.io.BufferedInputStream(inputStream, 8192);

            try (RepositoryConnection conn = repo.getConnection()) {
                var valueFactory = conn.getValueFactory();
                IRI graphIri = valueFactory.createIRI(graphUri);

                log.info("Opened GraphDB connection for {} (autoCommit={}, isolation={})",
                        projectId, conn.isAutoCommit(), safeIsolationLevel(conn));

                // Record current dataset size if possible
                long sizeBeforeClear = safeGraphSize(conn, graphIri, "before-clear", projectId);
                if (sizeBeforeClear >= 0) {
                    log.info("Project {}: {} triples detected before clear", projectId, sizeBeforeClear);
                }

                // Clear existing data only if needed (avoid hanging clears on empty graphs)
                if (sizeBeforeClear > 0) {
                    clearGraph(conn, graphIri, graphUri, projectId);
                } else {
                    log.info("Graph {} already empty, skipping clear", graphUri);
                }

                log.info("Loading data into GraphDB graph: {}", graphUri);

                // Load new data into named graph (streaming - no full load into memory)
                // RDF4J will parse and load incrementally
                long addStart = System.nanoTime();
                conn.add(bufferedStream, graphUri, rdfFormat, graphIri);
                log.info("GraphDB add() finished in {} ms", elapsedMillis(addStart));

                // Get size after loading
                long sizeQueryStart = System.nanoTime();
                long tripleCount = conn.size(graphIri);
                log.info("Graph size computed in {} ms", elapsedMillis(sizeQueryStart));

                log.info("Bulk load completed for project: {} - loaded {} triples (total {} ms)",
                        projectId, tripleCount, elapsedMillis(bulkLoadStart));
            }
            
        } catch (org.eclipse.rdf4j.rio.RDFParseException e) {
            log.error("RDF parsing failed for project: {}. Parse error: {}", projectId, e.getMessage());
            log.error("Error at line {}, column {}", e.getLineNumber(), e.getColumnNumber());
            
            // Log more context about the error
            String errorMsg = "Invalid RDF format";
            if (e.getMessage().contains("prolog")) {
                errorMsg = "File encoding issue or invalid XML prolog. The file may have hidden characters, incorrect BOM, or is not valid RDF/XML.";
            } else if (e.getMessage().contains("Premature end")) {
                errorMsg = "Incomplete RDF file. The file may be truncated or corrupted.";
            } else if (e.getMessage().contains("Undeclared namespace")) {
                errorMsg = "Missing namespace declaration: " + e.getMessage();
            }
            
            throw new RuntimeException(errorMsg + " Details: " + e.getMessage(), e);
            
        } catch (org.eclipse.rdf4j.repository.RepositoryException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("not found")) {
                log.error("GraphDB repository '{}' not found at {}", repositoryId, graphdbUrl);
                log.error("Please create the repository via GraphDB Workbench: {}/repository", graphdbUrl);
                throw new RuntimeException("GraphDB repository '" + repositoryId + "' not found. Please create it first.", e);
            } else {
                log.error("GraphDB repository error for project: {}", projectId, e);
                logCauseChain(e);
                throw new RuntimeException("GraphDB error: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("Unexpected error during bulk load for project: {}", projectId, e);
            log.error("Error type: {}", e.getClass().getName());
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
                // First check if graph has any data to avoid unnecessary clearing
                String countQuery = String.format(
                    "SELECT (COUNT(*) as ?count) WHERE { GRAPH <%s> { ?s ?p ?o } }",
                    graphUri
                );

                try {
                    var query = conn.prepareTupleQuery(countQuery);
                    try (var result = query.evaluate()) {
                        if (result.hasNext()) {
                            var binding = result.next();
                            var countValue = binding.getValue("count");
                            long count = Long.parseLong(countValue.stringValue());

                            if (count == 0) {
                                log.info("Dataset already empty for project: {}, skipping clear", projectId);
                                return;
                            }
                            log.info("Found {} triples to clear for project: {}", count, projectId);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not count triples, proceeding with clear: {}", e.getMessage());
                }

                // Use SPARQL DELETE for more reliable clearing
                String deleteQuery = String.format(
                    "DELETE { GRAPH <%s> { ?s ?p ?o } } WHERE { GRAPH <%s> { ?s ?p ?o } }",
                    graphUri, graphUri
                );

                // Execute update with query
                try {
                    conn.prepareUpdate(deleteQuery).execute();
                    log.info("Dataset cleared for project: {} using SPARQL DELETE", projectId);
                } catch (Exception e) {
                    // Fallback to conn.clear() if SPARQL DELETE fails
                    log.warn("SPARQL DELETE failed, falling back to conn.clear(): {}", e.getMessage());
                    conn.clear(conn.getValueFactory().createIRI(graphUri));
                    log.info("Dataset cleared for project: {} using conn.clear()", projectId);
                }
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

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private long safeGraphSize(RepositoryConnection conn, IRI graphIri, String tag, String projectId) {
        try {
            long size = conn.size(graphIri);
            log.info("Project {}: graph size {} during {}", projectId, size, tag);
            return size;
        } catch (Exception e) {
            log.warn("Could not get graph size for project {} during {}: {}", projectId, tag, e.getMessage());
            return -1;
        }
    }

    private void logCauseChain(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            log.error("Cause: {} - {}", current.getClass().getName(), current.getMessage());
            current = current.getCause();
        }
    }

    private String safeIsolationLevel(RepositoryConnection conn) {
        try {
            var method = conn.getClass().getMethod("getTransactionIsolationLevel");
            Object value = method.invoke(conn);
            return value != null ? value.toString() : "null";
        } catch (Exception ex) {
            return "unknown";
        }
    }

    private void clearGraph(RepositoryConnection conn, IRI graphIri, String graphUri, String projectId) {
        long clearStart = System.nanoTime();
        String clearQuery = String.format("CLEAR GRAPH <%s>", graphUri);
        try {
            conn.prepareUpdate(clearQuery).execute();
            log.info("Graph {} cleared via SPARQL CLEAR in {} ms", graphUri, elapsedMillis(clearStart));
            return;
        } catch (Exception sparqlClearError) {
            log.warn("SPARQL CLEAR failed for project {} graph {}: {}. Falling back to conn.clear()",
                    projectId, graphUri, sparqlClearError.getMessage());
        }

        long fallbackStart = System.nanoTime();
        conn.clear(graphIri);
        log.info("Graph {} cleared via conn.clear() in {} ms", graphUri, elapsedMillis(fallbackStart));
    }
}
