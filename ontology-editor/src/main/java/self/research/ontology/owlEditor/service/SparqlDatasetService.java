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
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.model.ImportOptions;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SparqlDatasetService {

    private static final Logger log = LoggerFactory.getLogger(SparqlDatasetService.class);
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

    @Value("${ontocode.fuseki.shared-graph.max-file-mb:50}")
    private int sharedGraphMaxFileMb;

    @Value("${ontocode.data.dir:./data}")
    private String dataDir;

    @Autowired(required = false)
    private CacheManager cacheManager;

    @Autowired(required = false)
    private OntologySpringCacheEvictionService springCacheEviction;

    @Autowired(required = false)
    private ProjectRepoCache projectRepoCache;

    @Autowired(required = false)
    private TopLevelClassCacheService topLevelCacheService;

    @Autowired(required = false)
    @Lazy
    private HierarchyIndexService hierarchyIndexService;

    @Autowired(required = false)
    @Lazy
    private ClassDetailCacheService classDetailCacheService;

    @Autowired(required = false)
    private OwlApiMutationCoordinator mutationCoordinator;

    @Autowired(required = false)
    private self.research.ontology.owlEditor.repository.ProjectRepository projectRepository;

    @Autowired(required = false)
    private self.research.ontology.owlEditor.repository.DraftSessionRepository draftSessionRepository;

    @Autowired(required = false)
    private MainGraphRevisionService mainGraphRevisionService;

    private Repository repository;

    private final Map<String, Repository> perFileRepositories = new ConcurrentHashMap<>();

    private final java.util.Set<String> sharedFallbackProjects =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final java.net.http.HttpClient SHARED_HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    private final Map<String, String> graphUriCache = new ConcurrentHashMap<>();
    private final Map<String, PartitionGraphs> partitionGraphCache = new ConcurrentHashMap<>();
    private static final long PARTITION_CACHE_TTL_MS = 120_000;

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

    public void init() {
        if (repository == null) {
            log.info("Initializing Fuseki SPARQL connection: query={} update={}", fusekiQueryEndpoint, fusekiUpdateEndpoint);
            try {
                BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(fusekiAdminUser, fusekiAdminPassword));

                String encodedCreds = java.util.Base64.getEncoder().encodeToString(
                        (fusekiAdminUser + ":" + fusekiAdminPassword)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                final String basicAuthHeader = "Basic " + encodedCreds;

                org.apache.http.client.config.RequestConfig requestConfig =
                        org.apache.http.client.config.RequestConfig.custom()
                                .setConnectTimeout(30_000)
                                .setSocketTimeout(7_200_000)
                                .setConnectionRequestTimeout(10_000)
                                .build();

                org.apache.http.impl.conn.PoolingHttpClientConnectionManager connManager =
                        new org.apache.http.impl.conn.PoolingHttpClientConnectionManager();
                connManager.setMaxTotal(60);
                connManager.setDefaultMaxPerRoute(60);
                CloseableHttpClient httpClient = HttpClients.custom()
                        .setConnectionManager(connManager)
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

    public boolean isFusekiReachable() {
        try {
            java.net.URI uri = java.net.URI.create(fusekiQueryEndpoint);
            int port = uri.getPort() != -1
                    ? uri.getPort()
                    : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(uri.getHost(), port), 1_200);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupFusekiAsync() {

        if (projectRepository != null) {
            try {
                projectRepository.findByStatusIn(java.util.List.of("COMPLETED")).forEach(p -> {
                    if (p.getMetadata() != null) {
                        Object stored = p.getMetadata().get("tripleCount");
                        if (stored instanceof Number n && n.longValue() > 0) {
                            tripleCountCache.put(p.getId(), n.longValue());
                        }
                    }
                });
                log.info("[WARMUP] Seeded tripleCountCache for {} COMPLETED projects from MongoDB",
                    tripleCountCache.size());
            } catch (Exception e) {
                log.warn("[WARMUP] tripleCountCache seed failed (non-fatal): {}", e.getMessage());
            }
        }
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[WARMUP] Starting background Fuseki/TDB2 warmup...");
                long start = System.currentTimeMillis();
                Repository repo = getRepository();

                String[][] priorityScans = {
                    {"rdfs:subClassOf", "SELECT (COUNT(*) AS ?c) WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?o }"},
                    {"rdf:type",        "SELECT (COUNT(*) AS ?c) WHERE { ?s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?o }"},
                    {"rdfs:label",      "SELECT (COUNT(*) AS ?c) WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?o }"}
                };
                for (String[] scan : priorityScans) {
                    try (RepositoryConnection conn = repo.getConnection()) {
                        TupleQuery q = conn.prepareTupleQuery(scan[1]);
                        q.setMaxExecutionTime(120);
                        try (TupleQueryResult r = q.evaluate()) {
                            long count = r.hasNext() ? Long.parseLong(r.next().getValue("c").stringValue()) : 0;
                            log.info("[WARMUP] {} index warm: {} triples in {}ms",
                                    scan[0], count, System.currentTimeMillis() - start);
                        }
                    } catch (Exception e) {
                        log.warn("[WARMUP] Priority scan {} failed (non-fatal): {}", scan[0], e.getMessage());
                    }
                }

                try (RepositoryConnection conn = repo.getConnection()) {
                    TupleQuery q = conn.prepareTupleQuery(
                        "SELECT ?g (COUNT(*) AS ?c) WHERE { GRAPH ?g { ?s ?p ?o } } GROUP BY ?g");
                    q.setMaxExecutionTime(600);
                    try (TupleQueryResult r = q.evaluate()) {
                        int graphs = 0;
                        while (r.hasNext()) { r.next(); graphs++; }
                        log.info("[WARMUP] Full warmup complete: {} named graphs in {}ms",
                                graphs, System.currentTimeMillis() - start);
                    }
                }
            } catch (Exception e) {
                log.warn("[WARMUP] Fuseki warmup failed (non-fatal): {}", e.getMessage());
            }
        });
    }

    public Repository getRepository() {
        if (repository == null) {
            init();
        }
        return repository;
    }

    private record ProjectGraphBinding(
            Repository repository,
            String projectId,
            String graphUri,
            String gspBase,
            boolean dedicatedPerFile) {

        static ProjectGraphBinding shared(Repository repo, String projectId, String graphUri, String gspBase) {
            return new ProjectGraphBinding(repo, projectId, graphUri, gspBase, false);
        }

        String namedGraphGspUrl() {
            return gspBase + "?graph=" + URLEncoder.encode(graphUri, StandardCharsets.UTF_8);
        }
    }

    public Repository getRepository(String projectId) {
        return resolveBinding(projectId, false).repository();
    }

    private boolean usesSharedGraphForImport(long fileSizeBytes) {
        if (sharedGraphMaxFileMb <= 0) {
            return false;
        }
        if (fileSizeBytes <= 0) {
            return false;
        }
        return fileSizeBytes < (long) sharedGraphMaxFileMb * 1024L * 1024L;
    }

    private ProjectGraphBinding resolveBindingForImport(String projectId, long fileSizeBytes) {
        String graphUri = getGraphUri(projectId);
        if (usesSharedGraphForImport(fileSizeBytes)) {
            log.info("[SharedGraph] File {} MB < {} MB limit — using shared dataset for project {}",
                    fileSizeBytes / (1024 * 1024), sharedGraphMaxFileMb, projectId);
            return ProjectGraphBinding.shared(getRepository(), projectId, graphUri, fusekiGspEndpoint);
        }
        return resolveBinding(projectId, true);
    }

    private String deriveFusekiBase() {
        java.net.URI endpointUri = java.net.URI.create(fusekiQueryEndpoint);
        return endpointUri.getScheme() + "://" + endpointUri.getHost()
                + (endpointUri.getPort() != -1 ? ":" + endpointUri.getPort() : "");
    }

    private boolean fusekiDatasetExists(String fusekiBase, String datasetName) {
        try {

            String adminUrl = fusekiBase + "/$/datasets/" + datasetName;
            String auth = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString((fusekiAdminUser + ":" + fusekiAdminPassword).getBytes(StandardCharsets.UTF_8));
            java.net.URL url = new java.net.URL(adminUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", auth);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.debug("[PerFileDS] Could not check Fuseki dataset '{}': {}", datasetName, e.getMessage());
            return false;
        }
    }

    private SPARQLRepository connectPerFileRepository(String fusekiBase, String dsName) throws Exception {
        String queryUrl = fusekiBase + "/" + dsName + "/query";
        String updateUrl = fusekiBase + "/" + dsName + "/update";
        SPARQLRepository repo = new SPARQLRepository(queryUrl, updateUrl);
        if (fusekiAdminUser != null && !fusekiAdminUser.isBlank()) {
            BasicCredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(fusekiAdminUser, fusekiAdminPassword));
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setDefaultCredentialsProvider(creds).build();
            SharedHttpClientSessionManager sm = new SharedHttpClientSessionManager();
            sm.setHttpClient(httpClient);
            repo.setHttpClientSessionManager(sm);
        }
        repo.init();
        return repo;
    }

    private ProjectGraphBinding resolveBinding(String projectId, boolean createIfAbsent) {
        String graphUri = getGraphUri(projectId);
        if (projectId == null || projectId.isBlank()) {
            return ProjectGraphBinding.shared(getRepository(), projectId, graphUri, fusekiGspEndpoint);
        }

        Repository cached = perFileRepositories.get(projectId);
        if (cached != null) {
            return new ProjectGraphBinding(cached, projectId, graphUri, getPerFileGspEndpoint(projectId), true);
        }

        String fusekiBase = deriveFusekiBase();
        String dsName = toDatasetName(projectId);
        boolean datasetExists = fusekiDatasetExists(fusekiBase, dsName);

        if (datasetExists || createIfAbsent) {
            try {
                if (createIfAbsent && !datasetExists) {
                    ensureFusekiDataset(fusekiBase, dsName);
                }
                SPARQLRepository repo = connectPerFileRepository(fusekiBase, dsName);

                if (!createIfAbsent && datasetExists) {

                    if (sharedFallbackProjects.contains(projectId)) {
                        return ProjectGraphBinding.shared(getRepository(), projectId, graphUri, fusekiGspEndpoint);
                    }
                    try (RepositoryConnection dedicatedConn = repo.getConnection()) {

                        boolean dedicatedEmpty = !askGraphHasData(dedicatedConn, graphUri);
                        if (dedicatedEmpty) {
                            try (RepositoryConnection sharedConn = getRepository().getConnection()) {
                                boolean sharedHasData = askGraphHasData(sharedConn, graphUri);
                                if (sharedHasData) {
                                    log.info("[PerFileDS] Dedicated dataset '{}' is empty but shared graph has data — using shared for {}",
                                            dsName, projectId);
                                    sharedFallbackProjects.add(projectId);
                                    return ProjectGraphBinding.shared(getRepository(), projectId, graphUri, fusekiGspEndpoint);
                                }
                            }
                        }
                    } catch (Exception sizeEx) {
                        log.debug("[PerFileDS] Could not compare dedicated vs shared size for {}: {}",
                                projectId, sizeEx.getMessage());
                    }
                }

                perFileRepositories.put(projectId, repo);
                log.info("[PerFileDS] Using dedicated Fuseki dataset '{}' for project {} (existed={}, created={})",
                        dsName, projectId, datasetExists, createIfAbsent && !datasetExists);
                return new ProjectGraphBinding(repo, projectId, graphUri, getPerFileGspEndpoint(projectId), true);
            } catch (Exception e) {
                if (createIfAbsent) {
                    log.warn("[PerFileDS] Could not use dedicated dataset for {} — shared fallback: {}",
                            projectId, e.getMessage());
                }
            }
        }

        return ProjectGraphBinding.shared(getRepository(), projectId, graphUri, fusekiGspEndpoint);
    }

    public void evictPerFileDataset(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        perFileRepositories.remove(projectId);
        partitionGraphCache.remove(projectId);
        sharedFallbackProjects.remove(projectId);
        log.info("[PerFileDS] Evicted cached repository for project {}", projectId);
    }

    private String toDatasetName(String projectId) {

        String safe = projectId.replaceAll("[^a-zA-Z0-9\\-]", "-");
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    private void ensureFusekiDataset(String fusekiBase, String datasetName) throws Exception {

        String adminUrl = fusekiBase + "/$/datasets";
        String body = "dbName=" + URLEncoder.encode(datasetName, StandardCharsets.UTF_8)
                    + "&dbType=tdb2";
        String auth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString((fusekiAdminUser + ":" + fusekiAdminPassword).getBytes(StandardCharsets.UTF_8));

        java.net.URL url = new java.net.URL(adminUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Authorization", auth);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        conn.disconnect();
        if (status == 200 || status == 201 || status == 409) {
            return;
        }
        throw new RuntimeException("Fuseki dataset creation returned HTTP " + status);
    }

    public String getPerFileGspEndpoint(String projectId) {
        java.net.URI endpointUri = java.net.URI.create(fusekiQueryEndpoint);
        String base = endpointUri.getScheme() + "://" + endpointUri.getHost()
            + (endpointUri.getPort() != -1 ? ":" + endpointUri.getPort() : "");
        return base + "/" + toDatasetName(projectId) + "/data";
    }

    public String getGraphUri(String projectId) {
        return graphUriCache.computeIfAbsent(projectId, SparqlGraphUris::mainProjectGraph);
    }

    public String getDraftGraphUri(String projectId, String userId) {
        return SparqlGraphUris.userDraftGraph(projectId, userId);
    }

    private String resolveDraftGraphUriForRead(String projectId) {
        String userId = SparqlQueryContext.getUserId();
        if (!isDraftCopyReady(projectId, userId)) {
            return null;
        }
        return getDraftGraphUri(projectId, userId);
    }

    private boolean isDraftCopyReadyForRead(String projectId) {
        String userId = SparqlQueryContext.getUserId();
        return shouldScopeReadsToDraftCopy(projectId, userId);
    }

    private boolean shouldScopeReadsToDraftCopy(String projectId, String userId) {
        return isDraftCopyReady(projectId, userId);
    }

    public boolean hasGraphData(String projectId) {
        try {
            ProjectGraphBinding binding = resolveBinding(projectId, false);
            try (RepositoryConnection conn = binding.repository().getConnection()) {
                return countGraphTriplesSparql(conn, binding.graphUri()) > 0;
            }
        } catch (Exception e) {
            log.warn("[hasGraphData] Could not check graph for project {}: {}", projectId, e.getMessage());
            return false;
        }
    }

    public Map<String, Object> checkFileExistsInGraphDB(String projectId, String fileName, String fileId) {
        Map<String, Object> result = new HashMap<>();
        result.put("exists", false);

        try {
            ProjectGraphBinding graphBinding = resolveBinding(projectId, false);
            String graphUri = graphBinding.graphUri();

            try (RepositoryConnection conn = graphBinding.repository().getConnection()) {

                long graphSize = countGraphTriplesSparql(conn, graphUri);

                String checkQuery = String.format(
                    "ASK { " +
                    "  GRAPH <%s> { " +
                    "    { ?s ?p ?o } " +
                    "  } " +
                    "}",
                    graphUri
                );

                BooleanQuery boolQuery = conn.prepareBooleanQuery(checkQuery);
                long askStart = System.nanoTime();
                boolean hasData = boolQuery.evaluate();
                log.info("[TIMING] checkFileExistsInGraphDB ASK query for project {}: {} ms", projectId, elapsedMillis(askStart));

                if (hasData && graphSize > 0) {

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

                    if (projectRepoCache != null && graphSize >= 1_000_000L) {
                        projectRepoCache.markKnownLarge(projectId);
                        log.info("[GraphDB] Marked project {} as large ontology ({} triples)", projectId, graphSize);
                    }

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

    public Path getProjectPath(String projectId) {
        return Paths.get(dataDir, "projects", projectId);
    }

    public TupleQueryResult execSelect(String projectId, String sparqlQuery) {

        return execSelect(projectId, sparqlQuery, false);
    }

    public boolean isKnownLargeProject(String projectId) {
        return projectRepoCache != null && projectRepoCache.isKnownLarge(projectId);
    }

    public TupleQueryResult execSelect(String projectId, String sparqlQuery, boolean includeInferred) {

        boolean draftScope = isDraftCopyReadyForRead(projectId);
        if (!includeInferred && !draftScope && projectRepoCache != null && projectRepoCache.isEnabled()) {
            TupleQueryResult cached = trySelectFromMemCache(projectId, sparqlQuery);
            if (cached != null) {
                return cached;
            }
        }
        return execSelectGraphDB(projectId, sparqlQuery, includeInferred);
    }

    private TupleQueryResult trySelectFromMemCache(String projectId, String sparqlQuery) {
        long start = System.nanoTime();
        Repository memRepo = projectRepoCache.getOrLoad(projectId, new ProjectRepoCache.Loader() {
            @Override public GraphQueryResult streamTriples(String pid) {
                return execConstructAll(pid);
            }
            @Override public String graphUri(String pid) {
                return getGraphUri(pid);
            }
            @Override public long estimateTripleCount(String pid) {

                try {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            ProjectGraphBinding binding = resolveBinding(pid, false);
                            try (RepositoryConnection conn = binding.repository().getConnection()) {
                                return countGraphTriplesSparql(conn, binding.graphUri());
                            }
                        } catch (Exception e) {
                            log.debug("[MEMCACHE] estimateTripleCount inner error for {}: {}", pid, e.getMessage());
                            return -1L;
                        }
                    }).get(8, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    log.warn("[MEMCACHE] estimateTripleCount timed out for {} (>8s) — skipping cache threshold check", pid);
                    return -1L;
                } catch (Exception e) {
                    log.debug("[MEMCACHE] estimateTripleCount failed for {}: {}", pid, e.getMessage());
                    return -1L;
                }
            }
        });
        if (memRepo == null) {
            return null;
        }
        try (RepositoryConnection conn = memRepo.getConnection()) {
            String finalQuery = sparqlQuery;

            if (!finalQuery.matches("(?si).*\\bFROM\\s+<.*")) {
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

    private GraphQueryResult execConstructAll(String projectId) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        final RepositoryConnection conn = binding.repository().getConnection();
        try {
            String fromClause = buildFromClause(conn, projectId);
            String q = "CONSTRUCT { ?s ?p ?o } " + fromClause + " WHERE { ?s ?p ?o }";
            GraphQuery gq = conn.prepareGraphQuery(q);
            gq.setIncludeInferred(false);
            final GraphQueryResult inner = gq.evaluate();

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

    private TupleQueryResult execSelectGraphDB(String projectId, String sparqlQuery, boolean includeInferred) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        String graphUri = binding.graphUri();
        long totalStart = System.nanoTime();

        try (RepositoryConnection conn = binding.repository().getConnection()) {
            long connMs = (System.nanoTime() - totalStart) / 1_000_000;

            if (sparqlQuery.matches("(?si).*\\bFROM\\s+<.*")) {
                sparqlQuery = sparqlQuery.replaceAll("(?i)\\bFROM\\s+<[^>]+>", " ");
            }
            if (!sparqlQuery.matches("(?si).*\\bFROM\\s+<.*")) {
                sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE",
                    buildFromClause(conn, projectId) + " WHERE");
            }

            String queryType = extractQueryType(sparqlQuery);

            log.info("[GRAPHDB] EXECUTING {} project={} graph={} connTime={}ms", queryType, projectId, graphUri, connMs);
            log.debug("[GRAPHDB] Query: {}", sparqlQuery);

            long queryStart = System.nanoTime();
            TupleQuery query = conn.prepareTupleQuery(sparqlQuery);
            query.setIncludeInferred(includeInferred);
            query.setMaxExecutionTime(300);

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

            sparqlLog.info("[SPARQL] {} project={} results={} queryTime={}ms totalTime={}ms connTime={}ms",
                    queryType, projectId, results.size(), queryMs, totalMs, connMs);

            if (queryMs > 1000) {
                sparqlLog.warn("[SPARQL_SLOW] {} project={} took {}ms results={} query={}",
                        queryType, projectId, queryMs, results.size(),
                        sparqlQuery.replaceAll("\\s+", " ").substring(0, Math.min(200, sparqlQuery.length())));
            }

            log.info("[GRAPHDB] {} completed: {} results in {}ms (conn={}ms, query={}ms)",
                    queryType, results.size(), totalMs, connMs, queryMs);

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

            return new SimpleTupleQueryResult(bindingNames, results);

        } catch (Exception e) {
            long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
            log.error("[GRAPHDB] SELECT failed for project {} after {}ms", projectId, totalMs, e);
            sparqlLog.error("[SPARQL_ERROR] project={} duration={}ms error={}", projectId, totalMs, e.getMessage());

            throw new RuntimeException(e.getMessage() != null ? e.getMessage() : "SPARQL query execution failed", e);
        }
    }

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

    public GraphQueryResult execConstruct(String projectId, String sparqlQuery) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        String graphUri = binding.graphUri();

        try (RepositoryConnection conn = binding.repository().getConnection()) {

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

    public boolean execAsk(String projectId, String sparqlQuery) {
        return execAsk(projectId, sparqlQuery, null);
    }

    public boolean execAskInGraph(String projectId, String graphUri, String sparqlQuery) {
        return execAsk(projectId, sparqlQuery, graphUri);
    }

    private boolean execAsk(String projectId, String sparqlQuery, String forceGraphUri) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        String graphUri = binding.graphUri();

        try (RepositoryConnection conn = binding.repository().getConnection()) {

            if (!sparqlQuery.toUpperCase().contains("FROM")) {
                String fromClause = forceGraphUri != null
                        ? "FROM <" + forceGraphUri + ">"
                        : buildFromClause(conn, projectId);
                if (sparqlQuery.toUpperCase().contains("WHERE")) {
                    sparqlQuery = sparqlQuery.replaceFirst("(?i)WHERE", fromClause + " WHERE");
                } else {

                    sparqlQuery = sparqlQuery.replaceFirst("(?i)(ASK\\s*)\\{", "$1" + fromClause + " WHERE {");
                }
            }

            log.info("[GRAPHDB] Executing ASK query for project: {} graph={}", projectId,
                    forceGraphUri != null ? forceGraphUri : graphUri);
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

    public void execUpdate(String projectId, String sparqlUpdate) {
        execUpdate(projectId, getGraphUri(projectId), sparqlUpdate);
    }

    public void execDraftUpdateCopyOnSwitch(String projectId, String userId, String sparqlUpdate) {
        String draftGraph = getDraftGraphUri(projectId, userId);
        if (sparqlUpdate.toUpperCase().contains("WHERE")
                && !sparqlUpdate.toUpperCase().contains("USING ")) {
            sparqlUpdate = sparqlUpdate.replaceAll("(?i)\\bWHERE\\b", "USING <" + draftGraph + "> WHERE");
        }
        execUpdate(projectId, draftGraph, sparqlUpdate);
    }

    public void clearDraftGraph(String projectId, String userId) {
        String draftGraph = getDraftGraphUri(projectId, userId);

        execUpdate(projectId, draftGraph, "CLEAR SILENT GRAPH <" + draftGraph + ">");
        log.info("[DRAFT-GRAPH] Cleared draft graph {} for project {} user {}", draftGraph, projectId, userId);
    }

    public void moveDraftToMain(String projectId, String userId) {
        String mainGraph = getGraphUri(projectId);
        String draftGraph = getDraftGraphUri(projectId, userId);
        String sparql = "MOVE GRAPH <" + draftGraph + "> TO <" + mainGraph + ">";
        long start = System.nanoTime();
        execUpdate(projectId, mainGraph, sparql);
        evictDraftReadyCache(projectId, userId);
        log.info("[DRAFT-MOVE] MOVE GRAPH draft→main for project {} user {} in {}ms",
                projectId, userId, elapsedMillis(start));
    }

    public void copyMainGraphToDraft(String projectId, String userId) {
        String mainGraph = getGraphUri(projectId);
        String draftGraph = getDraftGraphUri(projectId, userId);
        String sparql = "INSERT { GRAPH <" + draftGraph + "> { ?s ?p ?o } } WHERE { GRAPH <" + mainGraph + "> { ?s ?p ?o } }";
        long start = System.nanoTime();
        execUpdate(projectId, mainGraph, sparql);
        log.info("[DRAFT-COPY] Copied main → draft for project {} user {} in {}ms",
                projectId, userId, elapsedMillis(start));
    }

    public long countDraftTriples(String projectId, String userId) {
        try {
            ProjectGraphBinding binding = resolveBinding(projectId, false);
            String draftGraph = getDraftGraphUri(projectId, userId);
            try (RepositoryConnection conn = binding.repository().getConnection()) {
                return countGraphTriplesSparql(conn, draftGraph);
            }
        } catch (Exception e) {
            log.debug("[DRAFT-GRAPH] count failed for project {} user {}: {}", projectId, userId, e.getMessage());
            return 0;
        }
    }

    public boolean hasActiveDraftSession(String projectId, String userId) {
        return isDraftCopyReady(projectId, userId);
    }

    @Deprecated
    public boolean hasActiveDraftOverlay(String projectId, String userId) {
        return hasActiveDraftSession(projectId, userId);
    }

    private static final long DRAFT_READY_CACHE_TTL_MS = 5_000;
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> draftReadyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isDraftCopyReady(String projectId, String userId) {

        if (!SparqlQueryContext.wantsDraft()) {
            return false;
        }
        if (userId == null || userId.isBlank() || draftSessionRepository == null) {
            return false;
        }
        String key = projectId + " " + userId;
        long[] cached = draftReadyCache.get(key);
        if (cached != null && System.currentTimeMillis() < cached[1]) {
            return cached[0] == 1L;
        }
        boolean result = draftSessionRepository.findByProjectIdAndUserId(projectId, userId)
                .map(s -> s.getCopyStatus() == DraftCopyStatus.READY)
                .orElse(false);
        draftReadyCache.put(key, new long[]{result ? 1L : 0L,
                System.currentTimeMillis() + DRAFT_READY_CACHE_TTL_MS});
        return result;
    }

    public void evictDraftReadyCache(String projectId, String userId) {
        if (projectId != null && userId != null) {
            draftReadyCache.remove(projectId + " " + userId);
        }
    }

    public long countMainGraphTriples(String projectId) {
        try {
            ProjectGraphBinding binding = resolveBinding(projectId, false);
            String mainGraph = getGraphUri(projectId);
            try (RepositoryConnection conn = binding.repository().getConnection()) {
                return countGraphTriplesSparql(conn, mainGraph);
            }
        } catch (Exception e) {
            log.debug("[DRAFT-GRAPH] main graph count failed for project {}: {}", projectId, e.getMessage());
            return 0;
        }
    }

    public String exportNamedGraph(String projectId, String graphUri, org.eclipse.rdf4j.rio.RDFFormat format) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        try (RepositoryConnection conn = binding.repository().getConnection()) {
            java.io.StringWriter writer = new java.io.StringWriter();
            IRI context = conn.getValueFactory().createIRI(graphUri);
            conn.export(org.eclipse.rdf4j.rio.Rio.createWriter(format, writer), context);
            String result = writer.toString();
            if (format == org.eclipse.rdf4j.rio.RDFFormat.RDFXML) {
                result = stripSystemNamespaces(result);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to export graph {} for project {}", graphUri, projectId, e);
            throw new RuntimeException("Failed to export named graph", e);
        }
    }

    public void replaceMainGraphFromRdf(String projectId, String rdfContent, org.eclipse.rdf4j.rio.RDFFormat format) {
        replaceNamedGraphFromRdf(projectId, getGraphUri(projectId), rdfContent, format);
    }

    public void replaceNamedGraphFromRdf(String projectId, String graphUri, String rdfContent,
                                         org.eclipse.rdf4j.rio.RDFFormat format) {
        if (rdfContent == null || rdfContent.isBlank()) {
            throw new IllegalArgumentException("RDF content is empty");
        }

        List<Statement> statements = new ArrayList<>();
        RDFParser parser = Rio.createParser(format);
        parser.setRDFHandler(new StatementCollector(statements));
        try (ByteArrayInputStream in = new ByteArrayInputStream(rdfContent.getBytes(StandardCharsets.UTF_8))) {
            parser.parse(in, graphUri);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse RDF content for graph " + graphUri, e);
        }

        java.io.StringWriter triplesOut = new java.io.StringWriter();
        RDFWriter writer = Rio.createWriter(org.eclipse.rdf4j.rio.RDFFormat.NTRIPLES, triplesOut);
        writer.startRDF();
        for (Statement st : statements) {
            writer.handleStatement(st);
        }
        writer.endRDF();

        String updateText = "CLEAR SILENT GRAPH <" + graphUri + ">;\n"
                + "INSERT DATA { GRAPH <" + graphUri + "> {\n" + triplesOut + "\n} }";

        ProjectGraphBinding binding = resolveBinding(projectId, false);
        try (RepositoryConnection conn = binding.repository().getConnection()) {
            conn.prepareUpdate(updateText).execute();
            invalidateDerivedCachesAfterUpdate(projectId);
            log.info("[DRAFT-GRAPH] Replaced graph {} for project {} ({} triples)", graphUri, projectId, statements.size());
        } catch (Exception e) {
            log.error("Failed to replace graph {} for project {}", graphUri, projectId, e);
            throw new RuntimeException("Failed to replace named graph", e);
        }
    }

    private void execUpdate(String projectId, String targetGraphUri, String sparqlUpdate) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        String graphUri = binding.graphUri();
        long totalStart = System.nanoTime();

        try (RepositoryConnection conn = binding.repository().getConnection()) {
            long connMs = elapsedMillis(totalStart);

            log.info("[GRAPHDB] EXECUTING UPDATE project={} graph={} connTime={}ms", projectId, targetGraphUri, connMs);
            log.debug("[GRAPHDB] Update SPARQL: {}", sparqlUpdate);

            String graphAwareUpdate = injectGraphContext(sparqlUpdate, targetGraphUri);

            boolean autoCommit = conn.isAutoCommit();
            if (autoCommit) {
                conn.begin();
            }

            try {
                long updateExecStart = System.nanoTime();
                Update update = conn.prepareUpdate(graphAwareUpdate);
                update.execute();
                long updateExecMs = elapsedMillis(updateExecStart);

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

                if (projectRepoCache != null) {
                    projectRepoCache.evict(projectId);
                }

                invalidateDerivedCachesAfterUpdate(projectId);

                List<OntologyMutationService.MutationOp> structuredOps = MutationContext.getAndClear();
                if (mutationCoordinator != null) {
                    mutationCoordinator.afterMutation(projectId, structuredOps);
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

    private String injectGraphContext(String sparql, String graphUri) {

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

        if (operationsStr.matches("(?is)INSERT\\s*\\{.*WHERE.*")) {
            if (operationsStr.toUpperCase().contains("USING ")) {

                operationsStr = operationsStr.replaceFirst("(?is)(INSERT(?!\\s+DATA)\\s*\\{)(.*?)(\\}\\s*)(WHERE)",
                        "$1 GRAPH <" + graphUri + "> {$2} } $3$4");
                log.info("[GRAPH-INJECT] Injected GRAPH into INSERT template (USING present)");
            } else if (!operationsStr.trim().toUpperCase().startsWith("WITH")) {
                operationsStr = "WITH <" + graphUri + "> " + operationsStr;
                log.info("[GRAPH-INJECT] Added WITH clause to INSERT...WHERE statement");
            }
            return prefixes.toString() + operationsStr;
        }

        String[] statements = splitUpdateStatements(operationsStr);
        StringBuilder result = new StringBuilder(prefixes);

        log.info("[GRAPH-INJECT] Processing {} statements", statements.length);

        for (int i = 0; i < statements.length; i++) {
            String stmt = statements[i].trim();
            if (stmt.isEmpty()) continue;

            log.info("[GRAPH-INJECT] Statement {}: '{}'", i, stmt.substring(0, Math.min(100, stmt.length())));

            if (stmt.matches("(?is)INSERT\\s+DATA\\s*\\{.*")) {

                stmt = stmt.replaceFirst("(?is)(INSERT\\s+DATA\\s*\\{)", "$1 GRAPH <" + graphUri + "> {");

                stmt = stmt.replaceFirst("(?is)(.*)(\\}\\s*)$", "$1 }$2");
                log.info("[GRAPH-INJECT] Matched INSERT DATA");
            } else if (stmt.matches("(?is)DELETE\\s+DATA\\s*\\{.*")) {

                stmt = stmt.replaceFirst("(?is)(DELETE\\s+DATA\\s*\\{)", "$1 GRAPH <" + graphUri + "> {");

                stmt = stmt.replaceFirst("(?is)(.*)(\\}\\s*)$", "$1 }$2");
                log.info("[GRAPH-INJECT] Matched DELETE DATA");
            }

            else if (stmt.matches("(?is)DELETE\\s*\\{.*") && !stmt.trim().toUpperCase().startsWith("WITH")) {
                if (stmt.toUpperCase().contains("USING ")) {

                    stmt = stmt.replaceFirst("(?is)(DELETE\\s*\\{)(.*?)(\\})",
                            "$1 GRAPH <" + graphUri + "> {$2} $3");
                    stmt = stmt.replaceFirst("(?is)(INSERT(?!\\s+DATA)\\s*\\{)(.*?)(\\})(\\s*WHERE)",
                            "$1 GRAPH <" + graphUri + "> {$2} } $3$4");
                    log.info("[GRAPH-INJECT] Injected GRAPH into DELETE/INSERT templates (USING present)");
                } else {
                    stmt = "WITH <" + graphUri + "> " + stmt;
                    log.info("[GRAPH-INJECT] Matched DELETE WHERE, added WITH clause");
                }
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

                statements.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            statements.add(current.toString().trim());
        }

        return statements.toArray(new String[0]);
    }

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
            ProjectGraphBinding binding = resolveBindingForImport(projectId, fileSizeBytes);
            Repository repo = binding.repository();
            String graphUri = binding.graphUri();

            log.info("Starting CHUNKED bulk load for project: {} with format: {} (batch size: {} triples, dedicated={})",
                    projectId, rdfFormat, batchSize, binding.dedicatedPerFile());

                CountingInputStream countingStream = new CountingInputStream(inputStream);
                InputStream cleanedStream = stripLeadingGarbage(countingStream, rdfFormat);

            long t1 = System.nanoTime();
            try (RepositoryConnection conn = repo.getConnection()) {
                long t2 = System.nanoTime();
                log.info("[TIMING] getRepository: {} ms, getConnection: {} ms",
                        (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);

                var valueFactory = conn.getValueFactory();
                IRI graphIri = valueFactory.createIRI(graphUri);

                boolean inferenceDisabled = false;

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

                    log.info("Creating RDF parser for format: {} (class: {})", rdfFormat, rdfFormat.getClass().getName());
                    RDFParser parser = Rio.createParser(rdfFormat);
                    log.info("Parser created: {} (class: {})", parser, parser.getClass().getName());

                    parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
                    parser.getParserConfig().set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false);
                    parser.getParserConfig().set(BasicParserSettings.NORMALIZE_DATATYPE_VALUES, false);
                    parser.getParserConfig().set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false);

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

                    final String finalTargetGraphUri = targetGraphUri;
                    final IRI finalTargetGraphIri = targetGraphIri;
                    Map<IRI, List<Statement>> partitionBatches = partitionByNamespace ? new HashMap<>() : null;

                    final long COMMIT_TIME_INTERVAL_MS = 60_000L;
                    final long COMMIT_TRIPLE_INTERVAL = 200_000L;
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
                                    flushBatchWithRetry(projectId, conn, graphBatch, graphForStatement, totalTriples, batchSize, fileSizeBytes);

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
                                flushBatchWithRetry(projectId, conn, batch, finalTargetGraphIri, totalTriples, batchSize, fileSizeBytes);

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
                                if ((now - last) >= 2_000_000_000L) {
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

                    cleanedStream.mark(1024);
                    byte[] preview = cleanedStream.readNBytes(500);
                    cleanedStream.reset();
                    String previewStr = new String(preview, java.nio.charset.StandardCharsets.UTF_8);
                    log.info("Stream content preview (first 500 chars): {}", previewStr);

                    long parseStart = System.nanoTime();
                    parser.parse(cleanedStream, finalTargetGraphUri);
                    log.info("[TIMING] RDF parsing completed in {} ms ({} triples parsed)", elapsedMillis(parseStart), totalTriples.get());

                    if (partitionByNamespace) {
                        for (Map.Entry<IRI, List<Statement>> entry : partitionBatches.entrySet()) {
                            if (!entry.getValue().isEmpty()) {
                                flushBatchWithRetry(projectId, conn, entry.getValue(), entry.getKey(), totalTriples, batchSize, fileSizeBytes);
                            }
                        }
                    } else if (!batch.isEmpty()) {
                        flushBatchWithRetry(projectId, conn, batch, finalTargetGraphIri, totalTriples, batchSize, fileSizeBytes);
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

                    log.warn("⏳ Committing {} triples to GraphDB...", totalTriples.get());
                    long commitStart = System.nanoTime();
                    conn.commit();
                    long commitDuration = elapsedMillis(commitStart);
                    log.info("✓ FINAL COMMIT completed in {} ms ({} sec)", commitDuration, commitDuration / 1000);

                    long verifyStart = System.nanoTime();
                    long verifiedSize = countGraphTriplesSparql(conn, graphUri);
                    log.info("✓ VERIFICATION: Graph {} contains {} triples after commit (check took {} ms)",
                            graphUri, verifiedSize, elapsedMillis(verifyStart));

                    if (verifiedSize == 0 && totalTriples.get() > 0) {
                        log.error("❌ DATA LOSS DETECTED: Parsed {} triples but graph is empty after commit!", totalTriples.get());
                        throw new RuntimeException(
                                "Import verification failed: parsed " + totalTriples.get()
                                        + " triples but named graph is empty after commit for project " + projectId);
                    }

                    invalidateContextCaches(projectId);

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

                    if (inferenceDisabled) {

                    }
                }
            }

        } catch (Exception e) {
            log.error("Chunked bulk load failed for project: {}", projectId, e);
            logCauseChain(e);
            throw new RuntimeException("Chunked bulk load failed: " + e.getMessage(), e);
        }
    }

    public boolean directHttpUpload(String projectId,
                                     Path sourceFile,
                                     RDFFormat rdfFormat,
                                     long fileSizeBytes,
                                     ImportOptions options,
                                     ProgressListener progressListener) {
        long start = System.nanoTime();
        ProjectGraphBinding binding = resolveBindingForImport(projectId, fileSizeBytes);
        String graphUri = binding.graphUri();

        ImportOptions resolvedOptions = options != null ? options : ImportOptions.defaults();
        ImportOptions.ImportMode mode = resolvedOptions.getMode() != null
                ? resolvedOptions.getMode()
                : ImportOptions.ImportMode.FULL;

        if (mode == ImportOptions.ImportMode.DIFF) {
            log.info("[DirectUpload] DIFF mode not supported, falling back to chunked");
            return false;
        }
        if (resolvedOptions.getPartitionStrategy() == ImportOptions.PartitionStrategy.NAMESPACE) {
            log.info("[DirectUpload] Namespace partitioning not supported, falling back to chunked");
            return false;
        }

        String contentType = rdfFormat.getDefaultMIMEType();

        log.info("[DirectUpload] Starting for project: {} | dedicated={} | file: {} | size: {} MB | format: {}",
                projectId, binding.dedicatedPerFile(), sourceFile.getFileName(),
                fileSizeBytes / (1024 * 1024), rdfFormat);

        try {
            if (mode == ImportOptions.ImportMode.FULL) {
                long clearStart = System.nanoTime();
                try (RepositoryConnection conn = binding.repository().getConnection()) {
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

            String url = binding.namedGraphGspUrl();
            log.info("[DirectUpload] PUTting to Fuseki GSP: {}", url);

            String gspAuth = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString((fusekiAdminUser + ":" + fusekiAdminPassword)
                            .getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", contentType)
                    .header("Authorization", gspAuth)
                    .PUT(HttpRequest.BodyPublishers.ofFile(sourceFile))
                    .timeout(directUploadTimeout(fileSizeBytes))
                    .build();

            if (progressListener != null) {
                progressListener.onProgress(new ImportProgress(0, fileSizeBytes, 0, 0));
            }

            java.util.concurrent.atomic.AtomicBoolean uploadDone = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread progressHeartbeat = null;
            if (progressListener != null) {
                progressHeartbeat = Thread.ofVirtual().name("direct-upload-progress").start(() -> {
                    long heartbeatStart = System.nanoTime();
                    while (!uploadDone.get()) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (uploadDone.get()) {
                            return;
                        }
                        progressListener.onProgress(new ImportProgress(0, fileSizeBytes, 0, elapsedMillis(heartbeatStart)));
                    }
                });
            }

            long uploadStart = System.nanoTime();
            HttpResponse<String> response;
            try {
                response = SHARED_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            } finally {
                uploadDone.set(true);
                if (progressHeartbeat != null) {
                    progressHeartbeat.interrupt();
                }
            }
            long uploadMs = elapsedMillis(uploadStart);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = response.body();
                log.warn("[DirectUpload] Fuseki returned HTTP {}: {}. Falling back to chunked.",
                        response.statusCode(),
                        body.substring(0, Math.min(500, body.length())));
                return false;
            }

            long verifyStart = System.nanoTime();
            long verifiedSize = waitForGraphTriplesAfterDirectUpload(
                    binding.repository(), graphUri, projectId, fileSizeBytes);
            long verifyMs = elapsedMillis(verifyStart);
            long totalMs = elapsedMillis(start);

            if (progressListener != null) {
                progressListener.onProgress(new ImportProgress(fileSizeBytes, fileSizeBytes, verifiedSize, totalMs));
            }

            if (verifiedSize == 0 && fileSizeBytes > 0) {
                throw new RuntimeException(
                        "Direct HTTP upload verification failed: Fuseki returned HTTP "
                                + response.statusCode() + " but named graph is empty for project " + projectId);
            }

            invalidateContextCaches(projectId);

            log.info("═══════════════════════════════════════════════════════════");
            log.info("✓ DIRECT HTTP UPLOAD COMPLETE for project: {}", projectId);
            log.info("  Verified triples: {}", verifiedSize);

            if (verifiedSize > 0) {
                tripleCountCache.put(projectId, verifiedSize);
                if (verifiedSize >= 1_000_000L && projectRepoCache != null) {
                    projectRepoCache.markKnownLarge(projectId);
                    log.info("  [tripleCountCache] Seeded {} triples, marked knownLarge", verifiedSize);
                } else {
                    log.info("  [tripleCountCache] Seeded {} triples", verifiedSize);
                }
            }
            log.info("  TIMING BREAKDOWN:");
            log.info("    • HTTP upload + Fuseki indexing: {} ms ({} sec)", uploadMs, uploadMs / 1000);
            log.info("    • Verification: {} ms", verifyMs);
            log.info("  TOTAL TIME: {} ms ({} seconds)", totalMs, totalMs / 1000);
            if (verifiedSize > 0) {
                log.info("  Average speed: {} triples/sec", (long) ((verifiedSize * 1000.0) / Math.max(uploadMs, 1)));
            }
            log.info("═══════════════════════════════════════════════════════════");
            return true;
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Direct HTTP upload verification failed")) {
                throw e;
            }
            log.warn("[DirectUpload] Failed: {}. Falling back to chunked.", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[DirectUpload] Failed: {}. Falling back to chunked.", e.getMessage());
            return false;
        }
    }

    public boolean serverSideImport(String projectId,
                                     Path sourceFile,
                                     RDFFormat rdfFormat,
                                     long fileSizeBytes,
                                     ImportOptions options,
                                     ProgressListener progressListener) {
        log.debug("[ServerImport] Not supported on Fuseki — falling back to directHttpUpload / chunked");
        return false;

    }

    public void bulkLoad(String projectId, InputStream inputStream, RDFFormat rdfFormat) {
        long bulkLoadStart = System.nanoTime();
        try {
            Repository repo = getRepository();
            String graphUri = getGraphUri(projectId);

            log.info("Starting bulk load for project: {} with format: {} (endpoint: {})",
                    projectId, rdfFormat, fusekiQueryEndpoint);

            InputStream bomStrippedStream = new org.apache.commons.io.input.BOMInputStream(
                inputStream,
                org.apache.commons.io.ByteOrderMark.UTF_8,
                org.apache.commons.io.ByteOrderMark.UTF_16LE,
                org.apache.commons.io.ByteOrderMark.UTF_16BE
            );

            InputStream bufferedStream = new java.io.BufferedInputStream(bomStrippedStream, 65536);

            try (RepositoryConnection conn = repo.getConnection()) {
                var valueFactory = conn.getValueFactory();
                IRI graphIri = valueFactory.createIRI(graphUri);

                boolean originalAutoCommit = conn.isAutoCommit();

                if (originalAutoCommit) {
                    conn.setAutoCommit(false);
                }

                if (!conn.isActive()) {
                    conn.begin();
                    log.info("Started new transaction for bulk load");
                } else {
                    log.info("Using existing active transaction");
                }

                log.info("Opened GraphDB connection for {} (autoCommit={}, transaction active, isolation={})",
                        projectId, conn.isAutoCommit(), safeIsolationLevel(conn));

                java.io.File tempFile = null;
                try {

                    long sizeBeforeClear = safeGraphSize(conn, graphIri, "before-clear", projectId);
                    if (sizeBeforeClear >= 0) {
                        log.info("Project {}: {} triples detected before clear", projectId, sizeBeforeClear);
                    }

                    if (sizeBeforeClear > 0) {
                        clearGraph(conn, graphIri, graphUri, projectId);
                    } else {
                        log.info("Graph {} already empty, skipping clear", graphUri);
                    }

                    log.info("Loading data into GraphDB graph: {}", graphUri);

                    tempFile = java.io.File.createTempFile("graphdb-upload-", ".rdf");
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

                    long addStart = System.nanoTime();
                    final AtomicLong tripleCounter = new AtomicLong(0);
                    final AtomicLong lastLogTime = new AtomicLong(System.currentTimeMillis());
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {

                        org.eclipse.rdf4j.rio.RDFParser parser = org.eclipse.rdf4j.rio.Rio.createParser(rdfFormat);

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

                                if (count % 50000 == 0 || (System.currentTimeMillis() - lastLogTime.get() > 30000)) {
                                    long elapsed = elapsedMillis(addStart);
                                    double rate = (count * 1000.0) / elapsed;
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

                    long sizeQueryStart = System.nanoTime();
                    long tripleCount = countGraphTriplesSparql(conn, graphUri);
                    log.info("Graph size computed in {} ms", elapsedMillis(sizeQueryStart));

                    log.warn("⏳ Committing {} triples to GraphDB - this may take several minutes for large ontologies...", tripleCount);
                    long commitStart = System.nanoTime();
                    conn.commit();
                    long commitDuration = elapsedMillis(commitStart);
                    log.info("✓ Transaction committed in {} ms ({} seconds)",
                            commitDuration, commitDuration / 1000);

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

                    try {
                        if (conn.isActive()) {
                            conn.rollback();
                            log.warn("Transaction rolled back for project: {}", projectId);
                        }
                    } catch (Exception rollbackEx) {
                        log.error("Failed to rollback transaction", rollbackEx);
                    }
                    throw e;
                } finally {
                    if (tempFile != null) tempFile.delete();
                }
            }

        } catch (org.eclipse.rdf4j.rio.RDFParseException e) {
            log.error("RDF parsing failed for project: {}. Parse error: {}", projectId, e.getMessage());
            log.error("Error at line {}, column {}", e.getLineNumber(), e.getColumnNumber());

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

    private void clearGraphsInRepository(Repository repo, String projectId) {
        try (RepositoryConnection conn = repo.getConnection()) {
            List<String> graphs = getAllGraphUris(conn, projectId);
            String countQuery = buildCountQuery(graphs);

            try {
                long countStart = System.nanoTime();
                var query = conn.prepareTupleQuery(countQuery);
                try (var result = query.evaluate()) {
                    if (result.hasNext()) {
                        var binding = result.next();
                        var countValue = binding.getValue("count");
                        long count = Long.parseLong(countValue.stringValue());
                        log.info("[TIMING] clearDataset count for project {}: {} ms ({} triples)",
                                projectId, elapsedMillis(countStart), count);
                        if (count == 0) {
                            return;
                        }
                        log.info("Found {} triples to clear for project: {}", count, projectId);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not count triples, proceeding with clear: {}", e.getMessage());
            }

            for (String g : graphs) {
                long dropStart = System.nanoTime();
                try {
                    conn.prepareUpdate(String.format("DROP SILENT GRAPH <%s>", g)).execute();
                    log.info("[TIMING] clearDataset DROP GRAPH for project {} graph {}: {} ms",
                            projectId, g, elapsedMillis(dropStart));
                } catch (Exception e) {
                    log.warn("DROP SILENT GRAPH failed for graph {}: {}", g, e.getMessage());
                }
            }
        }
    }

    public void clearDataset(String projectId) {
        try {
            String graphUri = getGraphUri(projectId);
            ProjectGraphBinding dedicated = resolveBinding(projectId, false);
            if (dedicated.dedicatedPerFile()) {
                log.info("Clearing dedicated Fuseki dataset for project: {} (graph: {})", projectId, graphUri);
                clearGraphsInRepository(dedicated.repository(), projectId);
            }
            ProjectGraphBinding shared = ProjectGraphBinding.shared(
                    getRepository(), projectId, graphUri, fusekiGspEndpoint);
            if (!dedicated.dedicatedPerFile()) {
                log.info("Clearing shared dataset for project: {} (graph: {})", projectId, graphUri);
            } else {
                log.info("Clearing legacy shared named graph for project: {} (graph: {})", projectId, graphUri);
            }
            clearGraphsInRepository(shared.repository(), projectId);
            evictPerFileDataset(projectId);
            invalidateContextCaches(projectId);

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

    public Map<String, String> getPrefixes(String projectId) {
        return doGetPrefixes(projectId);
    }

    private Map<String, String> doGetPrefixes(String projectId) {
        Map<String, String> prefixes = new HashMap<>();

        try (RepositoryConnection conn = resolveBinding(projectId, false).repository().getConnection()) {

            long prefixStart = System.nanoTime();
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

    public void setPrefixes(String projectId, Map<String, String> prefixes) {
        try (RepositoryConnection conn = resolveBinding(projectId, false).repository().getConnection()) {

            for (Map.Entry<String, String> entry : prefixes.entrySet()) {
                String prefix = entry.getKey();

                if (prefix.endsWith(":")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                } else if (prefix.equals(":")) {
                    prefix = "";
                }
                conn.setNamespace(prefix, entry.getValue());
            }

            log.debug("Set {} prefixes for project: {}", prefixes.size(), projectId);

        } catch (Exception e) {
            log.error("Failed to set prefixes for project: {}", projectId, e);
            throw new RuntimeException("Failed to set prefixes", e);
        }
    }

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

    public void removePrefix(String projectId, String prefix) {
        try (RepositoryConnection conn = resolveBinding(projectId, false).repository().getConnection()) {
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

    public long getDatasetSize(String projectId) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        String graphUri = binding.graphUri();

        try (RepositoryConnection conn = binding.repository().getConnection()) {
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

    public boolean datasetExists(String projectId) {
        return getDatasetSize(projectId) > 0;
    }

    private static final Set<String> GRAPHDB_SYSTEM_NAMESPACE_PREFIXES = Set.of(
        "sail", "geof", "graphdb", "rdf4j", "sesame", "rep", "sr", "apf", "afn",
        "list", "agg", "omgeo", "geoext", "ofn", "path", "spif", "fn",
        "map", "array", "math", "wgs", "gn", "skos"
    );

    public String exportDataset(String projectId, RDFFormat format) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);

        try (RepositoryConnection conn = binding.repository().getConnection()) {

            long exportStart = System.nanoTime();
            StringWriter writer = new StringWriter();
            List<String> graphs = getAllGraphUris(conn, projectId);
            List<IRI> contexts = new ArrayList<>();
            for (String g : graphs) {
                contexts.add(conn.getValueFactory().createIRI(g));
            }

            conn.export(Rio.createWriter(format, writer), contexts.toArray(new IRI[0]));

            String result = writer.toString();

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

    public void exportDatasetToStream(String projectId, RDFFormat format, OutputStream out) {
        exportDatasetToStream(projectId, null, format, out);
    }

    public void exportDatasetToStream(String projectId, String userId, RDFFormat format, OutputStream out) {
        ProjectGraphBinding binding = resolveBinding(projectId, false);
        try (RepositoryConnection conn = binding.repository().getConnection()) {
            long exportStart = System.nanoTime();
            List<IRI> contexts = new ArrayList<>();
            if (userId != null && !userId.isBlank() && shouldScopeReadsToDraftCopy(projectId, userId)) {
                contexts.add(conn.getValueFactory().createIRI(getDraftGraphUri(projectId, userId)));
            } else {
                for (String g : getAllGraphUris(conn, projectId)) {
                    contexts.add(conn.getValueFactory().createIRI(g));
                }
            }
            conn.export(
                Rio.createWriter(format, new OutputStreamWriter(out, StandardCharsets.UTF_8)),
                contexts.toArray(new IRI[0])
            );
            log.info("[TIMING] exportDatasetToStream for project {}: {} ms (format: {}, draftSession: {})",
                     projectId, elapsedMillis(exportStart), format, userId != null && !userId.isBlank());
        } catch (Exception e) {
            log.error("Failed to stream-export dataset for project: {}", projectId, e);
            throw new RuntimeException("Failed to stream-export dataset", e);
        }
    }

    private String stripSystemNamespaces(String rdfXml) {

        for (String prefix : GRAPHDB_SYSTEM_NAMESPACE_PREFIXES) {
            java.util.regex.Pattern usagePattern = java.util.regex.Pattern.compile(
                "[<\\s\"']" + java.util.regex.Pattern.quote(prefix) + ":[A-Za-z_]");
            if (usagePattern.matcher(rdfXml).find()) {

                continue;
            }
            rdfXml = rdfXml.replaceAll(
                "\\s+xmlns:" + java.util.regex.Pattern.quote(prefix) + "=\"[^\"]*\"", "");
        }
        return rdfXml;
    }

    public RepositoryConnection getConnection() {
        return getRepository().getConnection();
    }

    public TupleQueryResult executeQuery(RepositoryConnection conn, String projectId, String sparqlQuery) {
        String graphUri = getGraphUri(projectId);

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

    private java.time.Duration gspRequestTimeout(long fileSizeBytes) {
        long mb = fileSizeBytes > 0 ? fileSizeBytes / (1024 * 1024) : 0;
        if (mb >= 200) {
            return java.time.Duration.ofMinutes(45);
        }
        if (mb >= 50) {
            return java.time.Duration.ofMinutes(25);
        }
        return java.time.Duration.ofMinutes(10);
    }

    private java.time.Duration directUploadTimeout(long fileSizeBytes) {
        long mb = fileSizeBytes > 0 ? fileSizeBytes / (1024 * 1024) : 0;
        if (mb >= 200) {
            return java.time.Duration.ofMinutes(120);
        }
        if (mb >= 50) {
            return java.time.Duration.ofMinutes(60);
        }
        return java.time.Duration.ofMinutes(30);
    }

    private long waitForGraphTriplesAfterDirectUpload(Repository repository,
                                                      String graphUri,
                                                      String projectId,
                                                      long fileSizeBytes) throws Exception {
        long mb = fileSizeBytes > 0 ? fileSizeBytes / (1024 * 1024) : 0;
        int maxAttempts = mb >= 200 ? 90 : (mb >= 50 ? 60 : 30);
        long pollMs = mb >= 200 ? 10_000L : 5_000L;

        long verifiedSize = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (RepositoryConnection conn = repository.getConnection()) {
                verifiedSize = countGraphTriplesSparql(conn, graphUri);
            }
            if (verifiedSize > 0) {
                if (attempt > 1) {
                    log.info("[DirectUpload] Graph populated after {} verification polls ({} triples) for {}",
                            attempt, verifiedSize, projectId);
                }
                return verifiedSize;
            }
            if (attempt < maxAttempts) {
                log.info("[DirectUpload] Waiting for Fuseki indexing (poll {}/{}, project={})",
                        attempt, maxAttempts, projectId);
                Thread.sleep(pollMs);
            }
        }
        return verifiedSize;
    }

    private final Map<String, Long> tripleCountCache = new ConcurrentHashMap<>();

    public long getGraphTripleCount(String projectId) {
        Long cached = tripleCountCache.get(projectId);
        if (cached != null) {
            return cached;
        }
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    ProjectGraphBinding binding = resolveBinding(projectId, false);
                    try (RepositoryConnection conn = binding.repository().getConnection()) {
                        long count = countGraphTriplesSparql(conn, binding.graphUri());
                        if (count > 0) tripleCountCache.put(projectId, count);
                        return count;
                    }
                } catch (Exception e) {
                    log.debug("[getGraphTripleCount] inner error for {}: {}", projectId, e.getMessage());
                    return -1L;
                }
            }).get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("[getGraphTripleCount] COUNT timed out for {} (>8s) — returning -1", projectId);
            return -1;
        } catch (Exception e) {
            log.debug("[getGraphTripleCount] Could not count triples for {}: {}", projectId, e.getMessage());
            return -1;
        }
    }

    private int resolveBatchSize(long fileSizeBytes) {

        if (fileSizeBytes <= 0) {
            return 5000;
        }
        long mb = fileSizeBytes / (1024 * 1024);
        if (mb >= 200) {
            return 50000;
        }
        if (mb >= 50) {
            return 25000;
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

            if (head.length >= 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF) {
                log.info("Detected UTF-8 BOM, will strip 3 bytes");
                contentStart = 3;
            }

            if (contentStart == -1) {
                for (int i = 0; i < head.length; i++) {
                    byte b = head[i];

                    if (b != 32 && b != 9 && b != 10 && b != 13) {
                        if (i > 0) {
                            contentStart = i;
                            log.info("Found {} bytes of leading whitespace, will strip", i);
                        }
                        break;
                    }
                }
            }

            if (format == RDFFormat.RDFXML) {

                if (contentStart == -1 || contentStart > 100) {
                    for (int i = 0; i < head.length - 5; i++) {
                        if (head[i] == '<' && head[i + 1] == '?' &&
                                head[i + 2] == 'x' && head[i + 3] == 'm' && head[i + 4] == 'l') {
                            contentStart = i;
                            log.info("Found <?xml declaration at byte offset: {}", i);
                            break;
                        }
                    }
                }

                if (contentStart == -1) {
                    String headStr = new String(head, 0, Math.min(head.length, 1024), java.nio.charset.StandardCharsets.UTF_8);
                    int rdfIndex = headStr.indexOf("<rdf:RDF");
                    if (rdfIndex >= 0) {
                        contentStart = rdfIndex;
                        log.info("Found <rdf:RDF tag at character offset: {}", rdfIndex);
                    }
                }

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

                if (contentStart == -1 || contentStart > 100) {
                    String headStr = new String(head, 0, Math.min(head.length, 1024), java.nio.charset.StandardCharsets.UTF_8);
                    int prefixIndex = headStr.indexOf("@prefix");
                    if (prefixIndex < 0) {
                        prefixIndex = headStr.indexOf("@base");
                    }
                    if (prefixIndex >= 0 && prefixIndex < 100) {
                        contentStart = prefixIndex;
                        log.info("Found Turtle directive at character offset: {}", prefixIndex);
                    }
                }
            }

            if (contentStart == -1) {
                log.info("No garbage bytes detected, using original stream");
                buffered.reset();
                return buffered;
            }

            if (contentStart == 0) {
                log.info("Content starts at byte 0, no stripping needed");
                buffered.reset();
                return buffered;
            }

            log.warn("Stripping {} bytes of garbage before {} content", contentStart, format);
            byte[] trimmed = new byte[head.length - contentStart];
            System.arraycopy(head, contentStart, trimmed, 0, trimmed.length);
            return new SequenceInputStream(new ByteArrayInputStream(trimmed), buffered);
        } catch (Exception e) {
            log.warn("Failed to strip leading bytes from {} stream: {}", format, e.getMessage());
            return inputStream;
        }
    }

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

    private void flushBatch(String projectId,
                            RepositoryConnection conn,
                            List<Statement> batch,
                            IRI graphIri,
                            AtomicLong totalTriples,
                            int batchSize,
                            long fileSizeBytes) {
        if (batch.isEmpty()) {
            return;
        }

        long start = System.nanoTime();
        try {
            postBatchToGSP(projectId, batch, graphIri.stringValue(), fileSizeBytes);
        } catch (Exception e) {
            log.error("Failed to add batch of {} statements to graph {}. Error: {}",
                    batch.size(), graphIri, e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
        long count = totalTriples.addAndGet(batch.size());
        long durationMs = elapsedMillis(start);

        if (count % 50000 == 0) {
            log.info("[TIMING] flushBatch: uploaded {} triples so far (this batch: {} triples in {} ms, rate: {} triples/sec)",
                     count, batch.size(), durationMs, (long)((batch.size() * 1000.0) / Math.max(durationMs, 1)));
        } else if (durationMs > 5000) {
            log.warn("[TIMING] flushBatch slow: {} triples in {} ms (rate: {} triples/sec, total: {})",
                     batch.size(), durationMs, (long)((batch.size() * 1000.0) / Math.max(durationMs, 1)), count);
        }
        batch.clear();
    }

    private void postBatchToGSP(String projectId, List<Statement> batch, String graphUri, long fileSizeBytes) {
        if (batch.isEmpty()) return;

        StringWriter sw = new StringWriter(batch.size() * 80);
        try {
            org.eclipse.rdf4j.rio.RDFWriter tw = Rio.createWriter(RDFFormat.TURTLE, sw);
            tw.startRDF();
            for (Statement st : batch) {
                tw.handleStatement(st);
            }
            tw.endRDF();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize batch to Turtle: " + e.getMessage(), e);
        }

        ProjectGraphBinding binding = resolveBinding(projectId, false);
        String url = binding.namedGraphGspUrl();
        String auth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((fusekiAdminUser + ":" + fusekiAdminPassword).getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest gspReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "text/turtle")
                    .header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(sw.toString(), StandardCharsets.UTF_8))
                    .timeout(gspRequestTimeout(fileSizeBytes))
                    .build();
            HttpResponse<String> resp = SHARED_HTTP_CLIENT.send(gspReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("GSP POST HTTP " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(200, resp.body().length())));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GSP POST failed: " + e.getMessage(), e);
        }
    }

    private void flushBatchWithRetry(String projectId,
                                     RepositoryConnection conn,
                                     List<Statement> batch,
                                     IRI graphIri,
                                     AtomicLong totalTriples,
                                     int batchSize,
                                     long fileSizeBytes) {
        int maxRetries = 3;
        long backoffMs = 5_000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                flushBatch(projectId, conn, batch, graphIri, totalTriples, batchSize, fileSizeBytes);
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

                if (!waitForGraphDB(15_000)) {
                    throw new RuntimeException("Fuseki did not recover within timeout. Last error: " + e.getMessage(), e);
                }
                backoffMs *= 2;
            }
        }
    }

    private boolean isConnectionError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof org.apache.http.conn.HttpHostConnectException
                    || (current.getMessage() != null && (
                            current.getMessage().contains("Connection refused")
                            || current.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timed out")
                            || current.getMessage().contains("no bytes")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

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

                    log.info("Fuseki is reachable again (status {})", response.statusCode());
                    return true;
                }
            } catch (Exception ignored) {

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

    private long countGraphTriplesSparql(RepositoryConnection conn, String graphUri) {
        String query = "SELECT (COUNT(*) AS ?count) FROM <" + graphUri + "> WHERE { ?s ?p ?o }";
        try (TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
            if (result.hasNext()) {
                BindingSet bs = result.next();
                if (bs.hasBinding("count")) {
                    try {
                        return Long.parseLong(bs.getValue("count").stringValue());
                    } catch (NumberFormatException e) {
                        log.warn("Unexpected COUNT value for graph {}: {}", graphUri, bs.getValue("count"));
                    }
                }
            }
        }
        return 0;
    }

    private boolean askGraphHasData(RepositoryConnection conn, String graphUri) {
        String query = "ASK { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
        try {
            return conn.prepareBooleanQuery(query).evaluate();
        } catch (Exception e) {
            log.debug("[askGraphHasData] ASK failed for graph {}: {}", graphUri, e.getMessage());
            return false;
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
        String userId = SparqlQueryContext.getUserId();
        if (shouldScopeReadsToDraftCopy(projectId, userId)) {
            return "FROM <" + getDraftGraphUri(projectId, userId) + ">";
        }
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

        PartitionGraphs cached = partitionGraphCache.get(projectId);
        if (cached != null) {
            long age = System.currentTimeMillis() - cached.lastUpdatedMs;
            if (age < PARTITION_CACHE_TTL_MS) {
                return cached.graphUris;
            }
        }

        log.debug("[TIMING] getPartitionGraphs for project {}: cache miss, returning empty (no expensive query)", projectId);
        return List.of();
    }

    public void registerPartitionGraphs(String projectId, List<String> graphs) {
        partitionGraphCache.put(projectId, new PartitionGraphs(graphs, System.currentTimeMillis()));
        log.info("[CACHE] Registered {} partition graphs for project {}", graphs.size(), projectId);
    }

    private void invalidateDerivedCachesAfterUpdate(String projectId) {
        tripleCountCache.remove(projectId);
        String userId = SparqlQueryContext.getUserId();

        boolean isDraftMutation = SparqlQueryContext.wantsDraft() && userId != null && !userId.isBlank();
        if (isDraftMutation) {

            if (springCacheEviction != null) {
                springCacheEviction.evictForProjectAndUser(projectId, userId);
            }
        } else {

            if (topLevelCacheService != null) {
                topLevelCacheService.evict(projectId);
            }
            if (hierarchyIndexService != null) {
                hierarchyIndexService.markStale(projectId);
            }

            if (classDetailCacheService != null && !MutationContext.hasStructuredOps()) {
                classDetailCacheService.dropAll(projectId);
            }
            if (springCacheEviction != null) {
                springCacheEviction.evictForProject(projectId);
            }
        }
        markProjectDirty(projectId);
    }

    public void markProjectDirty(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(dataDir).toAbsolutePath().normalize()
                    .resolve("projects").resolve(projectId);
            if (java.nio.file.Files.isDirectory(dir)) {
                java.nio.file.Files.writeString(dir.resolve("ontology.dirty"),
                        String.valueOf(System.currentTimeMillis()));
            }
        } catch (Exception e) {
            log.debug("[CACHE] Could not write dirty marker for {}: {}", projectId, e.getMessage());
        }
    }

    private void clearProjectDirty(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(dataDir).toAbsolutePath().normalize()
                    .resolve("projects").resolve(projectId).resolve("ontology.dirty"));
        } catch (Exception e) {
            log.debug("[CACHE] Could not clear dirty marker for {}: {}", projectId, e.getMessage());
        }
    }

    private void invalidateContextCaches(String projectId) {
        partitionGraphCache.remove(projectId);
        tripleCountCache.remove(projectId);

        clearProjectDirty(projectId);

        if (projectRepoCache != null) {
            projectRepoCache.evict(projectId);
        }

        if (topLevelCacheService != null) {
            topLevelCacheService.evict(projectId);
        }

        if (hierarchyIndexService != null) {
            hierarchyIndexService.markStale(projectId);
        }
        if (springCacheEviction != null) {
            springCacheEviction.evictForProject(projectId);
            log.info("[CACHE] Evicted Spring caches for project {} after import", projectId);
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

    public List<Map<String, Object>> getRootClassesFromGraphDB(String projectId) {
        List<Map<String, Object>> rootClasses = new ArrayList<>();

        ProjectGraphBinding graphBinding = resolveBinding(projectId, false);
        String graphUri = graphBinding.graphUri();

        try (RepositoryConnection conn = graphBinding.repository().getConnection()) {
            String baseGraph = graphUri;

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

    public List<Map<String, Object>> getChildClassesFromGraphDB(String projectId, String parentClassIri) {
        List<Map<String, Object>> childClasses = new ArrayList<>();

        ProjectGraphBinding graphBinding = resolveBinding(projectId, false);
        String graphUri = graphBinding.graphUri();

        try (RepositoryConnection conn = graphBinding.repository().getConnection()) {
            String baseGraph = graphUri;

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

    private String sanitizeIriString(String iri) {
        if (iri == null || iri.isEmpty()) return null;

        if (iri.isBlank()) {
            log.warn("[IRI-SANITIZE] Skipping blank IRI");
            return "urn:invalid:blank-iri";
        }

        final int len = iri.length();
        for (int i = 0; i < len; i++) {
            char c = iri.charAt(i);

            if (c < 0x20 || c == 0x7F
                    || c > 127 || c == ' ' || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == '|' || c == '\\' || c == '^' || c == '`'
                    || c == '(' || c == ')') {

                return sanitizeIriStringFrom(iri, i);
            }
        }
        return null;
    }

    private String sanitizeIriStringFrom(String iri, int start) {
        final int len = iri.length();
        StringBuilder sb = new StringBuilder(len + 40);
        sb.append(iri, 0, start);
        for (int i = start; i < len; i++) {
            char c = iri.charAt(i);

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

    private boolean disableInferenceDuringImport(RepositoryConnection conn, ValueFactory vf) {
        try {
            if (conn.isActive()) {
                conn.commit();
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

    private String extractShortForm(String iri) {
        if (iri == null || iri.isEmpty()) {
            return "unknown";
        }

        int hashIndex = iri.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex < iri.length() - 1) {
            return iri.substring(hashIndex + 1);
        }

        int slashIndex = iri.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < iri.length() - 1) {
            return iri.substring(slashIndex + 1);
        }

        return iri;
    }
}
