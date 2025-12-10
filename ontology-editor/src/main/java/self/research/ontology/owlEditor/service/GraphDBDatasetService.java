package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
                HTTPRepository httpRepo = new HTTPRepository(graphdbUrl, repositoryId);
                
                // Configure HTTP client with extended timeouts for large file uploads
                // This prevents "Connection aborted" errors during large imports
                httpRepo.setAdditionalHttpHeaders(java.util.Map.of(
                    "Keep-Alive", "timeout=1800, max=1" // 30 minutes keep-alive
                ));
                
                repository = httpRepo;
                repository.init();
                
                log.info("GraphDB HTTP client configured with extended timeouts for large file support");
                
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
            
            log.info("[GRAPHDB] 📡 EXECUTING SELECT QUERY");
            log.info("[GRAPHDB] Project: {}", projectId);
            log.info("[GRAPHDB] Graph URI: {}", graphUri);
            log.debug("[GRAPHDB] Query: {}", sparqlQuery);
            
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
            
            log.info("[GRAPHDB] ✅ Query completed, retrieved {} results from GraphDB", results.size());
            
            // Return a simple iterator-based implementation
            return new SimpleTupleQueryResult(bindingNames, results);
            
        } catch (Exception e) {
            log.error("[GRAPHDB] ❌ SELECT query failed for project: {}", projectId, e);
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
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            log.info("[GRAPHDB] ========== EXECUTING UPDATE ==========");
            log.info("[GRAPHDB] Project: {}", projectId);
            log.info("[GRAPHDB] Graph URI: {}", graphUri);
            log.info("[GRAPHDB] Original SPARQL:");
            log.info("{}", sparqlUpdate);
            
            // Inject graph context properly
            String graphAwareUpdate = injectGraphContext(sparqlUpdate, graphUri);
            
            log.info("[GRAPHDB] Graph-aware SPARQL:");
            log.info("{}", graphAwareUpdate);
            
            // Explicitly manage transaction for immediate visibility
            boolean autoCommit = conn.isAutoCommit();
            if (autoCommit) {
                conn.begin();
            }
            
            try {
                Update update = conn.prepareUpdate(graphAwareUpdate);
                update.execute();
                
                // Explicitly commit for immediate visibility
                if (autoCommit) {
                    conn.commit();
                    log.info("[GRAPHDB] ✅ Transaction committed");
                }
                
                log.info("[GRAPHDB] ✅ UPDATE executed successfully!");
                
            } catch (Exception e) {
                if (autoCommit) {
                    conn.rollback();
                    log.error("[GRAPHDB] ⚠️ Transaction rolled back");
                }
                throw e;
            }
            
        } catch (Exception e) {
            log.error("[GRAPHDB] ❌ UPDATE failed for project: {}", projectId, e);
            log.error("[GRAPHDB] Query was: {}", sparqlUpdate);
            throw new RuntimeException("SPARQL UPDATE failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Inject GRAPH/WITH context into SPARQL UPDATE
     */
    private String injectGraphContext(String sparql, String graphUri) {
        // Extract PREFIX declarations
        StringBuilder prefixes = new StringBuilder();
        StringBuilder operations = new StringBuilder();
        
        String[] lines = sparql.split("\\n");
        for (String line : lines) {
            if (line.trim().toUpperCase().startsWith("PREFIX")) {
                prefixes.append(line).append("\n");
            } else if (!line.trim().isEmpty()) {
                operations.append(line).append("\n");
            }
        }
        
        String operationsStr = operations.toString().trim();
        
        // Check if this is INSERT { ... } WHERE { ... } with Turtle syntax (contains blank nodes or semicolons within braces)
        // Don't split by semicolon in this case as it's part of Turtle syntax
        if (operationsStr.matches("(?is)INSERT\\s*\\{.*WHERE.*")) {
            // Add WITH clause before INSERT
            if (!operationsStr.trim().toUpperCase().startsWith("WITH")) {
                operationsStr = "WITH <" + graphUri + "> " + operationsStr;
                log.info("[GRAPH-INJECT] Added WITH clause to INSERT...WHERE statement");
            }
            return prefixes.toString() + operationsStr;
        }
        
        // For other cases, split by semicolon and process each statement
        // Split operations by semicolon - BUT only at top level, not inside {}
        String[] statements = splitUpdateStatements(operationsStr);
        StringBuilder result = new StringBuilder(prefixes);
        
        log.info("[GRAPH-INJECT] Processing {} statements", statements.length);
        
        for (int i = 0; i < statements.length; i++) {
            String stmt = statements[i].trim();
            if (stmt.isEmpty()) continue;
            
            log.info("[GRAPH-INJECT] Statement {}: '{}'", i, stmt.substring(0, Math.min(100, stmt.length())));
            
            // For INSERT DATA and DELETE DATA, wrap in GRAPH clause
            if (stmt.matches("(?is)INSERT\\s+DATA\\s*\\{.*")) {
                // Replace the opening brace to add GRAPH context
                stmt = stmt.replaceFirst("(?is)(INSERT\\s+DATA\\s*\\{)", "$1 GRAPH <" + graphUri + "> {");
                // Add closing brace for GRAPH context before the final brace
                stmt = stmt.replaceFirst("(?is)(.*)(\\}\\s*)$", "$1 }$2");
                log.info("[GRAPH-INJECT] Matched INSERT DATA");
            } else if (stmt.matches("(?is)DELETE\\s+DATA\\s*\\{.*")) {
                // Replace the opening brace to add GRAPH context
                stmt = stmt.replaceFirst("(?is)(DELETE\\s+DATA\\s*\\{)", "$1 GRAPH <" + graphUri + "> {");
                // Add closing brace for GRAPH context before the final brace
                stmt = stmt.replaceFirst("(?is)(.*)(\\}\\s*)$", "$1 }$2");
                log.info("[GRAPH-INJECT] Matched DELETE DATA");
            }
            // For DELETE { ... } WHERE { ... }, add WITH clause
            else if (stmt.matches("(?is)DELETE\\s*\\{.*") && !stmt.trim().toUpperCase().startsWith("WITH")) {
                stmt = "WITH <" + graphUri + "> " + stmt;
                log.info("[GRAPH-INJECT] Matched DELETE WHERE, added WITH clause");
            } else {
                log.info("[GRAPH-INJECT] No pattern matched for statement");
            }
            
            result.append(stmt);
            if (i < statements.length - 1) {
                result.append(" ;\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * Split UPDATE statements by semicolon, but only at top level (not inside braces)
     */
    private String[] splitUpdateStatements(String operations) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;
        
        for (int i = 0; i < operations.length(); i++) {
            char c = operations.charAt(i);
            
            if (c == '{') {
                braceDepth++;
                current.append(c);
            } else if (c == '}') {
                braceDepth--;
                current.append(c);
            } else if (c == ';' && braceDepth == 0) {
                // Top-level semicolon - this is a statement separator
                statements.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        // Add the last statement
        if (current.length() > 0) {
            statements.add(current.toString().trim());
        }
        
        return statements.toArray(new String[0]);
    }
    
    /**
     * Bulk load RDF data from input stream into GraphDB using CHUNKED approach
     * Parses the file and uploads in batches of 1000 triples to avoid connection timeouts
     * This is more reliable for large files that cause "Connection aborted" errors
     */
    public void bulkLoadChunked(String projectId, InputStream inputStream, RDFFormat rdfFormat) {
        long bulkLoadStart = System.nanoTime();
        final int BATCH_SIZE = 1000; // Triples per batch
        
        try {
            Repository repo = getRepository();
            String graphUri = getGraphUri(projectId);

            log.info("Starting CHUNKED bulk load for project: {} with format: {} (batch size: {} triples)",
                    projectId, rdfFormat, BATCH_SIZE);

            // WORKAROUND: VS Code web editor can add garbage bytes before XML declaration
            // Read entire stream, find <?xml, and create clean stream from that point
            InputStream cleanedStream;
            try {
                byte[] allBytes = inputStream.readAllBytes();
                log.info("Read {} bytes from input stream", allBytes.length);
                
                // Find the start of XML content (<?xml)
                int xmlStart = -1;
                for (int i = 0; i < Math.min(1000, allBytes.length - 5); i++) {
                    if (allBytes[i] == '<' && allBytes[i+1] == '?' && 
                        allBytes[i+2] == 'x' && allBytes[i+3] == 'm' && allBytes[i+4] == 'l') {
                        xmlStart = i;
                        break;
                    }
                }
                
                if (xmlStart == -1) {
                    log.error("Could not find <?xml declaration in file");
                    throw new RuntimeException("Invalid RDF/XML file: no <?xml declaration found");
                } else if (xmlStart > 0) {
                    log.warn("Found {} garbage bytes before <?xml declaration - stripping them", xmlStart);
                    byte[] cleanBytes = new byte[allBytes.length - xmlStart];
                    System.arraycopy(allBytes, xmlStart, cleanBytes, 0, cleanBytes.length);
                    cleanedStream = new java.io.ByteArrayInputStream(cleanBytes);
                } else {
                    log.info("File starts correctly with <?xml");
                    cleanedStream = new java.io.ByteArrayInputStream(allBytes);
                }
            } catch (Exception e) {
                log.error("Failed to clean input stream", e);
                throw new RuntimeException("Failed to prepare input stream: " + e.getMessage(), e);
            }

            try (RepositoryConnection conn = repo.getConnection()) {
                var valueFactory = conn.getValueFactory();
                IRI graphIri = valueFactory.createIRI(graphUri);

                // Disable auto-commit for better performance
                boolean originalAutoCommit = conn.isAutoCommit();
                if (originalAutoCommit) {
                    conn.setAutoCommit(false);
                }

                if (!conn.isActive()) {
                    conn.begin();
                }

                try {
                    // Clear existing data
                    long sizeBeforeClear = safeGraphSize(conn, graphIri, "before-clear", projectId);
                    if (sizeBeforeClear > 0) {
                        clearGraph(conn, graphIri, graphUri, projectId);
                    }

                    // Parse and upload in batches
                    RDFParser parser = Rio.createParser(rdfFormat);
                    AtomicLong totalTriples = new AtomicLong(0);
                    List<Statement> batch = new ArrayList<>(BATCH_SIZE);
                    
                    parser.setRDFHandler(new AbstractRDFHandler() {
                        @Override
                        public void handleStatement(Statement st) {
                            batch.add(st);
                            
                            if (batch.size() >= BATCH_SIZE) {
                                // Upload batch
                                conn.add(batch, graphIri);
                                long count = totalTriples.addAndGet(batch.size());
                                if (count % 10000 == 0) {
                                    log.info("Uploaded {} triples so far...", count);
                                }
                                batch.clear();
                            }
                        }
                    });

                    log.info("Parsing RDF file...");
                    parser.parse(cleanedStream, graphUri);
                    
                    // Upload remaining triples
                    if (!batch.isEmpty()) {
                        conn.add(batch, graphIri);
                        totalTriples.addAndGet(batch.size());
                    }

                    log.info("Parsed {} triples total", totalTriples.get());

                    // Commit transaction
                    log.warn("Committing {} triples to GraphDB...", totalTriples.get());
                    long commitStart = System.nanoTime();
                    conn.commit();
                    log.info("Transaction committed in {} ms", elapsedMillis(commitStart));

                    conn.setAutoCommit(originalAutoCommit);

                    log.info("CHUNKED bulk load completed for project: {} - loaded {} triples (total {} seconds)",
                            projectId, totalTriples.get(), elapsedMillis(bulkLoadStart) / 1000);
                } catch (Exception e) {
                    if (conn.isActive()) {
                        conn.rollback();
                        log.warn("Transaction rolled back for project: {}", projectId);
                    }
                    throw e;
                }
            }
            
        } catch (Exception e) {
            log.error("Chunked bulk load failed for project: {}", projectId, e);
            throw new RuntimeException("Chunked bulk load failed: " + e.getMessage(), e);
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

            // Wrap with BOM-aware input stream to handle UTF-8 BOM (Byte Order Mark)
            // Many OWL files downloaded from the internet have BOM which can cause parse failures
            InputStream bomStrippedStream = new org.apache.commons.io.input.BOMInputStream(
                inputStream, 
                org.apache.commons.io.ByteOrderMark.UTF_8,
                org.apache.commons.io.ByteOrderMark.UTF_16LE,
                org.apache.commons.io.ByteOrderMark.UTF_16BE
            );
            
            // Use large buffered input stream for better performance
            InputStream bufferedStream = new java.io.BufferedInputStream(bomStrippedStream, 65536); // 64KB buffer

            try (RepositoryConnection conn = repo.getConnection()) {
                var valueFactory = conn.getValueFactory();
                IRI graphIri = valueFactory.createIRI(graphUri);

                // **CRITICAL PERFORMANCE FIX**: Disable auto-commit for bulk loading
                // This groups all operations into a single transaction instead of committing each triple
                boolean originalAutoCommit = conn.isAutoCommit();
                
                // Only disable auto-commit if not already disabled
                if (originalAutoCommit) {
                    conn.setAutoCommit(false);
                }
                
                // Only begin transaction if not already active
                if (!conn.isActive()) {
                    conn.begin();
                    log.info("Started new transaction for bulk load");
                } else {
                    log.info("Using existing active transaction");
                }

                log.info("Opened GraphDB connection for {} (autoCommit={}, transaction active, isolation={})",
                        projectId, conn.isAutoCommit(), safeIsolationLevel(conn));

                try {
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

                    // WORKAROUND for "Connection reset by peer" errors:
                    // Save stream to temp file first, then load from file
                    // This makes the request repeatable if connection fails
                    java.io.File tempFile = java.io.File.createTempFile("graphdb-upload-", ".rdf");
                    tempFile.deleteOnExit();
                    
                    log.info("Copying stream to temp file: {}", tempFile.getAbsolutePath());
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                        bufferedStream.transferTo(fos);
                    }
                    long fileSize = tempFile.length();
                    log.info("Temp file created: {} bytes ({} MB)", fileSize, fileSize / 1024 / 1024);

                    // Now load from file (repeatable if connection drops)
                    long addStart = System.nanoTime();
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
                        conn.add(fis, graphUri, rdfFormat, graphIri);
                    }
                    log.info("GraphDB add() finished in {} ms", elapsedMillis(addStart));
                    
                    // Clean up temp file
                    tempFile.delete();

                    // Get size after loading
                    long sizeQueryStart = System.nanoTime();
                    long tripleCount = conn.size(graphIri);
                    log.info("Graph size computed in {} ms", elapsedMillis(sizeQueryStart));

                    // **COMMIT THE TRANSACTION** - This is where all changes are persisted
                    // For large files (100k+ triples), this commit can take 1-5 minutes
                    log.warn("Committing {} triples to GraphDB - this may take several minutes for large ontologies...", tripleCount);
                    long commitStart = System.nanoTime();
                    conn.commit();
                    log.info("Transaction committed in {} ms ({} seconds)", 
                            elapsedMillis(commitStart), elapsedMillis(commitStart) / 1000);

                    // Restore original auto-commit setting
                    conn.setAutoCommit(originalAutoCommit);

                    log.info("Bulk load completed for project: {} - loaded {} triples (total {} ms = {} seconds)",
                            projectId, tripleCount, elapsedMillis(bulkLoadStart), elapsedMillis(bulkLoadStart) / 1000);
                } catch (Exception e) {
                    // Rollback on any error
                    try {
                        if (conn.isActive()) {
                            conn.rollback();
                            log.warn("Transaction rolled back for project: {}", projectId);
                        }
                    } catch (Exception rollbackEx) {
                        log.error("Failed to rollback transaction", rollbackEx);
                    }
                    throw e; // Re-throw to outer catch blocks
                }
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
