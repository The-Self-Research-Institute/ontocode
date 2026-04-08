package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ImportOptions;

import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
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
    private final Map<String, PartitionGraphs> partitionGraphCache = new ConcurrentHashMap<>();
    private static final long PARTITION_CACHE_TTL_MS = 30_000;

    public interface ProgressListener {
        void onProgress(ImportProgress progress);
    }

    public static class ImportProgress {
        private final long bytesRead;
        private final long totalBytes;
        private final long triplesProcessed;
        private final long elapsedMs;

        public ImportProgress(long bytesRead, long totalBytes, long triplesProcessed, long elapsedMs) {
            this.bytesRead = bytesRead;
            this.totalBytes = totalBytes;
            this.triplesProcessed = triplesProcessed;
            this.elapsedMs = elapsedMs;
        }

        public long getBytesRead() { return bytesRead; }
        public long getTotalBytes() { return totalBytes; }
        public long getTriplesProcessed() { return triplesProcessed; }
        public long getElapsedMs() { return elapsedMs; }
    }

    private static class CountingInputStream extends java.io.FilterInputStream {
        private long count = 0L;
        private long mark = -1L;

        protected CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws java.io.IOException {
            int b = super.read();
            if (b != -1) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public synchronized void mark(int readlimit) {
            if (in.markSupported()) {
                super.mark(readlimit);
                mark = count;
            }
        }

        @Override
        public synchronized void reset() throws java.io.IOException {
            if (in.markSupported()) {
                super.reset();
                if (mark >= 0) {
                    count = mark;
                }
            }
        }

        public long getCount() {
            return count;
        }
    }
    
    /**
     * Initialize GraphDB repository connection
     */
    public void init() {
        if (repository == null) {
            log.info("Initializing GraphDB repository connection: {} / {}", graphdbUrl, repositoryId);
            try {
                HTTPRepository httpRepo = new HTTPRepository(graphdbUrl, repositoryId);
                
                // Configure HTTP client timeouts to match SPARQL query execution time
                org.apache.http.impl.client.CloseableHttpClient httpClient = org.apache.http.impl.client.HttpClients.custom()
                    .setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom()
                        .setConnectTimeout(30_000)        // 30s to establish connection
                        .setSocketTimeout(600_000)         // 10 min to wait for data (must exceed query.setMaxExecutionTime)
                        .setConnectionRequestTimeout(30_000)
                        .build())
                    .setMaxConnTotal(50)
                    .setMaxConnPerRoute(20)
                    .evictExpiredConnections()
                    .evictIdleConnections(5, java.util.concurrent.TimeUnit.MINUTES)
                    .build();
                httpRepo.setHttpClient(httpClient);
                
                httpRepo.setAdditionalHttpHeaders(java.util.Map.of(
                    "Keep-Alive", "timeout=3600, max=100",
                    "Connection", "keep-alive",
                    "Accept-Encoding", "gzip, deflate"
                ));
                
                repository = httpRepo;
                repository.init();
                
                log.info("✅ GraphDB HTTP client configured with:");
                log.info("   - Connect timeout: 30s");
                log.info("   - Socket timeout: 600s (10 min)");
                log.info("   - Connection pool: 50 total, 20 per route");
                log.info("   - Compression enabled");
                
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
     * Check if a file is already loaded into GraphDB by checking for file metadata triples
     * or by checking if the ontology IRI already exists in the project graph
     * @param projectId The project ID
     * @param fileName The name of the file to check
     * @param fileId Optional file ID to check for specific file metadata
     * @return Map with "exists" boolean and "details" with information about the existing file
     */
    public Map<String, Object> checkFileExistsInGraphDB(String projectId, String fileName, String fileId) {
        Map<String, Object> result = new HashMap<>();
        result.put("exists", false);
        
        try {
            Repository repo = getRepository();
            String graphUri = getGraphUri(projectId);
            
            try (RepositoryConnection conn = repo.getConnection()) {
                IRI graphIri = conn.getValueFactory().createIRI(graphUri);
                
                // Check 1: Check if there are any triples in the graph (basic duplicate prevention)
                long graphSize = conn.size(graphIri);
                
                // Check 2: Query for file metadata if the system stores it
                // This checks for triples that might indicate a file was already loaded
                String checkQuery = String.format(
                    "ASK { " +
                    "  GRAPH <%s> { " +
                    "    { ?s ?p ?o } " + // Check if graph has any data
                    "  } " +
                    "}",
                    graphUri
                );
                
                BooleanQuery boolQuery = conn.prepareBooleanQuery(checkQuery);
                boolean hasData = boolQuery.evaluate();
                
                if (hasData && graphSize > 0) {
                    // Graph has data - check if it's from a file with the same name
                    // Try to find ontology IRI or file identifier
                    String detailQuery = String.format(
                        "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                        "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
                        "SELECT DISTINCT ?ontology WHERE { " +
                        "  GRAPH <%s> { " +
                        "    { ?ontology rdf:type owl:Ontology } " +
                        "    UNION { ?ontology owl:versionIRI ?version } " +
                        "  } " +
                        "} LIMIT 5",
                        graphUri
                    );
                    
                    TupleQuery tupleQuery = conn.prepareTupleQuery(detailQuery);
                    List<String> ontologyIRIs = new ArrayList<>();
                    
                    try (TupleQueryResult queryResult = tupleQuery.evaluate()) {
                        while (queryResult.hasNext()) {
                            BindingSet binding = queryResult.next();
                            if (binding.hasBinding("ontology")) {
                                ontologyIRIs.add(binding.getValue("ontology").stringValue());
                            }
                        }
                    }
                    
                    result.put("exists", true);
                    result.put("graphSize", graphSize);
                    result.put("ontologyIRIs", ontologyIRIs);
                    result.put("message", String.format(
                        "Project graph already contains %d triples. Loading this file may create duplicate data.",
                        graphSize
                    ));
                    
                    log.info("[GraphDB Duplicate Check] Project {} graph contains {} triples. File: {}, FileId: {}",
                        projectId, graphSize, fileName, fileId);
                    
                    return result;
                }
                
                result.put("exists", false);
                result.put("graphSize", 0);
                log.debug("[GraphDB Duplicate Check] Project {} graph is empty. File: {} can be loaded.",
                    projectId, fileName);
                
            }
        } catch (Exception e) {
            log.error("[GraphDB Duplicate Check] Error checking file existence in GraphDB for project: {}, file: {}",
                projectId, fileName, e);
            result.put("error", e.getMessage());
            result.put("checkFailed", true);
        }
        
        return result;
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
        return execSelect(projectId, sparqlQuery, true);
    }

    /**
     * Execute a SPARQL SELECT query with control over inference.
     * @param includeInferred false to skip transitive/OWL inference (much faster on large repos)
     */
    public TupleQueryResult execSelect(String projectId, String sparqlQuery, boolean includeInferred) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            // Inject FROM clause if not present
            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE",
                    buildFromClause(conn, projectId) + " WHERE");
            }
            
            log.info("[GRAPHDB] 📡 EXECUTING SELECT QUERY");
            log.info("[GRAPHDB] Project: {}", projectId);
            log.info("[GRAPHDB] Graph URI: {}", graphUri);
            log.debug("[GRAPHDB] Query: {}", sparqlQuery);
            
            TupleQuery query = conn.prepareTupleQuery(sparqlQuery);
            query.setIncludeInferred(includeInferred);
            query.setMaxExecutionTime(300); // 5-minute timeout to prevent indefinite hangs            
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
            
            // Diagnostic: If no results, check if graphs have any data at all
            if (results.isEmpty()) {
                try {
                    List<String> allGraphs = getAllGraphUris(conn, projectId);
                    long totalSize = 0;
                    for (String g : allGraphs) {
                        var gIri = conn.getValueFactory().createIRI(g);
                        long gSize = conn.size(gIri);
                        totalSize += gSize;
                        if (gSize > 0) {
                            log.warn("[GRAPHDB] ⚠️ Query returned 0 results. Graph {} contains {} triples.", g, gSize);
                        }
                    }
                    if (totalSize == 0) {
                        log.error("[GRAPHDB] ❌ All graphs for project {} are EMPTY! Data may not have been loaded or committed. Graphs checked: {}", projectId, allGraphs);
                    }
                } catch (Exception diagEx) {
                    log.warn("[GRAPHDB] Could not check graph size: {}", diagEx.getMessage());
                }
            }
            
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
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            // Inject FROM clause if not present
            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE",
                    buildFromClause(conn, projectId) + " WHERE");
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
                    buildFromClause(conn, projectId) + " WHERE");
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
        bulkLoadChunked(projectId, inputStream, rdfFormat, -1, ImportOptions.defaults(), null);
    }

    public void bulkLoadChunked(String projectId,
                                InputStream inputStream,
                                RDFFormat rdfFormat,
                                long fileSizeBytes,
                                ImportOptions options) {
        bulkLoadChunked(projectId, inputStream, rdfFormat, fileSizeBytes, options, null);
    }

    public void bulkLoadChunked(String projectId,
                                InputStream inputStream,
                                RDFFormat rdfFormat,
                                long fileSizeBytes,
                                ImportOptions options,
                                ProgressListener progressListener) {
        long bulkLoadStart = System.nanoTime();
        int batchSize = resolveBatchSize(fileSizeBytes);
        ImportOptions resolvedOptions = options != null ? options : ImportOptions.defaults();
        
        try {
            long t0 = System.nanoTime();
            Repository repo = getRepository();
            String graphUri = getGraphUri(projectId);

            log.info("Starting CHUNKED bulk load for project: {} with format: {} (batch size: {} triples)",
                    projectId, rdfFormat, batchSize);

                // Strip binary garbage bytes that may be prepended by the upload pipeline.
                // This includes BOM, whitespace, or other binary data before content starts.
                // Apply to ALL formats, not just RDF/XML.
                CountingInputStream countingStream = new CountingInputStream(inputStream);
                InputStream cleanedStream = stripLeadingGarbage(countingStream, rdfFormat);

            long t1 = System.nanoTime();
            try (RepositoryConnection conn = repo.getConnection()) {
                long t2 = System.nanoTime();
                log.info("[TIMING] getRepository: {} ms, getConnection: {} ms",
                        (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);

                var valueFactory = conn.getValueFactory();
                IRI graphIri = valueFactory.createIRI(graphUri);

                if (!conn.isActive()) {
                    conn.begin();
                }
                long t3 = System.nanoTime();
                log.info("[TIMING] begin transaction: {} ms", (t3 - t2) / 1_000_000);

                try {
                    ImportOptions.ImportMode mode = resolvedOptions.getMode() != null
                            ? resolvedOptions.getMode()
                            : ImportOptions.ImportMode.FULL;

                    boolean shouldClear = mode == ImportOptions.ImportMode.FULL;
                    boolean diffMode = mode == ImportOptions.ImportMode.DIFF;
                    boolean partitionByNamespace = resolvedOptions.getPartitionStrategy() == ImportOptions.PartitionStrategy.NAMESPACE;

                    if (diffMode && partitionByNamespace) {
                        throw new IllegalArgumentException("Diff mode is not supported with namespace partitioning");
                    }

                    if (shouldClear) {
                        long sizeBeforeClear = safeGraphSize(conn, graphIri, "before-clear", projectId);
                        if (sizeBeforeClear > 0) {
                            clearGraph(conn, graphIri, graphUri, projectId);
                        }
                    }

                    // Parse and upload in batches
                    log.info("Creating RDF parser for format: {} (class: {})", rdfFormat, rdfFormat.getClass().getName());
                    RDFParser parser = Rio.createParser(rdfFormat);
                    log.info("Parser created: {} (class: {})", parser, parser.getClass().getName());
                    // PERFORMANCE: Disable expensive validations for large files
                    parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
                    parser.getParserConfig().set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false);
                    parser.getParserConfig().set(BasicParserSettings.NORMALIZE_DATATYPE_VALUES, false);
                    parser.getParserConfig().set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false);
                    // OWL/XML SUPPORT: Allow unqualified attributes (ontologyIRI, IRI, etc.)
                    parser.getParserConfig().set(BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES, false);
                    parser.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_URI_SYNTAX);
                    parser.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_DATATYPE_VALUES);
                    parser.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_LANGUAGE_TAGS);
                    AtomicLong totalTriples = new AtomicLong(0);
                    List<Statement> batch = new ArrayList<>(batchSize);

                    String targetGraphUri = graphUri;
                    IRI targetGraphIri = graphIri;
                    if (diffMode) {
                        targetGraphUri = graphUri + "/staging";
                        targetGraphIri = valueFactory.createIRI(targetGraphUri);
                        clearGraph(conn, targetGraphIri, targetGraphUri, projectId + "-staging");
                    }
                    
                    // Final references for use in inner class
                    final String finalTargetGraphUri = targetGraphUri;
                    final IRI finalTargetGraphIri = targetGraphIri;
                    Map<IRI, List<Statement>> partitionBatches = partitionByNamespace ? new HashMap<>() : null;
                    
                    // Optimized: Periodic commits for massive files (every 1M triples) to prevent memory bloat
                    final long COMMIT_INTERVAL = 1_000_000L;
                    
                    final long totalBytes = fileSizeBytes > 0 ? fileSizeBytes : -1;
                    final AtomicLong lastProgressSentAt = new AtomicLong(System.nanoTime());
                    final AtomicLong lastProgressTriples = new AtomicLong(0);

                    parser.setRDFHandler(new AbstractRDFHandler() {
                        @Override
                        public void handleNamespace(String prefix, String uri) {
                            // Optimized: Skip namespace handling - causes overhead for large imports
                            // GraphDB infers namespaces from data anyway
                        }

                        @Override
                        public void handleStatement(Statement st) {
                            st = sanitizeStatement(st, valueFactory);
                            if (partitionByNamespace) {
                                IRI graphForStatement = resolveNamespaceGraph(valueFactory, graphUri, st);
                                List<Statement> graphBatch = partitionBatches.computeIfAbsent(
                                        graphForStatement, key -> new ArrayList<>(batchSize));
                                graphBatch.add(st);
                                if (graphBatch.size() >= batchSize) {
                                    flushBatch(conn, graphBatch, graphForStatement, totalTriples, batchSize);
                                    
                                    // Optimized: Periodic commits for very large files (only check after flush)
                                    if (totalTriples.get() % COMMIT_INTERVAL == 0 && totalTriples.get() > 0) {
                                        conn.commit();
                                        conn.begin();
                                        log.info("Intermediate commit at {} triples", totalTriples.get());
                                    }
                                }
                                return;
                            }

                            batch.add(st);
                            if (batch.size() >= batchSize) {
                                flushBatch(conn, batch, finalTargetGraphIri, totalTriples, batchSize);
                                
                                // Optimized: Periodic commits for very large files (only check after flush)
                                if (totalTriples.get() % COMMIT_INTERVAL == 0 && totalTriples.get() > 0) {
                                    conn.commit();
                                    conn.begin();
                                    log.info("Intermediate commit at {} triples", totalTriples.get());
                                }
                            }
                            if (progressListener != null) {
                                long now = System.nanoTime();
                                long last = lastProgressSentAt.get();
                                if ((now - last) >= 2_000_000_000L) { // 2 seconds
                                    if (lastProgressSentAt.compareAndSet(last, now)) {
                                        long elapsedMs = elapsedMillis(bulkLoadStart);
                                        long triplesProcessed = totalTriples.get();
                                        long bytesRead = countingStream.getCount();
                                        if (triplesProcessed != lastProgressTriples.get()) {
                                            lastProgressTriples.set(triplesProcessed);
                                            progressListener.onProgress(new ImportProgress(bytesRead, totalBytes, triplesProcessed, elapsedMs));
                                        }
                                    }
                                }
                            }
                        }
                    });

                    log.info("Parsing RDF file...");
                    
                    // Preview first 500 bytes for debugging
                    cleanedStream.mark(1024);
                    byte[] preview = cleanedStream.readNBytes(500);
                    cleanedStream.reset();
                    String previewStr = new String(preview, java.nio.charset.StandardCharsets.UTF_8);
                    log.info("Stream content preview (first 500 chars): {}", previewStr);
                    
                    parser.parse(cleanedStream, finalTargetGraphUri);
                    
                    // Upload remaining triples
                    if (partitionByNamespace) {
                        for (Map.Entry<IRI, List<Statement>> entry : partitionBatches.entrySet()) {
                            if (!entry.getValue().isEmpty()) {
                                flushBatch(conn, entry.getValue(), entry.getKey(), totalTriples, batchSize);
                            }
                        }
                    } else if (!batch.isEmpty()) {
                        flushBatch(conn, batch, finalTargetGraphIri, totalTriples, batchSize);
                    }

                    log.info("Parsed {} triples total", totalTriples.get());
                    if (progressListener != null) {
                        long elapsedMs = elapsedMillis(bulkLoadStart);
                        long bytesRead = countingStream.getCount();
                        progressListener.onProgress(new ImportProgress(bytesRead, totalBytes, totalTriples.get(), elapsedMs));
                    }

                    if (diffMode) {
                        applyDiffUpdate(conn, graphUri, finalTargetGraphUri, projectId);
                        clearGraph(conn, finalTargetGraphIri, finalTargetGraphUri, projectId + "-staging");

                    }

                    // Commit transaction
                    log.warn("⏳ Committing {} triples to GraphDB...", totalTriples.get());
                    long commitStart = System.nanoTime();
                    conn.commit();
                    long commitDuration = elapsedMillis(commitStart);
                    log.info("✓ Transaction committed in {} ms ({} sec)", commitDuration, commitDuration / 1000);

                    // VERIFICATION: Check if data is actually readable after commit
                    long verifyStart = System.nanoTime();
                    long verifiedSize = conn.size(graphIri);
                    log.info("✓ VERIFICATION: Graph {} contains {} triples after commit (check took {} ms)", 
                            graphUri, verifiedSize, elapsedMillis(verifyStart));
                    
                    if (verifiedSize == 0 && totalTriples.get() > 0) {
                        log.error("❌ DATA LOSS DETECTED: Parsed {} triples but graph is empty after commit!", totalTriples.get());
                    }

                    long totalDuration = elapsedMillis(bulkLoadStart);
                    long parseDuration = totalDuration - commitDuration;
                    log.info("═══════════════════════════════════════════════════════════");
                    log.info("✓ CHUNKED UPLOAD COMPLETE for project: {}", projectId);
                    log.info("  Total triples: {}", totalTriples.get());
                    log.info("  TIMING BREAKDOWN:");
                    log.info("    • Parsing & batched upload: {} ms ({}%)", parseDuration, (parseDuration * 100) / totalDuration);
                    log.info("    • Commit to GraphDB: {} ms ({}%)", commitDuration, (commitDuration * 100) / totalDuration);
                    log.info("  TOTAL TIME: {} ms ({} seconds)", totalDuration, totalDuration / 1000);
                    log.info("  Average speed: {:.0f} triples/sec", (totalTriples.get() * 1000.0) / totalDuration);
                    log.info("═══════════════════════════════════════════════════════════");
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
            logCauseChain(e);
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
                    long tempFileStart = System.nanoTime();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                        bufferedStream.transferTo(fos);
                    }
                    long tempFileDuration = elapsedMillis(tempFileStart);
                    long fileSize = tempFile.length();
                    log.info("✓ Temp file created in {} ms: {} bytes ({} MB) - Speed: {:.2f} MB/s", 
                            tempFileDuration, fileSize, fileSize / 1024 / 1024,
                            (fileSize / 1024.0 / 1024.0) / (tempFileDuration / 1000.0));

                    // Now load from file (repeatable if connection drops)
                    long addStart = System.nanoTime();
                    final AtomicLong tripleCounter = new AtomicLong(0);
                    final AtomicLong lastLogTime = new AtomicLong(System.currentTimeMillis());
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
                        // Use a parser to capture namespaces while loading
                        org.eclipse.rdf4j.rio.RDFParser parser = org.eclipse.rdf4j.rio.Rio.createParser(rdfFormat);
                        // Lenient parsing for OWL/XML compatibility
                        parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
                        parser.getParserConfig().set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false);
                        parser.getParserConfig().set(BasicParserSettings.NORMALIZE_DATATYPE_VALUES, false);
                        parser.getParserConfig().set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false);
                        parser.setRDFHandler(new org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler() {
                            @Override
                            public void handleNamespace(String prefix, String uri) {
                                conn.setNamespace(prefix, uri);
                            }

                            @Override
                            public void handleStatement(org.eclipse.rdf4j.model.Statement st) {
                                st = sanitizeStatement(st, conn.getValueFactory());
                                conn.add(st, graphIri);
                                long count = tripleCounter.incrementAndGet();
                                // Log progress every 50000 triples or every 30 seconds
                                if (count % 50000 == 0 || (System.currentTimeMillis() - lastLogTime.get() > 30000)) {
                                    long elapsed = elapsedMillis(addStart);
                                    double rate = (count * 1000.0) / elapsed; // triples per second
                                    log.info("  Progress: {} triples parsed/uploaded in {} ms ({:.0f} triples/sec)", 
                                            count, elapsed, rate);
                                    lastLogTime.set(System.currentTimeMillis());
                                }
                            }
                        });
                        parser.parse(fis, graphUri);
                    }
                    long parseDuration = elapsedMillis(addStart);
                    double parseRate = (tripleCounter.get() * 1000.0) / parseDuration;
                    log.info("✓ Parsing & uploading completed in {} ms ({} sec) - {} triples at {:.0f} triples/sec", 
                            parseDuration, parseDuration / 1000, tripleCounter.get(), parseRate);
                    
                    // Clean up temp file
                    tempFile.delete();

                    // Get size after loading
                    long sizeQueryStart = System.nanoTime();
                    long tripleCount = conn.size(graphIri);
                    log.info("Graph size computed in {} ms", elapsedMillis(sizeQueryStart));

                    // **COMMIT THE TRANSACTION** - This is where all changes are persisted
                    // For large files (100k+ triples), this commit can take 1-5 minutes
                    log.warn("⏳ Committing {} triples to GraphDB - this may take several minutes for large ontologies...", tripleCount);
                    long commitStart = System.nanoTime();
                    conn.commit();
                    long commitDuration = elapsedMillis(commitStart);
                    log.info("✓ Transaction committed in {} ms ({} seconds)", 
                            commitDuration, commitDuration / 1000);

                    // Restore original auto-commit setting
                    conn.setAutoCommit(originalAutoCommit);

                    long totalDuration = elapsedMillis(bulkLoadStart);
                    log.info("═══════════════════════════════════════════════════════════");
                    log.info("✓ UPLOAD COMPLETE for project: {}", projectId);
                    log.info("  Total triples: {}", tripleCount);
                    log.info("  File size: {} MB", fileSize / 1024 / 1024);
                    log.info("  TIMING BREAKDOWN:");
                    log.info("    • File preparation: {} ms ({}%)", tempFileDuration, (tempFileDuration * 100) / totalDuration);
                    log.info("    • Parsing & upload: {} ms ({}%)", parseDuration, (parseDuration * 100) / totalDuration);
                    log.info("    • Commit to GraphDB: {} ms ({}%)", commitDuration, (commitDuration * 100) / totalDuration);
                    log.info("  TOTAL TIME: {} ms ({} seconds)", totalDuration, totalDuration / 1000);
                    log.info("  Average speed: {:.0f} triples/sec", (tripleCount * 1000.0) / totalDuration);
                    log.info("═══════════════════════════════════════════════════════════");
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
                List<String> graphs = getAllGraphUris(conn, projectId);

                // First check if graph has any data to avoid unnecessary clearing
                String countQuery = buildCountQuery(graphs);

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

                for (String g : graphs) {
                    String deleteQuery = String.format(
                        "DELETE { GRAPH <%s> { ?s ?p ?o } } WHERE { GRAPH <%s> { ?s ?p ?o } }",
                        g, g
                    );

                    try {
                        conn.prepareUpdate(deleteQuery).execute();
                        log.info("Dataset cleared for project {} graph {} using SPARQL DELETE", projectId, g);
                    } catch (Exception e) {
                        log.warn("SPARQL DELETE failed for graph {}: {}. Falling back to conn.clear()", g, e.getMessage());
                        conn.clear(conn.getValueFactory().createIRI(g));
                        log.info("Dataset cleared for project {} graph {} using conn.clear()", projectId, g);
                    }
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
     * Get prefix mappings from the dataset.
     * Only returns prefixes whose namespace URIs are actually used in the ontology triples,
     * filtering out well-known prefixes injected during import that the OWL file doesn't use.
     */
    public Map<String, String> getPrefixes(String projectId) {
        Map<String, String> prefixes = new HashMap<>();
        
        try (RepositoryConnection conn = getRepository().getConnection()) {
            
            // Collect all registered namespaces
            Map<String, String> allNamespaces = new HashMap<>();
            for (org.eclipse.rdf4j.model.Namespace ns : conn.getNamespaces()) {
                String prefix = ns.getPrefix();
                if (!prefix.endsWith(":") && !prefix.isEmpty()) {
                    prefix += ":";
                } else if (prefix.isEmpty()) {
                    prefix = ":";
                }
                allNamespaces.put(prefix, ns.getName());
            }

            // Query for all namespace URIs actually used in triples (subjects, predicates, objects)
            String sparql = "SELECT DISTINCT ?ns WHERE { " +
                    "{ ?s ?p ?o . BIND(REPLACE(STR(?s), '(.*[#/])[^#/]*$', '$1') AS ?ns) " +
                    "  FILTER(isIRI(?s)) } " +
                    "UNION " +
                    "{ ?s ?p ?o . BIND(REPLACE(STR(?p), '(.*[#/])[^#/]*$', '$1') AS ?ns) } " +
                    "UNION " +
                    "{ ?s ?p ?o . BIND(REPLACE(STR(?o), '(.*[#/])[^#/]*$', '$1') AS ?ns) " +
                    "  FILTER(isIRI(?o)) } " +
                    "}";

            java.util.Set<String> usedNamespaceUris = new java.util.HashSet<>();
            try (TupleQueryResult result = conn.prepareTupleQuery(sparql).evaluate()) {
                while (result.hasNext()) {
                    BindingSet bs = result.next();
                    if (bs.hasBinding("ns") && bs.getValue("ns") != null) {
                        usedNamespaceUris.add(bs.getValue("ns").stringValue());
                    }
                }
            } catch (Exception sparqlEx) {
                // If SPARQL fails (e.g., regex not supported), fall back to returning all
                log.warn("SPARQL namespace-usage query failed, returning all registered prefixes: {}",
                         sparqlEx.getMessage());
                prefixes.putAll(allNamespaces);
                return prefixes;
            }

            // Filter: keep only namespaces whose URI is actually referenced in triples
            // Always keep rdf:, rdfs:, owl:, xsd: as they're fundamental
            java.util.Set<String> alwaysKeep = java.util.Set.of(
                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                    "http://www.w3.org/2000/01/rdf-schema#",
                    "http://www.w3.org/2002/07/owl#",
                    "http://www.w3.org/2001/XMLSchema#"
            );

            for (Map.Entry<String, String> entry : allNamespaces.entrySet()) {
                String nsUri = entry.getValue();
                if (alwaysKeep.contains(nsUri) || usedNamespaceUris.contains(nsUri)) {
                    prefixes.put(entry.getKey(), nsUri);
                }
            }

            log.debug("Filtered prefixes for project {}: {} out of {} total",
                    projectId, prefixes.size(), allNamespaces.size());
            
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
                String prefix = entry.getKey();
                // Normalize: strip trailing colon for RDF4J
                if (prefix.endsWith(":")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                } else if (prefix.equals(":")) {
                    prefix = ""; // Default prefix
                }
                conn.setNamespace(prefix, entry.getValue());
            }
            
            log.debug("Set {} prefixes for project: {}", prefixes.size(), projectId);
            
        } catch (Exception e) {
            log.error("Failed to set prefixes for project: {}", projectId, e);
            throw new RuntimeException("Failed to set prefixes", e);
        }
    }

    /**
     * Update a single prefix mapping
     */
    public void updatePrefix(String projectId, String prefix, String iri) {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            String normalizedPrefix = prefix;
            if (normalizedPrefix.endsWith(":")) {
                normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
            } else if (normalizedPrefix.equals(":")) {
                normalizedPrefix = "";
            }
            conn.setNamespace(normalizedPrefix, iri);
            log.debug("Updated prefix '{}' to '{}' for project: {}", prefix, iri, projectId);
        } catch (Exception e) {
            log.error("Failed to update prefix '{}' for project: {}", prefix, projectId, e);
            throw new RuntimeException("Failed to update prefix", e);
        }
    }

    /**
     * Remove a prefix mapping from the dataset
     */
    public void removePrefix(String projectId, String prefix) {
        try (RepositoryConnection conn = getRepository().getConnection()) {
            String normalizedPrefix = prefix;
            if (normalizedPrefix.endsWith(":")) {
                normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
            } else if (normalizedPrefix.equals(":")) {
                normalizedPrefix = "";
            }
            conn.removeNamespace(normalizedPrefix);
            log.debug("Removed prefix '{}' for project: {}", prefix, projectId);
        } catch (Exception e) {
            log.error("Failed to remove prefix '{}' for project: {}", prefix, projectId, e);
            throw new RuntimeException("Failed to remove prefix", e);
        }
    }
    
    /**
     * Get dataset size (triple count) for a project
     */
    public long getDatasetSize(String projectId) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            List<String> graphs = getAllGraphUris(conn, projectId);
            long total = 0;
            for (String g : graphs) {
                total += conn.size(conn.getValueFactory().createIRI(g));
            }
            return total;
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
            List<String> graphs = getAllGraphUris(conn, projectId);
            List<IRI> contexts = new ArrayList<>();
            for (String g : graphs) {
                contexts.add(conn.getValueFactory().createIRI(g));
            }
            conn.export(Rio.createWriter(format, writer),
                       contexts.toArray(new IRI[0]));
            
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
                buildFromClause(conn, projectId) + " WHERE");
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

    private int resolveBatchSize(long fileSizeBytes) {
        if (fileSizeBytes <= 0) {
            return 50000;  // Optimized: 50x larger default batch size
        }
        long mb = fileSizeBytes / (1024 * 1024);
        if (mb >= 500) {
            return 100000;  // Optimized: massive batch for large files (20x improvement)
        }
        if (mb >= 200) {
            return 75000;  // Optimized: large batch
        }
        if (mb >= 100) {
            return 50000;  // Optimized: medium batch
        }
        return 50000;  // Optimized: default batch
    }

    private InputStream stripLeadingGarbage(InputStream inputStream, RDFFormat format) {
        try {
            BufferedInputStream buffered = new BufferedInputStream(inputStream, 16 * 1024);
            buffered.mark(16 * 1024);
            byte[] head = buffered.readNBytes(16 * 1024);
            
            int contentStart = -1;
            
            // First, check for UTF-8 BOM (EF BB BF) - common issue with text editors
            if (head.length >= 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF) {
                log.info("Detected UTF-8 BOM, will strip 3 bytes");
                contentStart = 3;
            }
            
            // Check for leading whitespace (spaces, tabs, newlines) before content
            if (contentStart == -1) {
                for (int i = 0; i < head.length; i++) {
                    byte b = head[i];
                    // Skip whitespace: space (32), tab (9), newline (10), carriage return (13)
                    if (b != 32 && b != 9 && b != 10 && b != 13) {
                        if (i > 0) {
                            contentStart = i;
                            log.info("Found {} bytes of leading whitespace, will strip", i);
                        }
                        break;
                    }
                }
            }
            
            // For XML-based formats (RDF/XML, OWL/XML), look for XML markers
            if (format == RDFFormat.RDFXML) {
                // Look for <?xml declaration
                if (contentStart == -1 || contentStart > 100) { // Only search if we haven't found content or found too much garbage
                    for (int i = 0; i < head.length - 5; i++) {
                        if (head[i] == '<' && head[i + 1] == '?' &&
                                head[i + 2] == 'x' && head[i + 3] == 'm' && head[i + 4] == 'l') {
                            contentStart = i;
                            log.info("Found <?xml declaration at byte offset: {}", i);
                            break;
                        }
                    }
                }
                
                // Look for <rdf:RDF opening tag
                if (contentStart == -1) {
                    String headStr = new String(head, 0, Math.min(head.length, 1024), java.nio.charset.StandardCharsets.UTF_8);
                    int rdfIndex = headStr.indexOf("<rdf:RDF");
                    if (rdfIndex >= 0) {
                        contentStart = rdfIndex;
                        log.info("Found <rdf:RDF tag at character offset: {}", rdfIndex);
                    }
                }
                
                // Look for <owl:Ontology or <Ontology tags
                if (contentStart == -1) {
                    String headStr = new String(head, 0, Math.min(head.length, 1024), java.nio.charset.StandardCharsets.UTF_8);
                    int owlIndex = headStr.indexOf("<owl:Ontology");
                    if (owlIndex < 0) {
                        owlIndex = headStr.indexOf("<Ontology");
                    }
                    if (owlIndex >= 0) {
                        contentStart = owlIndex;
                        log.info("Found <Ontology tag at character offset: {}", owlIndex);
                    }
                }
            } else if (format == RDFFormat.TURTLE || format == RDFFormat.N3) {
                // For Turtle/N3, look for @prefix or @base directives
                if (contentStart == -1 || contentStart > 100) {
                    String headStr = new String(head, 0, Math.min(head.length, 1024), java.nio.charset.StandardCharsets.UTF_8);
                    int prefixIndex = headStr.indexOf("@prefix");
                    if (prefixIndex < 0) {
                        prefixIndex = headStr.indexOf("@base");
                    }
                    if (prefixIndex >= 0 && prefixIndex < 100) { // Only if reasonable
                        contentStart = prefixIndex;
                        log.info("Found Turtle directive at character offset: {}", prefixIndex);
                    }
                }
            }
            
            // If no content markers found, return original stream
            if (contentStart == -1) {
                log.info("No garbage bytes detected, using original stream");
                buffered.reset();
                return buffered;
            }

            // If content starts at the beginning, no stripping needed
            if (contentStart == 0) {
                log.info("Content starts at byte 0, no stripping needed");
                buffered.reset();
                return buffered;
            }

            // Strip the garbage bytes before actual content
            log.warn("Stripping {} bytes of garbage before {} content", contentStart, format);
            byte[] trimmed = new byte[head.length - contentStart];
            System.arraycopy(head, contentStart, trimmed, 0, trimmed.length);
            return new SequenceInputStream(new ByteArrayInputStream(trimmed), buffered);
        } catch (Exception e) {
            log.warn("Failed to strip leading bytes from {} stream: {}", format, e.getMessage());
            return inputStream;
        }
    }

    /**
     * @deprecated Use stripLeadingGarbage instead
     */
    @Deprecated
    private InputStream stripLeadingGarbageRdfXml(InputStream inputStream) {
        return stripLeadingGarbage(inputStream, RDFFormat.RDFXML);
    }

    private IRI resolveNamespaceGraph(ValueFactory valueFactory, String baseGraphUri, Statement st) {
        String ns = null;
        if (st.getSubject() instanceof IRI iri) {
            ns = iri.getNamespace();
        } else if (st.getPredicate() != null) {
            ns = st.getPredicate().getNamespace();
        }
        if (ns == null || ns.isBlank()) {
            ns = "default";
        }
        String encoded = URLEncoder.encode(ns, StandardCharsets.UTF_8);
        return valueFactory.createIRI(baseGraphUri + "/ns/" + encoded);
    }

    private void applyDiffUpdate(RepositoryConnection conn,
                                 String mainGraphUri,
                                 String stagingGraphUri,
                                 String projectId) {
        String deleteQuery = String.format("""
            DELETE { GRAPH <%s> { ?s ?p ?o } }
            WHERE {
              GRAPH <%s> { ?s ?p ?o }
              FILTER NOT EXISTS { GRAPH <%s> { ?s ?p ?o } }
            }
            """, mainGraphUri, mainGraphUri, stagingGraphUri);

        String insertQuery = String.format("""
            INSERT { GRAPH <%s> { ?s ?p ?o } }
            WHERE {
              GRAPH <%s> { ?s ?p ?o }
              FILTER NOT EXISTS { GRAPH <%s> { ?s ?p ?o } }
            }
            """, mainGraphUri, stagingGraphUri, mainGraphUri);

        log.info("[Diff] Applying delete diff for {}", projectId);
        conn.prepareUpdate(deleteQuery).execute();
        log.info("[Diff] Applying insert diff for {}", projectId);
        conn.prepareUpdate(insertQuery).execute();
    }

    private void flushBatch(RepositoryConnection conn,
                            List<Statement> batch,
                            IRI graphIri,
                            AtomicLong totalTriples,
                            int batchSize) {
        if (batch.isEmpty()) {
            return;
        }

        long start = System.nanoTime();
        try {
            conn.add(batch, graphIri);
        } catch (Exception e) {
            // Log the failing batch details to identify the problematic IRI
            log.error("Failed to add batch of {} statements to graph {}. Error: {}",
                    batch.size(), graphIri, e.getMessage());
            for (Statement st : batch) {
                String subj = st.getSubject().stringValue();
                String pred = st.getPredicate().stringValue();
                String obj = st.getObject().stringValue();
                log.error("  Statement: <{}> <{}> <{}>", subj, pred, obj);
            }
            throw e;
        }
        long count = totalTriples.addAndGet(batch.size());
        // Optimized: Only log every 100k triples instead of 10k to reduce I/O overhead
        if (count % 100000 == 0) {
            log.info("Uploaded {} triples so far...", count);
        }
        batch.clear();

        // Optimized: Removed backpressure/sleep logic - trust GraphDB's internal queuing
        long durationMs = elapsedMillis(start);
        if (durationMs > 10000) {
            log.debug("Batch flush took {} ms for {} triples", durationMs, batchSize);
        }
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

    private String buildFromClause(RepositoryConnection conn, String projectId) {
        List<String> graphs = getAllGraphUris(conn, projectId);
        StringBuilder builder = new StringBuilder();
        for (String g : graphs) {
            builder.append("FROM <").append(g).append("> ");
        }
        return builder.toString().trim();
    }

    private List<String> getAllGraphUris(RepositoryConnection conn, String projectId) {
        String baseGraph = getGraphUri(projectId);
        List<String> graphs = new ArrayList<>();
        graphs.add(baseGraph);
        graphs.addAll(getPartitionGraphs(conn, projectId, baseGraph));
        return graphs;
    }

    private List<String> getPartitionGraphs(RepositoryConnection conn, String projectId, String baseGraph) {
        long now = System.currentTimeMillis();
        PartitionGraphs cached = partitionGraphCache.get(projectId);
        if (cached != null && now - cached.lastUpdatedMs < PARTITION_CACHE_TTL_MS) {
            return cached.graphUris;
        }

        List<String> graphs = new ArrayList<>();
        String query = String.format("""
            SELECT DISTINCT ?g WHERE {
              GRAPH ?g { ?s ?p ?o }
              FILTER(STRSTARTS(STR(?g), "%s/ns/"))
            }
            """, baseGraph);

        try {
            TupleQuery tupleQuery = conn.prepareTupleQuery(query);
            try (TupleQueryResult result = tupleQuery.evaluate()) {
                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    if (binding.hasBinding("g")) {
                        graphs.add(binding.getValue("g").stringValue());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list partition graphs for {}: {}", projectId, e.getMessage());
        }

        partitionGraphCache.put(projectId, new PartitionGraphs(graphs, now));
        return graphs;
    }

    private String buildCountQuery(List<String> graphs) {
        if (graphs.size() == 1) {
            return String.format(
                "SELECT (COUNT(*) as ?count) WHERE { GRAPH <%s> { ?s ?p ?o } }",
                graphs.get(0)
            );
        }

        StringBuilder where = new StringBuilder();
        where.append("SELECT (COUNT(*) as ?count) WHERE { ");
        for (int i = 0; i < graphs.size(); i++) {
            if (i > 0) {
                where.append(" UNION ");
            }
            where.append("{ GRAPH <").append(graphs.get(i)).append("> { ?s ?p ?o } }");
        }
        where.append(" }");
        return where.toString();
    }

    private static final class PartitionGraphs {
        private final List<String> graphUris;
        private final long lastUpdatedMs;

        private PartitionGraphs(List<String> graphUris, long lastUpdatedMs) {
            this.graphUris = graphUris;
            this.lastUpdatedMs = lastUpdatedMs;
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

    /**
     * Get root classes (direct children of owl:Thing) from GraphDB using SPARQL.
     * This queries the actual imported triples, not the original file.
     * Works for all file formats (OWL/XML, RDF/XML, Turtle, etc.)
     * 
     * @param projectId Project identifier
     * @return List of maps containing class info: id, label, type, hasChildren
     */
    public List<Map<String, Object>> getRootClassesFromGraphDB(String projectId) {
        List<Map<String, Object>> rootClasses = new ArrayList<>();
        
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            String baseGraph = graphUri;
            // SPARQL query to find all classes that are NOT subclasses of any class except owl:Thing
            // Includes owl:Class, rdfs:Class, and classes only mentioned in subclass axioms
            String query = 
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> \n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> \n" +
                "SELECT DISTINCT ?class ?label \n" +
                "WHERE { \n" +
                "  { \n" +
                "    GRAPH ?g { \n" +
                "      { ?class rdf:type ?type . VALUES ?type { owl:Class rdfs:Class } } \n" +
                "      UNION { ?class rdfs:subClassOf ?any . FILTER (!isBlank(?class)) } \n" +
                "      UNION { ?any rdfs:subClassOf ?class . FILTER (!isBlank(?class)) } \n" +
                "      OPTIONAL { ?class rdfs:label ?label } \n" +
                "      FILTER (?class != owl:Thing && !isBlank(?class)) \n" +
                "      OPTIONAL { \n" +
                "        ?class rdfs:subClassOf ?parent . \n" +
                "        FILTER (?parent != owl:Thing && !isBlank(?parent)) \n" +
                "      } \n" +
                "      FILTER (!BOUND(?parent)) \n" +
                "    } \n" +
                "    FILTER(STRSTARTS(STR(?g), \"" + baseGraph + "\")) \n" +
                "  } \n" +
                "  UNION { \n" +
                "    { ?class rdf:type ?type . VALUES ?type { owl:Class rdfs:Class } } \n" +
                "    UNION { ?class rdfs:subClassOf ?any . FILTER (!isBlank(?class)) } \n" +
                "    UNION { ?any rdfs:subClassOf ?class . FILTER (!isBlank(?class)) } \n" +
                "    OPTIONAL { ?class rdfs:label ?label } \n" +
                "    FILTER (?class != owl:Thing && !isBlank(?class)) \n" +
                "    OPTIONAL { \n" +
                "      ?class rdfs:subClassOf ?parent . \n" +
                "      FILTER (?parent != owl:Thing && !isBlank(?parent)) \n" +
                "    } \n" +
                "    FILTER (!BOUND(?parent)) \n" +
                "  } \n" +
                "} \n" +
                "ORDER BY ?label ?class";
            
            log.info("[GRAPHDB] Executing getRootClasses query for project: {}", projectId);
            TupleQuery tupleQuery = conn.prepareTupleQuery(query);
            
            try (TupleQueryResult result = tupleQuery.evaluate()) {
                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    String classIri = binding.getValue("class").stringValue();
                    String classLabel = binding.hasBinding("label") 
                        ? binding.getValue("label").stringValue() 
                        : extractShortForm(classIri);
                    
                    // Check if this class has children
                    boolean hasChildren = classHasChildren(conn, projectId, classIri);
                    
                    Map<String, Object> classInfo = new HashMap<>();
                    classInfo.put("id", classIri);
                    classInfo.put("label", classLabel);
                    classInfo.put("type", "CLASS");
                    classInfo.put("hasChildren", hasChildren);
                    
                    rootClasses.add(classInfo);
                    log.debug("Found root class: {} ({})", classLabel, classIri);
                }
            }
            
            log.info("[GRAPHDB] Found {} root classes for project {}", rootClasses.size(), projectId);
            
        } catch (Exception e) {
            log.error("Error getting root classes from GraphDB for project {}", projectId, e);
        }
        
        return rootClasses;
    }

    /**
     * Get child classes of a given class using SPARQL.
     * 
     * @param projectId Project identifier
     * @param parentClassIri IRI of the parent class
     * @return List of maps containing child class info
     */
    public List<Map<String, Object>> getChildClassesFromGraphDB(String projectId, String parentClassIri) {
        List<Map<String, Object>> childClasses = new ArrayList<>();
        
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            String baseGraph = graphUri;
            // SPARQL query to find direct children of a given class
            String query = 
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> \n" +
                "SELECT DISTINCT ?child ?label \n" +
                "WHERE { \n" +
                "  { \n" +
                "    GRAPH ?g { \n" +
                "      ?child rdfs:subClassOf <" + parentClassIri + "> . \n" +
                "      OPTIONAL { ?child rdfs:label ?label } \n" +
                "      FILTER (?child != owl:Thing && !isBlank(?child)) \n" +
                "    } \n" +
                "    FILTER(STRSTARTS(STR(?g), \"" + baseGraph + "\")) \n" +
                "  } \n" +
                "  UNION { \n" +
                "    ?child rdfs:subClassOf <" + parentClassIri + "> . \n" +
                "    OPTIONAL { ?child rdfs:label ?label } \n" +
                "    FILTER (?child != owl:Thing && !isBlank(?child)) \n" +
                "  } \n" +
                "} \n" +
                "ORDER BY ?label ?child";
            
            log.info("[GRAPHDB] Executing getChildClasses query for parent: {} in project: {}", 
                     parentClassIri, projectId);
            TupleQuery tupleQuery = conn.prepareTupleQuery(query);
            
            try (TupleQueryResult result = tupleQuery.evaluate()) {
                while (result.hasNext()) {
                    BindingSet binding = result.next();
                    String childIri = binding.getValue("child").stringValue();
                    String childLabel = binding.hasBinding("label") 
                        ? binding.getValue("label").stringValue() 
                        : extractShortForm(childIri);
                    
                    // Check if this child has further children
                    boolean hasChildren = classHasChildren(conn, projectId, childIri);
                    
                    Map<String, Object> childInfo = new HashMap<>();
                    childInfo.put("id", childIri);
                    childInfo.put("label", childLabel);
                    childInfo.put("type", "CLASS");
                    childInfo.put("hasChildren", hasChildren);
                    
                    childClasses.add(childInfo);
                    log.debug("Found child class: {} ({})", childLabel, childIri);
                }
            }
            
            log.info("[GRAPHDB] Found {} child classes for parent {} in project {}", 
                     childClasses.size(), parentClassIri, projectId);
            
        } catch (Exception e) {
            log.error("Error getting child classes from GraphDB for parent {} in project {}", 
                     parentClassIri, projectId, e);
        }
        
        return childClasses;
    }

    /**
     * Check if a class has any direct subclasses.
     * Used to determine hasChildren flag without loading all children.
     * 
     * @param conn RepositoryConnection to use
     * @param graphUri URI of the graph
     * @param classIri IRI of the class to check
     * @return true if class has direct subclasses
     */
    private boolean classHasChildren(RepositoryConnection conn, String projectId, String classIri) {
        try {
            String baseGraph = getGraphUri(projectId);
            String query = 
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> \n" +
                "ASK \n" +
                "WHERE { \n" +
                "  { \n" +
                "    GRAPH ?g { \n" +
                "      ?child rdfs:subClassOf <" + classIri + "> . \n" +
                "      FILTER (?child != owl:Thing && !isBlank(?child)) \n" +
                "    } \n" +
                "    FILTER(STRSTARTS(STR(?g), \"" + baseGraph + "\")) \n" +
                "  } \n" +
                "  UNION { \n" +
                "    ?child rdfs:subClassOf <" + classIri + "> . \n" +
                "    FILTER (?child != owl:Thing && !isBlank(?child)) \n" +
                "  } \n" +
                "}";
            
            BooleanQuery booleanQuery = conn.prepareBooleanQuery(query);
            return booleanQuery.evaluate();
            
        } catch (Exception e) {
            log.warn("Error checking if class {} has children", classIri, e);
            return false;
        }
    }

    /**
     * Sanitize a statement by percent-encoding invalid characters in IRIs.
     * GraphDB rejects IRIs containing characters like [], {}, etc.
     * Returns the original statement if no sanitization is needed.
     */
    private static final AtomicLong sanitizeLogCount = new AtomicLong(0);

    private Statement sanitizeStatement(Statement st, ValueFactory vf) {
        Resource subject = st.getSubject();
        IRI predicate = st.getPredicate();
        org.eclipse.rdf4j.model.Value object = st.getObject();
        boolean changed = false;

        if (subject instanceof IRI subjectIri) {
            String sanitized = sanitizeIriString(subjectIri.stringValue());
            if (sanitized != null) {
                subject = vf.createIRI(sanitized);
                changed = true;
            }
        }

        String sanitizedPred = sanitizeIriString(predicate.stringValue());
        if (sanitizedPred != null) {
            predicate = vf.createIRI(sanitizedPred);
            changed = true;
        }

        if (object instanceof IRI objectIri) {
            String sanitized = sanitizeIriString(objectIri.stringValue());
            if (sanitized != null) {
                object = vf.createIRI(sanitized);
                changed = true;
            }
        }

        if (changed && sanitizeLogCount.incrementAndGet() <= 20) {
            log.warn("[IRI-SANITIZE] Sanitized statement: <{}> <{}> <{}>",
                    subject.stringValue(), predicate.stringValue(), object.stringValue());
        }
        return changed ? vf.createStatement(subject, predicate, object) : st;
    }

    /**
     * Percent-encode characters in an IRI that GraphDB rejects.
     * Returns null if no sanitization needed (fast path for common case).
     */
    private String sanitizeIriString(String iri) {
        if (iri == null) return null;

        // Fast check: scan for any character that needs encoding
        boolean needsEncoding = false;
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c == '[' || c == ']' || c == '{' || c == '}' || c == '|'
                    || c == '\\' || c == '^' || c == '`' || c == ' '
                    || c == '(' || c == ')' || c > 127) {
                needsEncoding = true;
                break;
            }
        }
        if (!needsEncoding) return null;

        StringBuilder sb = new StringBuilder(iri.length() + 40);
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c == '[') sb.append("%5B");
            else if (c == ']') sb.append("%5D");
            else if (c == '{') sb.append("%7B");
            else if (c == '}') sb.append("%7D");
            else if (c == '|') sb.append("%7C");
            else if (c == '\\') sb.append("%5C");
            else if (c == '^') sb.append("%5E");
            else if (c == '`') sb.append("%60");
            else if (c == ' ') sb.append("%20");
            else if (c == '(') sb.append("%28");
            else if (c == ')') sb.append("%29");
            else if (c > 127) {
                // Percent-encode non-ASCII (Unicode subscripts, etc.)
                byte[] utf8 = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : utf8) {
                    sb.append('%');
                    sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Extract short form (local name) from an IRI for use as class label.
     * Examples:
     *   http://example.org#MyClass -> MyClass
     *   http://example.org/ontology#Name -> Name
     *
     * @param iri Full IRI string
     * @return Short form (last part after # or /)
     */
    private String extractShortForm(String iri) {
        if (iri == null || iri.isEmpty()) {
            return "unknown";
        }
        
        // Try to extract after # first (OWL convention)
        int hashIndex = iri.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex < iri.length() - 1) {
            return iri.substring(hashIndex + 1);
        }
        
        // Fall back to after last /
        int slashIndex = iri.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < iri.length() - 1) {
            return iri.substring(slashIndex + 1);
        }
        
        // Return as-is if no delimiter found
        return iri;
    }
}
