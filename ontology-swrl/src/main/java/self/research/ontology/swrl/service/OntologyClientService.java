package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * IMPROVED VERSION - Fixed blocking WebClient, added retry logic, and better error handling
 */
@Service
@CacheConfig(cacheNames = "ontologies")
public class OntologyClientService {

    private static final Logger logger = LoggerFactory.getLogger(OntologyClientService.class);
    private static final int DIAGNOSTIC_CAPTURE_LIMIT = 4096;

    private final RestTemplate restTemplate;
    private final String editorServiceUrl;

    @Value("${ontocode.internal.token:ontocode-internal}")
    private String internalToken;

    @Value("${ontocode.capacity.swrl-execute.max-ontology-axioms:0}")
    private long maxOntologyAxioms = 0;

    @Value("${ontocode.capacity.swrl-execute.ontology-heap-fraction:" + OntologyBudget.DEFAULT_HEAP_FRACTION + "}")
    private double ontologyHeapFraction = OntologyBudget.DEFAULT_HEAP_FRACTION;

    @Value("${ontocode.capacity.swrl-execute.ontology-bytes-per-axiom:" + OntologyBudget.DEFAULT_BYTES_PER_AXIOM + "}")
    private long ontologyBytesPerAxiom = OntologyBudget.DEFAULT_BYTES_PER_AXIOM;

    @Value("${ontocode.capacity.swrl-execute.ontology-text-bytes-per-axiom:" + OntologyBudget.DEFAULT_TEXT_BYTES_PER_AXIOM + "}")
    private long ontologyTextBytesPerAxiom = OntologyBudget.DEFAULT_TEXT_BYTES_PER_AXIOM;

    @Value("${ontocode.capacity.swrl-execute.workers:${swrl.execution.workers:2}}")
    private int executionWorkers = 2;

    public OntologyClientService(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${ontology.editor.service.url}") String editorServiceUrl) {
        
        this.editorServiceUrl = editorServiceUrl;
        
        // ✅ FIXED: Use RestTemplate instead of blocking WebClient
        // Read timeout matches the editor's own /api/ontology/warm timeoutMs=300000 convention
        // for large-ontology operations — a 30s timeout here made SWRL rule creation fail with
        // an opaque "I/O error ... null" on any real-world-sized ontology (e.g. GO-basic's
        // ~13k classes took 105s+ to export), even though the fetch itself was succeeding.
        this.restTemplate = restTemplateBuilder
            .rootUri(editorServiceUrl)
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofSeconds(300))
            .build();
            
        logger.info("Initialized OntologyClientService with URL: {}", editorServiceUrl);
    }

    /**
     * Fetch ontology with retry logic
     *
     * ✅ FIXED: No more blocking WebClient calls
     * ✅ ADDED: Retry logic with exponential backoff
     * ✅ IMPROVED: Better error messages
     *
     * Deliberately NOT cached: this fetches the project's live ontology from a
     * separate service (the editor), which mutates independently of SWRL. A
     * cached copy here would go stale the moment someone edits the project —
     * with no automatic invalidation wired between the two services, that
     * meant SWRL could reason against ontology data that was edited/re-imported
     * minutes or hours ago. The cost of re-fetching on every call is small next
     * to the cost of silently wrong reasoning results.
     */
    @Retryable(
        value = { RestClientException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    /**
     * Forward the caller's Bearer token to the editor service. The editor
     * enforces JWT on /api/** when running in docker (require-jwt=true), so an
     * unauthenticated export call 401s. Outside a request context (or without
     * a bearer token) no Authorization header is set, matching desktop/dev
     * where the editor exempts localhost callers.
     */
    private String currentAuthorizationHeader() {
        org.springframework.web.context.request.RequestAttributes attrs =
            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
            String auth = servletAttrs.getRequest().getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return auth;
            }
        }
        return null;
    }

    public record FetchedOntology(OWLOntology ontology, Long ontologyRevision, String draftVersion) {}

    public record RuleAccess(boolean allowed, String reason) {
    }

    public RuleAccess canWriteRules(String projectId) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        String authHeader = currentAuthorizationHeader();
        if (authHeader != null) {
            headers.set("Authorization", authHeader);
        }
        try {
            ResponseEntity<java.util.Map> response = restTemplate.exchange("/api/ontology/{projectId}/rules-access",
                    org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers),
                    java.util.Map.class, projectId);
            java.util.Map<?, ?> body = response.getBody();
            boolean allowed = body != null && Boolean.TRUE.equals(body.get("canWriteRules"));
            Object reason = body != null ? body.get("reason") : null;
            return new RuleAccess(allowed, reason != null ? String.valueOf(reason) : "You cannot change the rules of this project.");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                return new RuleAccess(false, "You do not have access to change the rules of this project.");
            }
            throw e;
        }
    }

    long maxAxioms() {
        return OntologyBudget.maxAxioms(maxOntologyAxioms, Runtime.getRuntime().maxMemory(), ontologyHeapFraction,
                executionWorkers, ontologyBytesPerAxiom);
    }

    public String currentStamp(String projectId, String draftUserId) {
        if (internalToken == null || internalToken.isBlank()) {
            return null;
        }
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Ontocode-Internal-Token", internalToken);
            boolean draft = draftUserId != null && !draftUserId.isBlank();
            ResponseEntity<Map> response = draft
                    ? restTemplate.exchange("/internal/reasoning/{projectId}/draft-version?userId={userId}",
                            org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers),
                            Map.class, projectId, draftUserId)
                    : restTemplate.exchange("/internal/reasoning/{projectId}/revision",
                            org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers),
                            Map.class, projectId);
            Map<?, ?> body = response.getBody();
            Object value = body == null ? null : (draft ? body.get("version") : body.get("revision"));
            return value == null ? null : (draft ? "d:" : "r:") + value;
        } catch (RestClientException e) {
            logger.debug("Could not read the ontology stamp for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    public OWLOntology fetchOntology(String projectId) throws OWLOntologyCreationException {
        return fetchOntologyWithStamp(projectId, null).ontology();
    }

    public FetchedOntology fetchOntologyWithStamp(String projectId, String draftUserId) throws OWLOntologyCreationException {
        logger.info("Fetching ontology for project: {}{}", projectId, draftUserId != null ? " (draft)" : "");
        long startTime = System.currentTimeMillis();

        try {
            String authHeader = currentAuthorizationHeader();
            // Stream ontology directly into OWL parser to avoid holding entire byte[] in heap
            final Long[] revision = new Long[1];
            final String[] draftVersion = new String[1];
            boolean internalCall = authHeader == null && internalToken != null && !internalToken.isBlank();
            String exportPath = internalCall
                ? (draftUserId != null
                    ? "/internal/reasoning/{projectId}/export.ttl?draft=true&userId={userId}"
                    : "/internal/reasoning/{projectId}/export.ttl")
                : (draftUserId != null
                    ? "/api/ontology/export/{projectId}?draft=true&userId={userId}"
                    : "/api/ontology/export/{projectId}");
            OWLOntology ontology = restTemplate.execute(
                exportPath,
                org.springframework.http.HttpMethod.GET,
                request -> {
                    if (authHeader != null) {
                        request.getHeaders().set("Authorization", authHeader);
                    } else if (internalCall) {
                        request.getHeaders().set("X-Ontocode-Internal-Token", internalToken);
                    }
                },
                response -> {
                    if (response.getStatusCode() != HttpStatus.OK) {
                        throw new RuntimeException(
                            "Failed to fetch ontology: HTTP " + response.getStatusCode()
                        );
                    }
                    String revisionHeader = response.getHeaders().getFirst("X-Ontology-Revision");
                    if (revisionHeader != null) {
                        try {
                            revision[0] = Long.valueOf(revisionHeader.trim());
                        } catch (NumberFormatException ignored) {
                            revision[0] = null;
                        }
                    }
                    draftVersion[0] = response.getHeaders().getFirst("X-Ontology-Draft-Version");
                    long axiomLimit = maxAxioms();
                    long textLimit = OntologyBudget.maxTextBytes(axiomLimit, ontologyTextBytesPerAxiom);
                    if (response.getHeaders().getContentLength() > textLimit) {
                        throw new OntologyTooLargeException(axiomLimit);
                    }
                    OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                    // Mirror only the first DIAGNOSTIC_CAPTURE_LIMIT bytes into a small in-memory
                    // buffer as the parser reads, so a parse failure can log what the editor
                    // actually sent instead of just "6 parsers tried, all failed". Bounded to a
                    // few KB regardless of ontology size, so it doesn't reintroduce the full-heap
                    // buffering this streaming approach was written to avoid.
                    java.io.ByteArrayOutputStream headCapture =
                        new java.io.ByteArrayOutputStream(DIAGNOSTIC_CAPTURE_LIMIT);
                    try (InputStream raw = response.getBody();
                         CappedInputStream is = new CappedInputStream(
                                 new BoundedTeeInputStream(raw, headCapture, DIAGNOSTIC_CAPTURE_LIMIT), textLimit)) {
                        try {
                            OWLOntology loaded = manager.loadOntologyFromOntologyDocument(is);
                            if (loaded.getAxiomCount() > axiomLimit) {
                                throw new OntologyTooLargeException(axiomLimit);
                            }
                            return loaded;
                        } catch (OWLOntologyCreationException | RuntimeException e) {
                            if (is.exceeded()) {
                                throw new OntologyTooLargeException(axiomLimit);
                            }
                            throw e;
                        }
                    } catch (OWLOntologyCreationException e) {
                        logger.error(
                            "OWL parse failure for project {}. First {} bytes of the exported document:\n{}",
                            projectId, headCapture.size(),
                            headCapture.toString(java.nio.charset.StandardCharsets.UTF_8)
                        );
                        throw new RuntimeException("OWL parsing error", e);
                    }
                },
                draftUserId != null ? new Object[]{projectId, draftUserId} : new Object[]{projectId}
            );

            if (ontology == null) {
                throw new RuntimeException("Failed to fetch ontology: null response");
            }

            PlainLiteralNormalizer.apply(ontology);

            long loadTime = System.currentTimeMillis() - startTime;
            logger.info("Successfully loaded ontology for project {} in {}ms. " +
                       "Classes: {}, Properties: {}, Individuals: {}", 
                       projectId, 
                       loadTime,
                       ontology.getClassesInSignature().size(),
                       ontology.getObjectPropertiesInSignature().size(),
                       ontology.getIndividualsInSignature().size());
            
            return new FetchedOntology(ontology, revision[0], draftVersion[0]);

        } catch (OntologyTooLargeException e) {
            logger.warn("Refused to load the ontology for project {}: it is over the limit of {} axioms", projectId, e.getLimitAxioms());
            throw e;
        } catch (RestClientException e) {
            logger.error("REST client error fetching ontology for project: {}", projectId, e);
            throw new OWLOntologyCreationException(
                "Failed to fetch ontology from editor service: " + e.getMessage(), e
            );
            
        } catch (Exception e) {
            logger.error("Unexpected error fetching ontology for project: {}", projectId, e);
            if (e.getCause() instanceof OWLOntologyCreationException) {
                throw (OWLOntologyCreationException) e.getCause();
            }
            throw new OWLOntologyCreationException(
                "Unexpected error loading ontology: " + e.getMessage(), e
            );
        }
    }

    /**
     * ✅ NEW: Evict cached ontology when it's updated
     */
    @CacheEvict(key = "#projectId")
    public void invalidateCache(String projectId) {
        logger.info("Invalidated ontology cache for project: {}", projectId);
    }

    /**
     * ✅ NEW: Evict all cached ontologies
     */
    @CacheEvict(allEntries = true)
    public void invalidateAllCache() {
        logger.info("Invalidated all ontology caches");
    }

    /**
     * ✅ NEW: Check if ontology exists without loading it
     */
    public boolean ontologyExists(String projectId) {
        try {
            ResponseEntity<Void> response = restTemplate.getForEntity(
                "/api/ontology/exists/{projectId}",
                Void.class,
                projectId
            );
            
            return response.getStatusCode() == HttpStatus.OK;
            
        } catch (Exception e) {
            logger.warn("Error checking ontology existence for project {}: {}", 
                       projectId, e.getMessage());
            return false;
        }
    }

    /**
     * ✅ NEW: Get ontology metadata without loading full ontology
     */
    public OntologyMetadata getOntologyMetadata(String projectId) {
        try {
            return restTemplate.getForObject(
                "/api/ontology/metadata/{projectId}",
                OntologyMetadata.class,
                projectId
            );
            
        } catch (Exception e) {
            logger.error("Failed to get metadata for project {}", projectId, e);
            return null;
        }
    }

    /**
     * ✅ NEW: Preload ontologies for multiple projects
     */
    public void preloadOntologies(List<String> projectIds) {
        logger.info("Preloading {} ontologies", projectIds.size());
        
        projectIds.parallelStream().forEach(projectId -> {
            try {
                fetchOntology(projectId);
            } catch (Exception e) {
                logger.warn("Failed to preload ontology for project {}: {}", 
                           projectId, e.getMessage());
            }
        });
        
        logger.info("Completed preloading ontologies");
    }

    /**
     * ✅ NEW: Health check for editor service
     */
    public boolean isEditorServiceHealthy() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                "/actuator/health",
                Map.class
            );
            
            return response.getStatusCode() == HttpStatus.OK;
            
        } catch (Exception e) {
            logger.error("Editor service health check failed", e);
            return false;
        }
    }

    public String getEditorServiceUrl() {
        return editorServiceUrl;
    }
}

/**
 * Mirrors up to {@code limit} bytes read through it into {@code capture}, then passes reads
 * through untouched. Used to grab a diagnostic head-sample of a streamed ontology document
 * without buffering the whole thing in heap.
 */
class BoundedTeeInputStream extends java.io.FilterInputStream {
    private final java.io.ByteArrayOutputStream capture;
    private final int limit;

    BoundedTeeInputStream(InputStream in, java.io.ByteArrayOutputStream capture, int limit) {
        super(in);
        this.capture = capture;
        this.limit = limit;
    }

    @Override
    public int read() throws java.io.IOException {
        int b = super.read();
        if (b != -1 && capture.size() < limit) {
            capture.write(b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws java.io.IOException {
        int n = super.read(b, off, len);
        if (n > 0 && capture.size() < limit) {
            capture.write(b, off, Math.min(n, limit - capture.size()));
        }
        return n;
    }
}

class CappedInputStream extends java.io.FilterInputStream {
    private final long limit;
    private long read;
    private boolean exceeded;

    CappedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    boolean exceeded() {
        return exceeded;
    }

    private void count(long n) throws java.io.IOException {
        read += n;
        if (read > limit) {
            exceeded = true;
            throw new java.io.IOException("The ontology text is over the size limit");
        }
    }

    @Override
    public int read() throws java.io.IOException {
        int b = super.read();
        if (b != -1) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws java.io.IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count(n);
        }
        return n;
    }
}

/**
 * ✅ NEW: Ontology metadata DTO
 */
class OntologyMetadata {
    private String projectId;
    private String name;
    private int classCount;
    private int propertyCount;
    private int individualCount;
    private long sizeInBytes;
    private String lastModified;

    // Constructors, getters, setters
    
    public OntologyMetadata() {}

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getClassCount() { return classCount; }
    public void setClassCount(int classCount) { this.classCount = classCount; }

    public int getPropertyCount() { return propertyCount; }
    public void setPropertyCount(int propertyCount) { this.propertyCount = propertyCount; }

    public int getIndividualCount() { return individualCount; }
    public void setIndividualCount(int individualCount) { this.individualCount = individualCount; }

    public long getSizeInBytes() { return sizeInBytes; }
    public void setSizeInBytes(long sizeInBytes) { this.sizeInBytes = sizeInBytes; }

    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }
}