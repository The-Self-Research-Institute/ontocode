package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.rdf4j.http.client.SharedHttpClientSessionManager;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ImportOptions;

import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Logger sparqlLog = LoggerFactory.getLogger("SPARQL");
    
    @Value("${ontocode.fuseki.queryEndpoint:http://localhost:3030/ontocode/query}")
    private String fusekiQueryEndpoint;

    @Value("${ontocode.fuseki.updateEndpoint:http://localhost:3030/ontocode/update}")
    private String fusekiUpdateEndpoint;

    @Value("${ontocode.fuseki.gspEndpoint:http://localhost:3030/ontocode/data}")
    private String fusekiGspEndpoint;

    @Value("${ontocode.fuseki.adminUser:admin}")
    private String fusekiAdminUser;

    @Value("${ontocode.fuseki.adminPassword:admin}")
    private String fusekiAdminPassword;

    @Value("${ontocode.data.dir:./data}")
    private String dataDir;
    
    @Autowired(required = false)
    private CacheManager cacheManager;

    // Phase C: in-memory mirror of project graphs to bypass GraphDB on hot reads.
    @Autowired(required = false)
    private ProjectRepoCache projectRepoCache;

    // Shared repository connection
    private Repository repository;
    
    // Cache of graph URIs per project (projectId -> graphUri)
    private final Map<String, String> graphUriCache = new ConcurrentHashMap<>();
    private final Map<String, PartitionGraphs> partitionGraphCache = new ConcurrentHashMap<>();
    private static final long PARTITION_CACHE_TTL_MS = 120_000; // 2 minutes — partition graphs rarely change

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
     * Initialize Fuseki SPARQL endpoint connection via SPARQLRepository
     */
    public void init() {
        if (repository == null) {
            log.info("Initializing Fuseki SPARQL connection: query={} update={}", fusekiQueryEndpoint, fusekiUpdateEndpoint);
            try {
                BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(fusekiAdminUser, fusekiAdminPassword));
                // Preemptive Basic Auth — RDF4J's SPARQLProtocolSession intercepts 401 and
                // throws UnauthorizedException before HttpClient can retry with credentials,
                // so we must send the Authorization header on every request upfront.
                String encodedCreds = java.util.Base64.getEncoder().encodeToString(
                        (fusekiAdminUser + ":" + fusekiAdminPassword)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                final String basicAuthHeader = "Basic " + encodedCreds;
                // 2-hour socket timeout matches the gateway and tomcat timeouts — prevents
                // QueryInterruptedException (SocketTimeoutException) on large ontology commits
                org.apache.http.client.config.RequestConfig requestConfig =
                        org.apache.http.client.config.RequestConfig.custom()
                                .setConnectTimeout(30_000)
                                .setSocketTimeout(7_200_000)
                                .setConnectionRequestTimeout(30_000)
                                .build();
                CloseableHttpClient httpClient = HttpClients.custom()
                        .setDefaultCredentialsProvider(credsProvider)
                        .setDefaultRequestConfig(requestConfig)
                        .addInterceptorFirst((org.apache.http.HttpRequestInterceptor) (request, context) -> {
                            if (!request.containsHeader("Authorization")) {
                                request.addHeader("Authorization", basicAuthHeader);
                            }
                        })
                        .build();
                SPARQLRepository sparqlRepo = new SPARQLRepository(fusekiQueryEndpoint, fusekiUpdateEndpoint);
                java.util.concurrent.ScheduledExecutorService scheduler =
                        java.util.concurrent.Executors.newScheduledThreadPool(1);
                sparqlRepo.setHttpClientSessionManager(new SharedHttpClientSessionManager(httpClient, scheduler));
                sparqlRepo.init();
                repository = sparqlRepo;

                // Test connectivity with a cheap ASK query
                try (RepositoryConnection conn = repository.getConnection()) {
                    conn.prepareBooleanQuery("ASK { }").evaluate();
                    log.info("✅ Fuseki connection verified: {}", fusekiQueryEndpoint);
                }
            } catch (Exception e) {
                log.error("Failed to connect to Fuseki at {}", fusekiQueryEndpoint, e);
                log.error("Start Fuseki: docker compose up fuseki");
                throw new RuntimeException("Fuseki connection failed: " + e.getMessage(), e);
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
            id -> "http://ontocode.org/project/" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
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
                // Check 1: Check if there are any triples in the graph (basic duplicate prevention)
                long graphSize = countGraphTriplesSparql(conn, graphUri);
                
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
                long askStart = System.nanoTime();
                boolean hasData = boolQuery.evaluate();
                log.info("[TIMING] checkFileExistsInGraphDB ASK query for project {}: {} ms", projectId, elapsedMillis(askStart));
                
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
                    long detailQueryStart = System.nanoTime();
                    
                    try (TupleQueryResult queryResult = tupleQuery.evaluate()) {
                        while (queryResult.hasNext()) {
                            BindingSet binding = queryResult.next();
                            if (binding.hasBinding("ontology")) {
                                ontologyIRIs.add(binding.getValue("ontology").stringValue());
                            }
                        }
                    }
                    
                    log.info("[TIMING] checkFileExistsInGraphDB detail SELECT query for project {}: {} ms (found {} ontology IRIs)",
                        projectId, elapsedMillis(detailQueryStart), ontologyIRIs.size());
                    
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
        // Default to includeInferred=false — the repository uses ruleset: empty,
        // so inference adds only overhead (30-130s penalties per query)
        return execSelect(projectId, sparqlQuery, false);
    }

    /**
     * Execute a SPARQL SELECT query with control over inference.
     * @param includeInferred false to skip transitive/OWL inference (much faster on large repos)
     */
    public TupleQueryResult execSelect(String projectId, String sparqlQuery, boolean includeInferred) {
        // Phase C: try the in-memory per-project cache first. Only when inference
        // is not requested, since the cache only stores explicit triples.
        if (!includeInferred && projectRepoCache != null && projectRepoCache.isEnabled()) {
            TupleQueryResult cached = trySelectFromMemCache(projectId, sparqlQuery);
            if (cached != null) {
                return cached;
            }
        }
        return execSelectGraphDB(projectId, sparqlQuery, includeInferred);
    }

    /**
     * Run a SELECT against the in-memory project repo. Returns {@code null} on
     * cache miss / load failure so the caller can fall back to GraphDB.
     */
    private TupleQueryResult trySelectFromMemCache(String projectId, String sparqlQuery) {
        long start = System.nanoTime();
        Repository memRepo = projectRepoCache.getOrLoad(projectId, new ProjectRepoCache.Loader() {
            @Override public GraphQueryResult streamTriples(String pid) {
                return execConstructAll(pid);
            }
            @Override public String graphUri(String pid) {
                return getGraphUri(pid);
            }
        });
        if (memRepo == null) {
            return null;
        }
        try (RepositoryConnection conn = memRepo.getConnection()) {
            String finalQuery = sparqlQuery;
            if (!finalQuery.toUpperCase().contains("FROM")) {
                finalQuery = finalQuery.replaceFirst("(?i)WHERE",
                        buildFromClause(conn, projectId) + " WHERE");
            }
            String queryType = extractQueryType(finalQuery);
            TupleQuery query = conn.prepareTupleQuery(finalQuery);
            query.setIncludeInferred(false);
            List<BindingSet> results = new ArrayList<>();
            List<String> bindingNames = new ArrayList<>();
            try (TupleQueryResult rs = query.evaluate()) {
                bindingNames.addAll(rs.getBindingNames());
                while (rs.hasNext()) {
                    results.add(rs.next());
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            sparqlLog.info("[SPARQL_MEM] {} project={} results={} time={}ms",
                    queryType, projectId, results.size(), ms);
            return new SimpleTupleQueryResult(bindingNames, results);
        } catch (Exception e) {
            log.warn("[MEMCACHE] SELECT failed for project={}, falling back to GraphDB: {}",
                    projectId, e.getMessage());
            return null;
        }
    }

    /**
     * Stream every explicit triple in the project's graph(s) from GraphDB.
     * Used by {@link ProjectRepoCache} to populate the in-memory mirror.
     * The caller MUST close the returned {@link GraphQueryResult} which in
     * turn closes its RepositoryConnection.
     */
    private GraphQueryResult execConstructAll(String projectId) {
        Repository repo = getRepository();
        final RepositoryConnection conn = repo.getConnection();
        try {
            String fromClause = buildFromClause(conn, projectId);
            String q = "CONSTRUCT { ?s ?p ?o } " + fromClause + " WHERE { ?s ?p ?o }";
            GraphQuery gq = conn.prepareGraphQuery(q);
            gq.setIncludeInferred(false);
            final GraphQueryResult inner = gq.evaluate();
            // Wrap so closing the result also closes the connection.
            return new GraphQueryResult() {
                @Override public java.util.Map<String, String> getNamespaces() { return inner.getNamespaces(); }
                @Override public boolean hasNext() { return inner.hasNext(); }
                @Override public org.eclipse.rdf4j.model.Statement next() { return inner.next(); }
                @Override public void remove() { inner.remove(); }
                @Override public void close() {
                    try { inner.close(); } finally { conn.close(); }
                }
            };
        } catch (RuntimeException e) {
            conn.close();
            throw e;
        }
    }

    /**
     * Original GraphDB SELECT path — retained as the fallback behind the
     * in-memory cache.
     */
    private TupleQueryResult execSelectGraphDB(String projectId, String sparqlQuery, boolean includeInferred) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        long totalStart = System.nanoTime();

        try (RepositoryConnection conn = repo.getConnection()) {
            long connMs = (System.nanoTime() - totalStart) / 1_000_000;
            
            // Inject FROM clause if not present
            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE",
                    buildFromClause(conn, projectId) + " WHERE");
            }
            
            // Extract query type for logging (first SELECT/ASK/CONSTRUCT keyword)
            String queryType = extractQueryType(sparqlQuery);
            
            log.info("[GRAPHDB] EXECUTING {} project={} graph={} connTime={}ms", queryType, projectId, graphUri, connMs);
            log.debug("[GRAPHDB] Query: {}", sparqlQuery);
            
            long queryStart = System.nanoTime();
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
            long queryMs = (System.nanoTime() - queryStart) / 1_000_000;
            long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
            
            // Log to dedicated SPARQL logger
            sparqlLog.info("[SPARQL] {} project={} results={} queryTime={}ms totalTime={}ms connTime={}ms",
                    queryType, projectId, results.size(), queryMs, totalMs, connMs);
            
            if (queryMs > 1000) {
                sparqlLog.warn("[SPARQL_SLOW] {} project={} took {}ms results={} query={}",
                        queryType, projectId, queryMs, results.size(),
                        sparqlQuery.replaceAll("\\s+", " ").substring(0, Math.min(200, sparqlQuery.length())));
            }
            
            log.info("[GRAPHDB] {} completed: {} results in {}ms (conn={}ms, query={}ms)",
                    queryType, results.size(), totalMs, connMs, queryMs);
            
            // Diagnostic: If no results, check if graphs have any data at all
            if (results.isEmpty()) {
                try {
                    List<String> allGraphs = getAllGraphUris(conn, projectId);
                    long totalSize = 0;
                    for (String g : allGraphs) {
                        long gSize = countGraphTriplesSparql(conn, g);
                        totalSize += gSize;
                        if (gSize > 0) {
                            log.warn("[GRAPHDB] Query returned 0 results. Graph {} contains {} triples.", g, gSize);
                        }
                    }
                    if (totalSize == 0) {
                        log.error("[GRAPHDB] All graphs for project {} are EMPTY!", projectId);
                    }
                } catch (Exception diagEx) {
                    log.warn("[GRAPHDB] Could not check graph size: {}", diagEx.getMessage());
                }
            }
            
            // Return a simple iterator-based implementation
            return new SimpleTupleQueryResult(bindingNames, results);
            
        } catch (Exception e) {
            long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
            log.error("[GRAPHDB] SELECT failed for project {} after {}ms: {}", projectId, totalMs, e.getMessage());
            sparqlLog.error("[SPARQL_ERROR] project={} duration={}ms error={}", projectId, totalMs, e.getMessage());
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
            
            log.info("[GRAPHDB] Executing CONSTRUCT query for project: {}", projectId);
            long constructStart = System.nanoTime();
            GraphQuery query = conn.prepareGraphQuery(sparqlQuery);
            query.setIncludeInferred(false);
            GraphQueryResult result = query.evaluate();
            log.info("[TIMING] execConstruct for project {}: {} ms", projectId, elapsedMillis(constructStart));
            return result;
            
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
            
            log.info("[GRAPHDB] Executing ASK query for project: {}", projectId);
            long askStart = System.nanoTime();
            BooleanQuery query = conn.prepareBooleanQuery(sparqlQuery);
            query.setIncludeInferred(false);
            boolean askResult = query.evaluate();
            log.info("[TIMING] execAsk for project {}: {} ms (result: {})", projectId, elapsedMillis(askStart), askResult);
            return askResult;
            
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
        long totalStart = System.nanoTime();
        
        try (RepositoryConnection conn = repo.getConnection()) {
            long connMs = elapsedMillis(totalStart);
            
            log.info("[GRAPHDB] EXECUTING UPDATE project={} graph={} connTime={}ms", projectId, graphUri, connMs);
            log.debug("[GRAPHDB] Update SPARQL: {}", sparqlUpdate);
            
            // Inject graph context properly
            String graphAwareUpdate = injectGraphContext(sparqlUpdate, graphUri);
            
            // Explicitly manage transaction for immediate visibility
            boolean autoCommit = conn.isAutoCommit();
            if (autoCommit) {
                conn.begin();
            }
            
            try {
                long updateExecStart = System.nanoTime();
                Update update = conn.prepareUpdate(graphAwareUpdate);
                update.execute();
                long updateExecMs = elapsedMillis(updateExecStart);
                
                // Explicitly commit for immediate visibility
                long commitMs = 0;
                if (autoCommit) {
                    long commitStart = System.nanoTime();
                    conn.commit();
                    commitMs = elapsedMillis(commitStart);
                }
                
                long totalMs = elapsedMillis(totalStart);
                sparqlLog.info("[SPARQL] UPDATE project={} execTime={}ms commitTime={}ms totalTime={}ms connTime={}ms",
                        projectId, updateExecMs, commitMs, totalMs, connMs);
                log.info("[GRAPHDB] UPDATE completed: execTime={}ms commitTime={}ms totalTime={}ms",
                        updateExecMs, commitMs, totalMs);

                // Phase C: evict the in-memory mirror so the next read repopulates it
                // with the just-written triples. Cheap (microseconds) + safe.
                if (projectRepoCache != null) {
                    projectRepoCache.evict(projectId);
                }

                if (totalMs > 1000) {
                    sparqlLog.warn("[SPARQL_SLOW] UPDATE project={} took {}ms query={}",
                            projectId, totalMs,
                            sparqlUpdate.replaceAll("\\s+", " ").substring(0, Math.min(200, sparqlUpdate.length())));
                }
                
            } catch (Exception e) {
                if (autoCommit) {
                    conn.rollback();
                    log.error("[GRAPHDB] Transaction rolled back for project {}", projectId);
                }
                throw e;
            }
            
        } catch (Exception e) {
            long totalMs = elapsedMillis(totalStart);
            log.error("[GRAPHDB] UPDATE failed for project {} after {}ms: {}", projectId, totalMs, e.getMessage());
            sparqlLog.error("[SPARQL_ERROR] UPDATE project={} duration={}ms error={}", projectId, totalMs, e.getMessage());
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

                // NOTE: The repository is configured with graphdb:ruleset "empty"
                // so there is NO inference engine active. The disableInferenceDuringImport
                // call is skipped because it's a no-op that just adds HTTP round-trips.
                boolean inferenceDisabled = false;

                // Start the import transaction AFTER inference is disabled
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
                    
                    // Intermediate commits keep the transaction alive and prevent GraphDB from
                    // timing out long-running transactions ("transaction not registered" error).
                    // With ruleset "empty" (no inference), commits are cheap — just index updates.
                    // But each commit+begin is 2 HTTP round-trips, and GraphDB indexes on commit,
                    // so committing too often adds significant overhead for large imports.
                    final long COMMIT_TIME_INTERVAL_MS = 60_000L; // Commit every 60 seconds
                    final long COMMIT_TRIPLE_INTERVAL = 200_000L;  // Also commit every 200k triples
                    final AtomicLong lastCommitTime = new AtomicLong(System.currentTimeMillis());
                    final AtomicLong lastCommitTriples = new AtomicLong(0);
                    
                    log.info("[PERFORMANCE] Intermediate commit strategy: Time-based every {}s OR every {} triples (file size: {} MB, batch size: {})",
                        COMMIT_TIME_INTERVAL_MS / 1000, COMMIT_TRIPLE_INTERVAL, fileSizeBytes / (1024 * 1024), batchSize);
                    
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
                                    flushBatchWithRetry(conn, graphBatch, graphForStatement, totalTriples, batchSize);
                                    
                                    // Time-based and count-based intermediate commits
                                    long now = System.currentTimeMillis();
                                    long triplesNow = totalTriples.get();
                                    if (now - lastCommitTime.get() >= COMMIT_TIME_INTERVAL_MS
                                            || triplesNow - lastCommitTriples.get() >= COMMIT_TRIPLE_INTERVAL) {
                                        long commitStart = System.nanoTime();
                                        conn.commit();
                                        conn.begin();
                                        long commitMs = elapsedMillis(commitStart);
                                        lastCommitTime.set(System.currentTimeMillis());
                                        lastCommitTriples.set(triplesNow);
                                        log.info("[TIMING] Intermediate commit at {} triples: {} ms", triplesNow, commitMs);
                                    }
                                }
                                return;
                            }

                            batch.add(st);
                            if (batch.size() >= batchSize) {
                                flushBatchWithRetry(conn, batch, finalTargetGraphIri, totalTriples, batchSize);
                                
                                // Time-based and count-based intermediate commits
                                long now = System.currentTimeMillis();
                                long triplesNow = totalTriples.get();
                                if (now - lastCommitTime.get() >= COMMIT_TIME_INTERVAL_MS
                                        || triplesNow - lastCommitTriples.get() >= COMMIT_TRIPLE_INTERVAL) {
                                    long commitStart = System.nanoTime();
                                    conn.commit();
                                    conn.begin();
                                    long commitMs = elapsedMillis(commitStart);
                                    lastCommitTime.set(System.currentTimeMillis());
                                    lastCommitTriples.set(triplesNow);
                                    log.info("[TIMING] Intermediate commit at {} triples: {} ms", triplesNow, commitMs);
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
                    
                    long parseStart = System.nanoTime();
                    parser.parse(cleanedStream, finalTargetGraphUri);
                    log.info("[TIMING] RDF parsing completed in {} ms ({} triples parsed)", elapsedMillis(parseStart), totalTriples.get());
                    
                    // Upload remaining triples
                    if (partitionByNamespace) {
                        for (Map.Entry<IRI, List<Statement>> entry : partitionBatches.entrySet()) {
                            if (!entry.getValue().isEmpty()) {
                                flushBatchWithRetry(conn, entry.getValue(), entry.getKey(), totalTriples, batchSize);
                            }
                        }
                    } else if (!batch.isEmpty()) {
                        flushBatchWithRetry(conn, batch, finalTargetGraphIri, totalTriples, batchSize);
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

                    // Guard: If the parser produced 0 triples and we cleared the graph,
                    // rollback to preserve the original data instead of committing the clear.
                    if (totalTriples.get() == 0 && shouldClear) {
                        if (conn.isActive()) {
                            conn.rollback();
                        }
                        log.error("❌ ABORTING IMPORT: Parser produced 0 triples from a non-empty file. " +
                                "Rolling back to preserve existing graph data for project: {}", projectId);
                        throw new RuntimeException(
                                "Import produced 0 triples — possible format mismatch or corrupt file. " +
                                "Existing data preserved (not cleared).");
                    }

                    // Commit transaction
                    log.warn("⏳ Committing {} triples to GraphDB...", totalTriples.get());
                    long commitStart = System.nanoTime();
                    conn.commit();
                    long commitDuration = elapsedMillis(commitStart);
                    log.info("✓ FINAL COMMIT completed in {} ms ({} sec)", commitDuration, commitDuration / 1000);

                    // VERIFICATION: Check if data is actually readable after commit (SPARQL COUNT is much faster than conn.size())
                    long verifyStart = System.nanoTime();
                    long verifiedSize = countGraphTriplesSparql(conn, graphUri);
                    log.info("✓ VERIFICATION: Graph {} contains {} triples after commit (check took {} ms)", 
                            graphUri, verifiedSize, elapsedMillis(verifyStart));
                    
                    if (verifiedSize == 0 && totalTriples.get() > 0) {
                        log.error("❌ DATA LOSS DETECTED: Parsed {} triples but graph is empty after commit!", totalTriples.get());
                    }

                    // Invalidate context caches after new data is committed
                    invalidateContextCaches(projectId);

                    // Register any partition graphs that were created during this import
                    if (partitionBatches != null && !partitionBatches.isEmpty()) {
                        List<String> partGraphs = partitionBatches.keySet().stream()
                                .map(IRI::stringValue)
                                .filter(g -> !g.equals(graphUri))
                                .toList();
                        registerPartitionGraphs(projectId, partGraphs);
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
                    log.info("  Average speed: {} triples/sec", (long)((totalTriples.get() * 1000.0) / Math.max(totalDuration, 1)));
                    log.info("═══════════════════════════════════════════════════════════");
                } catch (Exception e) {
                    if (conn.isActive()) {
                        conn.rollback();
                        log.warn("Transaction rolled back for project: {}", projectId);
                    }
                    throw e;
                } finally {
                    // With ruleset "empty", inference is never enabled so no re-enable needed.
                    // Kept as guard for future ruleset changes.
                    if (inferenceDisabled) {
                        // reEnableInferenceNoReindex(conn, valueFactory);
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Chunked bulk load failed for project: {}", projectId, e);
            logCauseChain(e);
            throw new RuntimeException("Chunked bulk load failed: " + e.getMessage(), e);
        }
    }

    /**
     * Import an RDF file by POSTing the entire file directly to GraphDB's REST API
     * in a single HTTP request. GraphDB handles parsing and indexing internally —
     * no client-side batching, no intermediate commits, no transaction management.
     *
     * This is the fastest client-side approach: ONE HTTP request for the entire file.
     * Falls back to chunked if this fails (e.g., GraphDB rejects the request or times out).
     *
     * @param projectId   The project ID (determines the named graph)
     * @param sourceFile  Path to the RDF file on the editor's filesystem
     * @param rdfFormat   The RDF format of the file
     * @param fileSizeBytes The file size in bytes (for logging)
     * @param options     Import options (mode, partition strategy)
     * @param progressListener Optional callback for progress updates
     * @return true if direct upload succeeded, false if it should fall back to chunked
     */
    public boolean directHttpUpload(String projectId,
                                     Path sourceFile,
                                     RDFFormat rdfFormat,
                                     long fileSizeBytes,
                                     ImportOptions options,
                                     ProgressListener progressListener) {
        long start = System.nanoTime();
        ImportOptions resolvedOptions = options != null ? options : ImportOptions.defaults();
        ImportOptions.ImportMode mode = resolvedOptions.getMode() != null
                ? resolvedOptions.getMode()
                : ImportOptions.ImportMode.FULL;

        // Direct upload does not support DIFF mode or namespace partitioning
        if (mode == ImportOptions.ImportMode.DIFF) {
            log.info("[DirectUpload] DIFF mode not supported, falling back to chunked");
            return false;
        }
        if (resolvedOptions.getPartitionStrategy() == ImportOptions.PartitionStrategy.NAMESPACE) {
            log.info("[DirectUpload] Namespace partitioning not supported, falling back to chunked");
            return false;
        }

        String graphUri = getGraphUri(projectId);
        String contentType = rdfFormat.getDefaultMIMEType();

        log.info("[DirectUpload] Starting direct HTTP upload for project: {} | file: {} | size: {} MB | format: {} | content-type: {}",
                projectId, sourceFile.getFileName(), fileSizeBytes / (1024 * 1024), rdfFormat, contentType);

        try {
            // Step 1: If FULL mode, clear the existing graph first via SPARQL
            if (mode == ImportOptions.ImportMode.FULL) {
                long clearStart = System.nanoTime();
                Repository repo = getRepository();
                try (RepositoryConnection conn = repo.getConnection()) {
                    IRI graphIri = conn.getValueFactory().createIRI(graphUri);
                    long existingSize = safeGraphSize(conn, graphIri, "before-clear", projectId);
                    if (existingSize > 0) {
                        conn.begin();
                        clearGraph(conn, graphIri, graphUri, projectId);
                        conn.commit();
                        log.info("[DirectUpload] Cleared existing graph ({} triples) in {} ms",
                                existingSize, elapsedMillis(clearStart));
                    }
                }
            }

            // Step 2: PUT entire file to Fuseki Graph Store Protocol endpoint
            // Fuseki GSP: PUT {gspEndpoint}?graph={graphUri} — atomically replaces named graph
            String url = fusekiGspEndpoint + "?graph=" + URLEncoder.encode(graphUri, StandardCharsets.UTF_8);

            log.info("[DirectUpload] PUTting to Fuseki GSP: {}", url);

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            String gspAuth = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString((fusekiAdminUser + ":" + fusekiAdminPassword)
                            .getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", contentType)
                    .header("Authorization", gspAuth)
                    .PUT(HttpRequest.BodyPublishers.ofFile(sourceFile))
                    .build();

            // Report initial progress
            if (progressListener != null) {
                progressListener.onProgress(new ImportProgress(0, fileSizeBytes, 0, 0));
            }

            long uploadStart = System.nanoTime();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long uploadMs = elapsedMillis(uploadStart);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Step 3: Verify — check graph size (using SPARQL COUNT, much faster than conn.size())
                long verifyStart = System.nanoTime();
                Repository repo = getRepository();
                long verifiedSize = 0;
                try (RepositoryConnection conn = repo.getConnection()) {
                    verifiedSize = countGraphTriplesSparql(conn, graphUri);
                }
                long verifyMs = elapsedMillis(verifyStart);
                long totalMs = elapsedMillis(start);

                // Report final progress
                if (progressListener != null) {
                    progressListener.onProgress(new ImportProgress(fileSizeBytes, fileSizeBytes, verifiedSize, totalMs));
                }

                // Invalidate context caches
                invalidateContextCaches(projectId);

                log.info("═══════════════════════════════════════════════════════════");
                log.info("✓ DIRECT HTTP UPLOAD COMPLETE for project: {}", projectId);
                log.info("  Verified triples: {}", verifiedSize);
                log.info("  TIMING BREAKDOWN:");
                log.info("    • HTTP upload + Fuseki indexing: {} ms ({} sec)", uploadMs, uploadMs / 1000);
                log.info("    • Verification: {} ms", verifyMs);
                log.info("  TOTAL TIME: {} ms ({} seconds)", totalMs, totalMs / 1000);
                if (verifiedSize > 0) {
                    log.info("  Average speed: {} triples/sec", (long) ((verifiedSize * 1000.0) / Math.max(uploadMs, 1)));
                }
                log.info("═══════════════════════════════════════════════════════════");

                if (verifiedSize == 0 && fileSizeBytes > 0) {
                    log.error("❌ DIRECT UPLOAD: Fuseki returned 2xx but graph is empty! Falling back to chunked.");
                    return false;
                }

                return true;
            } else {
                log.warn("[DirectUpload] Fuseki returned HTTP {}: {}. Falling back to chunked.",
                        response.statusCode(), response.body().substring(0, Math.min(500, response.body().length())));
                return false;
            }
        } catch (Exception e) {
            log.warn("[DirectUpload] Failed: {}. Falling back to chunked.", e.getMessage());
            return false;
        }
    }

    /**
     * Fuseki has no server-side import directory API — always falls back to chunked or directHttpUpload.
     */
    public boolean serverSideImport(String projectId,
                                     Path sourceFile,
                                     RDFFormat rdfFormat,
                                     long fileSizeBytes,
                                     ImportOptions options,
                                     ProgressListener progressListener) {
        log.debug("[ServerImport] Not supported on Fuseki — falling back to directHttpUpload / chunked");
        return false;
        /*
        // Legacy GraphDB server-side import body kept for reference — not used with Fuseki
        Path importDir = Paths.get("/opt/graphdb-import");
        if (!Files.isDirectory(importDir)) {
            return false;
        }

        ImportOptions resolvedOptions = options != null ? options : ImportOptions.defaults();
        ImportOptions.ImportMode mode = resolvedOptions.getMode() != null
                ? resolvedOptions.getMode()
                : ImportOptions.ImportMode.FULL;
        boolean shouldClear = mode == ImportOptions.ImportMode.FULL;

        String graphUri = getGraphUri(projectId);
        // Use a unique filename to avoid collisions
        String importFileName = projectId.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + System.currentTimeMillis()
                + "." + rdfFormat.getDefaultFileExtension();
        Path importFile = importDir.resolve(importFileName);

        try {
            // Step 1: Copy file to the shared import directory
            long copyStart = System.nanoTime();
            Files.copy(sourceFile, importFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("[ServerImport {}] File copied to import dir in {} ms ({} bytes)",
                    projectId, elapsedMillis(copyStart), fileSizeBytes);

            if (progressListener != null) {
                progressListener.onProgress(new ImportProgress(fileSizeBytes / 4, fileSizeBytes, 0, elapsedMillis(copyStart)));
            }

            // Step 2: (Graph clearing is handled atomically by replaceGraphs in the REST API call below)
            // This avoids a race where the graph is cleared but import fails mid-way, leaving empty graph

            if (progressListener != null) {
                progressListener.onProgress(new ImportProgress(fileSizeBytes / 2, fileSizeBytes, 0, elapsedMillis(copyStart)));
            }

            // Step 3: Trigger GraphDB server-side import via REST API
            // The file is at /opt/graphdb/home/graphdb-import/<filename> inside the GraphDB container
            long importStart = System.nanoTime();
            log.info("[ServerImport {}] Triggering server-side import: file={}, graph={}", projectId, importFileName, graphUri);

            HttpClient httpClient = HttpClient.newHttpClient();
            String importUrl = graphdbUrl + "/rest/data/import/server/" + URLEncoder.encode(repositoryId, StandardCharsets.UTF_8);

            // GraphDB server-side import request body
            // Use replaceGraphs to atomically clear+import (avoids race where we clear graph then fail mid-import)
            String replaceGraphsJson = shouldClear ? "[\"" + graphUri + "\"]" : "[]";
            String jsonBody = String.format(
                    "{\"fileNames\":[\"%s\"],\"importSettings\":{\"context\":\"%s\",\"replaceGraphs\":%s}}",
                    importFileName, graphUri, replaceGraphsJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(importUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("[ServerImport {}] GraphDB REST API returned {}: {}", projectId, response.statusCode(), response.body());
                return false;
            }
            log.info("[ServerImport {}] Import triggered, polling for completion...", projectId);

            // Step 4: Poll for completion
            // Scale timeout with file size: small files should fail fast, large files get more time
            String statusUrl = graphdbUrl + "/rest/data/import/server/" + URLEncoder.encode(repositoryId, StandardCharsets.UTF_8);
            int maxPolls;
            if (fileSizeBytes < 1024 * 1024) {           // < 1MB: 30 seconds
                maxPolls = 30;
            } else if (fileSizeBytes < 50 * 1024 * 1024) { // < 50MB: 3 minutes
                maxPolls = 180;
            } else {
                maxPolls = 600;                             // >= 50MB: 10 minutes
            }
            log.info("[ServerImport {}] File size: {} bytes, maxPolls: {}", projectId, fileSizeBytes, maxPolls);
            for (int poll = 0; poll < maxPolls; poll++) {
                Thread.sleep(1000);

                HttpRequest statusReq = HttpRequest.newBuilder()
                        .uri(URI.create(statusUrl))
                        .GET()
                        .build();
                HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
                String body = statusResp.body();

                // Check if our file is still in the import list (means still importing or queued)
                if (!body.contains(importFileName)) {
                    // File no longer in list — import finished
                    log.info("[ServerImport {}] Import completed in {} ms", projectId, elapsedMillis(importStart));
                    break;
                }

                // Check for status in the JSON response
                if (body.contains("\"status\":\"DONE\"") && body.contains(importFileName)) {
                    log.info("[ServerImport {}] Import DONE in {} ms", projectId, elapsedMillis(importStart));
                    break;
                }
                if (body.contains("\"status\":\"ERROR\"") && body.contains(importFileName)) {
                    log.error("[ServerImport {}] Import failed: {}", projectId, body);
                    return false;
                }

                // Update progress
                if (progressListener != null && poll % 2 == 0) {
                    long elapsed = elapsedMillis(copyStart);
                    // Estimate progress: 50% is copy done, 50-99% is GraphDB processing
                    int estimatedPercent = (int) Math.min(99, 50 + (poll * 50.0 / maxPolls));
                    progressListener.onProgress(new ImportProgress(
                            (long) (fileSizeBytes * estimatedPercent / 100.0), fileSizeBytes, 0, elapsed));
                }

                // Early exit for small files: if still not done after maxPolls, don't wait more
                if (poll == maxPolls - 1) {
                    log.warn("[ServerImport {}] Polling timed out after {} seconds — falling back to other methods", projectId, maxPolls);
                }
            }

            // Step 5: Verify (using SPARQL COUNT, much faster than conn.size())
            try (RepositoryConnection conn = getRepository().getConnection()) {
                long size = countGraphTriplesSparql(conn, graphUri);
                log.info("[ServerImport {}] ✅ Verification: graph has {} triples", projectId, size);
                if (size == 0) {
                    log.warn("[ServerImport {}] Graph is empty after import — falling back to chunked", projectId);
                    return false;
                }
            }

            if (progressListener != null) {
                progressListener.onProgress(new ImportProgress(fileSizeBytes, fileSizeBytes, 0, elapsedMillis(copyStart)));
            }

            // Invalidate context caches after new data is committed
            invalidateContextCaches(projectId);

            long totalMs = elapsedMillis(copyStart);
            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ SERVER-SIDE IMPORT COMPLETE for project: {}", projectId);
            log.info("  TOTAL TIME: {} ms ({} seconds)", totalMs, totalMs / 1000);
            log.info("═══════════════════════════════════════════════════════════");
            return true;

        } catch (Exception e) {
            log.warn("[ServerImport {}] Server-side import failed, will fall back to chunked: {}", projectId, e.getMessage());
            return false;
        } finally {
            // Clean up the import file
            try { Files.deleteIfExists(importFile); } catch (Exception ignored) {}
        }
        */
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

            log.info("Starting bulk load for project: {} with format: {} (endpoint: {})",
                    projectId, rdfFormat, fusekiQueryEndpoint);

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
                                    log.info("  Progress: {} triples parsed/uploaded in {} ms ({} triples/sec)", 
                                            count, elapsed, (long) rate);
                                    lastLogTime.set(System.currentTimeMillis());
                                }
                            }
                        });
                        parser.parse(fis, graphUri);
                    }
                    long parseDuration = elapsedMillis(addStart);
                    double parseRate = (tripleCounter.get() * 1000.0) / parseDuration;
                    log.info("✓ Parsing & uploading completed in {} ms ({} sec) - {} triples at {} triples/sec", 
                            parseDuration, parseDuration / 1000, tripleCounter.get(), (long) parseRate);
                    
                    // Clean up temp file
                    tempFile.delete();

                    // Get size after loading (SPARQL COUNT is much faster than conn.size())
                    long sizeQueryStart = System.nanoTime();
                    long tripleCount = countGraphTriplesSparql(conn, graphUri);
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
                    log.info("  Average speed: {} triples/sec", (long)((tripleCount * 1000.0) / Math.max(totalDuration, 1)));
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
                log.error("Fuseki dataset not found at {}", fusekiQueryEndpoint);
                log.error("Start Fuseki: docker compose up fuseki");
                throw new RuntimeException("Fuseki dataset not found at " + fusekiQueryEndpoint + ". Is Fuseki running?", e);
            } else {
                log.error("SPARQL repository error for project: {}", projectId, e);
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
                    long countStart = System.nanoTime();
                    var query = conn.prepareTupleQuery(countQuery);
                    try (var result = query.evaluate()) {
                        if (result.hasNext()) {
                            var binding = result.next();
                            var countValue = binding.getValue("count");
                            long count = Long.parseLong(countValue.stringValue());
                            log.info("[TIMING] clearDataset count query for project {}: {} ms ({} triples)", projectId, elapsedMillis(countStart), count);

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
                        long deleteStart = System.nanoTime();
                        conn.prepareUpdate(deleteQuery).execute();
                        log.info("[TIMING] clearDataset DELETE for project {} graph {}: {} ms", projectId, g, elapsedMillis(deleteStart));
                    } catch (Exception e) {
                        log.warn("SPARQL DELETE failed for graph {}: {}. Falling back to conn.clear()", g, e.getMessage());
                        long clearStart = System.nanoTime();
                        conn.clear(conn.getValueFactory().createIRI(g));
                        log.info("[TIMING] clearDataset conn.clear() for project {} graph {}: {} ms", projectId, g, elapsedMillis(clearStart));
                    }
                }
            }

        } catch (org.eclipse.rdf4j.repository.RepositoryException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("not found")) {
                log.error("Fuseki dataset not found. Ensure Fuseki is running: docker compose up fuseki");
                throw new RuntimeException("Fuseki dataset not found at " + fusekiQueryEndpoint + ". Start Fuseki.", e);
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
     * Returns all namespaces registered in the GraphDB repository.
     */
    public Map<String, String> getPrefixes(String projectId) {
        return doGetPrefixes(projectId);
    }
    
    /**
     * Internal: returns prefixes registered in the GraphDB repository.
     * Uses the fast conn.getNamespaces() API instead of scanning all triples.
     */
    private Map<String, String> doGetPrefixes(String projectId) {
        Map<String, String> prefixes = new HashMap<>();
        
        try (RepositoryConnection conn = getRepository().getConnection()) {
            
            long prefixStart = System.nanoTime();
            // Collect all registered namespaces
            Map<String, String> allNamespaces = new HashMap<>();
            for (org.eclipse.rdf4j.model.Namespace ns : conn.getNamespaces()) {
                String prefix = ns.getPrefix();
                if (!prefix.endsWith(":") && !prefix.isEmpty()) {
                    prefix += ":";
                } else if (prefix.isEmpty()) {
                    prefix = ":";
                }
                prefixes.put(prefix, ns.getName());
            }

            log.info("[TIMING] doGetPrefixes for project {}: {} ms ({} prefixes)",
                    projectId, elapsedMillis(prefixStart), prefixes.size());
            
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
            long sizeStart = System.nanoTime();
            List<String> graphs = getAllGraphUris(conn, projectId);
            long total = 0;
            for (String g : graphs) {
                total += countGraphTriplesSparql(conn, g);
            }
            log.info("[TIMING] getDatasetSize for project {}: {} ms ({} triples across {} graphs)", 
                     projectId, elapsedMillis(sizeStart), total, graphs.size());
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
    // GraphDB internal/system namespace prefixes that should not appear in ontology exports
    private static final Set<String> GRAPHDB_SYSTEM_NAMESPACE_PREFIXES = Set.of(
        "sail", "geof", "graphdb", "rdf4j", "sesame", "rep", "sr", "apf", "afn",
        "list", "agg", "omgeo", "geoext", "ofn", "path", "spif", "fn",
        "map", "array", "math", "wgs", "gn", "skos"
    );

    public String exportDataset(String projectId, RDFFormat format) {
        Repository repo = getRepository();
        String graphUri = getGraphUri(projectId);
        
        try (RepositoryConnection conn = repo.getConnection()) {
            
            long exportStart = System.nanoTime();
            StringWriter writer = new StringWriter();
            List<String> graphs = getAllGraphUris(conn, projectId);
            List<IRI> contexts = new ArrayList<>();
            for (String g : graphs) {
                contexts.add(conn.getValueFactory().createIRI(g));
            }

            conn.export(Rio.createWriter(format, writer), contexts.toArray(new IRI[0]));
            
            String result = writer.toString();
            // Post-process: strip GraphDB system xmlns declarations from RDF/XML output
            if (format == org.eclipse.rdf4j.rio.RDFFormat.RDFXML) {
                result = stripSystemNamespaces(result);
            }
            log.info("[TIMING] exportDataset for project {}: {} ms ({} chars, format: {})", 
                     projectId, elapsedMillis(exportStart), result.length(), format);
            return result;
            
        } catch (Exception e) {
            log.error("Failed to export dataset for project: {}", projectId, e);
            throw new RuntimeException("Failed to export dataset", e);
        }
    }
    
    /**
     * Strip GraphDB internal system namespace declarations from RDF/XML export output.
     * GraphDB registers many internal namespaces in its repository config that pollute exports.
     */
    private String stripSystemNamespaces(String rdfXml) {
        // Remove xmlns:PREFIX="..." declarations for known system namespaces
        for (String prefix : GRAPHDB_SYSTEM_NAMESPACE_PREFIXES) {
            rdfXml = rdfXml.replaceAll(
                "\\s+xmlns:" + java.util.regex.Pattern.quote(prefix) + "=\"[^\"]*\"", "");
        }
        return rdfXml;
    }

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
        
        long executeQueryStart = System.nanoTime();
        TupleQuery query = conn.prepareTupleQuery(sparqlQuery);
        TupleQueryResult result = query.evaluate();
        log.info("[TIMING] executeQuery for project {}: {} ms", projectId, elapsedMillis(executeQueryStart));
        return result;
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

    private String extractQueryType(String sparql) {
        if (sparql == null) return "UNKNOWN";
        String trimmed = sparql.stripLeading().toUpperCase();
        // Skip PREFIX declarations
        while (trimmed.startsWith("PREFIX")) {
            int nl = trimmed.indexOf('\n');
            if (nl < 0) break;
            trimmed = trimmed.substring(nl + 1).stripLeading();
        }
        if (trimmed.startsWith("SELECT")) return "SELECT";
        if (trimmed.startsWith("ASK")) return "ASK";
        if (trimmed.startsWith("CONSTRUCT")) return "CONSTRUCT";
        if (trimmed.startsWith("DESCRIBE")) return "DESCRIBE";
        if (trimmed.startsWith("INSERT")) return "INSERT";
        if (trimmed.startsWith("DELETE")) return "DELETE";
        return "QUERY";
    }

    private int resolveBatchSize(long fileSizeBytes) {
        // Larger batches reduce HTTP round-trip overhead.
        // Each batch is one HTTP POST to GraphDB's transaction endpoint.
        // For a 224MB/2.8M triple file at 10K batch size = 280 HTTP requests.
        // At 50K batch size = 56 HTTP requests — 5x fewer round-trips.
        if (fileSizeBytes <= 0) {
            return 5000;
        }
        long mb = fileSizeBytes / (1024 * 1024);
        if (mb >= 200) {
            return 50000;  // Large files: maximize throughput, fewer HTTP round-trips
        }
        if (mb >= 50) {
            return 25000;  // Medium files: balanced
        }
        if (mb >= 10) {
            return 10000;
        }
        return 5000;
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
        long diffDeleteStart = System.nanoTime();
        conn.prepareUpdate(deleteQuery).execute();
        log.info("[TIMING] applyDiffUpdate DELETE for project {}: {} ms", projectId, elapsedMillis(diffDeleteStart));
        log.info("[Diff] Applying insert diff for {}", projectId);
        long diffInsertStart = System.nanoTime();
        conn.prepareUpdate(insertQuery).execute();
        log.info("[TIMING] applyDiffUpdate INSERT for project {}: {} ms", projectId, elapsedMillis(diffInsertStart));
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
        long durationMs = elapsedMillis(start);
        // Log every 50K triples with timing
        if (count % 50000 == 0) {
            log.info("[TIMING] flushBatch: uploaded {} triples so far (this batch: {} triples in {} ms, rate: {} triples/sec)", 
                     count, batch.size(), durationMs, (long)((batch.size() * 1000.0) / Math.max(durationMs, 1)));
        } else if (durationMs > 5000) {
            log.warn("[TIMING] flushBatch slow: {} triples in {} ms (rate: {} triples/sec, total: {})", 
                     batch.size(), durationMs, (long)((batch.size() * 1000.0) / Math.max(durationMs, 1)), count);
        }
        batch.clear();
    }

    /**
     * Flush a batch with retry logic. If GraphDB is temporarily unresponsive (GC pause,
     * high load), wait and retry up to 3 times with exponential backoff.
     */
    private void flushBatchWithRetry(RepositoryConnection conn,
                                     List<Statement> batch,
                                     IRI graphIri,
                                     AtomicLong totalTriples,
                                     int batchSize) {
        int maxRetries = 3;
        long backoffMs = 5_000; // 5s initial backoff
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                flushBatch(conn, batch, graphIri, totalTriples, batchSize);
                return;
            } catch (Exception e) {
                boolean isConnectionError = isConnectionError(e);
                if (!isConnectionError || attempt == maxRetries) {
                    throw e;
                }
                log.warn("flushBatch failed (attempt {}/{}), GraphDB may be under pressure. Retrying in {}ms...",
                        attempt, maxRetries, backoffMs, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
                // Verify GraphDB is reachable before retrying
                if (!waitForGraphDB(15_000)) {
                    throw new RuntimeException("Fuseki did not recover within timeout. Last error: " + e.getMessage(), e);
                }
                backoffMs *= 2;
            }
        }
    }

    /**
     * Check if an exception is a connection-level error (connection refused, timeout, etc.)
     */
    private boolean isConnectionError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof org.apache.http.conn.HttpHostConnectException
                    || (current.getMessage() != null && current.getMessage().contains("Connection refused"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Wait for Fuseki to become reachable, polling every 2 seconds.
     * @return true if Fuseki responds within the timeout
     */
    private boolean waitForGraphDB(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(3))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(fusekiQueryEndpoint))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 || response.statusCode() == 400) {
                    // 400 = endpoint reachable but needs a query param — that's OK
                    log.info("Fuseki is reachable again (status {})", response.statusCode());
                    return true;
                }
            } catch (Exception ignored) {
                // Still not reachable
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.error("GraphDB did not become reachable within {}ms", timeoutMs);
        return false;
    }

    private long safeGraphSize(RepositoryConnection conn, IRI graphIri, String tag, String projectId) {
        try {
            long size = countGraphTriplesSparql(conn, graphIri.stringValue());
            log.info("Project {}: graph size {} during {}", projectId, size, tag);
            return size;
        } catch (Exception e) {
            log.warn("Could not get graph size for project {} during {}: {}", projectId, tag, e.getMessage());
            return -1;
        }
    }

    /**
     * Fast triple count using SPARQL COUNT instead of conn.size().
     * conn.size(graphIri) is extremely slow on GraphDB (20-60s even for small graphs)
     * because it scans the entire index. A SPARQL COUNT query is orders of magnitude faster.
     */
    private long countGraphTriplesSparql(RepositoryConnection conn, String graphUri) {
        String query = "SELECT (COUNT(*) AS ?count) FROM <" + graphUri + "> WHERE { ?s ?p ?o }";
        try (TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
            if (result.hasNext()) {
                BindingSet bs = result.next();
                if (bs.hasBinding("count")) {
                    return Long.parseLong(bs.getValue("count").stringValue());
                }
            }
        }
        return 0;
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
        // Partition graphs are only created during import with PartitionStrategy.NAMESPACE.
        // They are populated into the cache at import time (see bulkLoadChunked).
        // If cache is cold/expired, we return empty — no expensive GraphDB queries needed.
        // getContextIDs() over HTTP takes 20-64 seconds and is not acceptable.
        PartitionGraphs cached = partitionGraphCache.get(projectId);
        if (cached != null) {
            long age = System.currentTimeMillis() - cached.lastUpdatedMs;
            if (age < PARTITION_CACHE_TTL_MS) {
                return cached.graphUris;
            }
        }
        // Cold cache — assume no partitions (base graph only).
        // Partitions will be populated after the next import if applicable.
        log.debug("[TIMING] getPartitionGraphs for project {}: cache miss, returning empty (no expensive query)", projectId);
        return List.of();
    }

    /**
     * Register partition graphs discovered during import into the cache.
     * Called from bulkLoadChunked when partitionByNamespace is enabled.
     */
    public void registerPartitionGraphs(String projectId, List<String> graphs) {
        partitionGraphCache.put(projectId, new PartitionGraphs(graphs, System.currentTimeMillis()));
        log.info("[CACHE] Registered {} partition graphs for project {}", graphs.size(), projectId);
    }

    /**
     * Invalidate the per-project partition cache and Spring-managed caches.
     * Called after successful imports to ensure new graphs are discovered.
     */
    private void invalidateContextCaches(String projectId) {
        partitionGraphCache.remove(projectId);
        // Phase C: drop the in-memory project mirror so imports are visible.
        if (projectRepoCache != null) {
            projectRepoCache.evict(projectId);
        }
        // Evict Spring-managed Caffeine caches that depend on ontology data
        if (cacheManager != null) {
            for (String cacheName : List.of("topLevelClasses", "classChildren", "allClasses",
                    "ontologyProperties", "ontologyIndividuals", "classInstanceCounts",
                    "classDetails", "classAnnotations", "classInstances", "individualCount", "debugInfo", "graphCache")) {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) cache.clear();
            }
            log.info("[CACHE] Evicted all Spring caches after import for project {}", projectId);
        }
        log.info("[CACHE] Invalidated partition cache for project {}", projectId);
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
                "        ?parent a owl:Class . \n" +
                "        FILTER (?parent != owl:Thing && !isBlank(?parent) && isIRI(?parent)) \n" +
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
                "      ?parent a owl:Class . \n" +
                "      FILTER (?parent != owl:Thing && !isBlank(?parent) && isIRI(?parent)) \n" +
                "    } \n" +
                "    FILTER (!BOUND(?parent)) \n" +
                "  } \n" +
                "} \n" +
                "ORDER BY ?label ?class";
            
            log.info("[GRAPHDB] Executing getRootClasses query for project: {}", projectId);
            long rootClassesStart = System.nanoTime();
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
            
            log.info("[TIMING] getRootClassesFromGraphDB for project {}: {} ms ({} root classes)", 
                     projectId, elapsedMillis(rootClassesStart), rootClasses.size());
            
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
            long childClassesStart = System.nanoTime();
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
            
            log.info("[TIMING] getChildClassesFromGraphDB for parent {} in project {}: {} ms ({} children)", 
                     parentClassIri, projectId, elapsedMillis(childClassesStart), childClasses.size());
            
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
            
            long hasChildrenStart = System.nanoTime();
            BooleanQuery booleanQuery = conn.prepareBooleanQuery(query);
            boolean result = booleanQuery.evaluate();
            log.debug("[TIMING] classHasChildren ASK for {}: {} ms (result: {})", classIri, elapsedMillis(hasChildrenStart), result);
            return result;
            
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
     * Optimized: uses char[] directly and avoids String.charAt() overhead on hot path.
     */
    private String sanitizeIriString(String iri) {
        if (iri == null || iri.isEmpty()) return null;

        // Reject IRIs that are only whitespace — GraphDB throws InvalidValueException
        if (iri.isBlank()) {
            log.warn("[IRI-SANITIZE] Skipping blank IRI");
            return "urn:invalid:blank-iri";
        }

        // Fast check: scan for any character that needs encoding.
        // Most IRIs are clean ASCII — exit as early as possible.
        final int len = iri.length();
        for (int i = 0; i < len; i++) {
            char c = iri.charAt(i);
            // Control characters (0x00-0x1F, 0x7F) cause GraphDB InvalidValueException
            if (c < 0x20 || c == 0x7F
                    || c > 127 || c == ' ' || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == '|' || c == '\\' || c == '^' || c == '`'
                    || c == '(' || c == ')') {
                // Found a bad char — do encoding starting from this position
                return sanitizeIriStringFrom(iri, i);
            }
        }
        return null;
    }

    /** Encode bad chars in IRI starting from position {@code start}. */
    private String sanitizeIriStringFrom(String iri, int start) {
        final int len = iri.length();
        StringBuilder sb = new StringBuilder(len + 40);
        sb.append(iri, 0, start); // copy clean prefix
        for (int i = start; i < len; i++) {
            char c = iri.charAt(i);
            // Control characters (0x00-0x1F, 0x7F) — GraphDB rejects these with InvalidValueException
            if (c < 0x20 || c == 0x7F) {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
            else if (c == '[') sb.append("%5B");
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
     * Disable GraphDB's forward-chaining inference engine during bulk import.
     * This is the single biggest performance win: without inference, commits
     * that took 8+ minutes complete in under 30 seconds.
     *
     * Must be called OUTSIDE an active transaction.
     */
    private boolean disableInferenceDuringImport(RepositoryConnection conn, ValueFactory vf) {
        try {
            if (conn.isActive()) {
                conn.commit(); // close any open tx first
            }
            conn.begin();
            IRI sysProp = vf.createIRI("http://www.ontotext.com/owlim/system#inferenceDisabled");
            conn.add(sysProp, sysProp, vf.createLiteral(true));
            conn.commit();
            log.info("⚡ Inference DISABLED for bulk import");
            return true;
        } catch (Exception e) {
            log.warn("Could not disable inference (will proceed with inference enabled): {}", e.getMessage());
            try { if (conn.isActive()) conn.rollback(); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Re-enable inference after import WITHOUT triggering a full reindex.
     * Our SPARQL queries use explicit patterns (owl:Class, rdfs:subClassOf)
     * and do NOT depend on inferred triples, so reindex is unnecessary and
     * would waste 14+ minutes on large ontologies.
     */
    private void reEnableInferenceNoReindex(RepositoryConnection conn, ValueFactory vf) {
        try {
            if (conn.isActive()) {
                conn.commit();
            }
            conn.begin();
            IRI sysProp = vf.createIRI("http://www.ontotext.com/owlim/system#inferenceDisabled");
            conn.remove(sysProp, sysProp, null);
            conn.commit();
            log.info("✓ Inference re-enabled (reindex skipped — not needed for explicit queries)");
        } catch (Exception e) {
            log.warn("Could not re-enable inference (non-critical): {}", e.getMessage());
            try { if (conn.isActive()) conn.rollback(); } catch (Exception ignored) {}
        }
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
