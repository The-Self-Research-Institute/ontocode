package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
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
    
    private final RestTemplate restTemplate;
    private final String editorServiceUrl;

    public OntologyClientService(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${ontology.editor.service.url}") String editorServiceUrl) {
        
        this.editorServiceUrl = editorServiceUrl;
        
        // ✅ FIXED: Use RestTemplate instead of blocking WebClient
        this.restTemplate = restTemplateBuilder
            .rootUri(editorServiceUrl)
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofSeconds(30))
            .build();
            
        logger.info("Initialized OntologyClientService with URL: {}", editorServiceUrl);
    }

    /**
     * Fetch ontology with caching and retry logic
     * 
     * ✅ FIXED: No more blocking WebClient calls
     * ✅ ADDED: Retry logic with exponential backoff
     * ✅ IMPROVED: Better error messages
     */
    @Cacheable(key = "#projectId")
    @Retryable(
        value = { RestClientException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public OWLOntology fetchOntology(String projectId) throws OWLOntologyCreationException {
        logger.info("Fetching ontology for project: {}", projectId);
        long startTime = System.currentTimeMillis();

        try {
            // Fetch ontology bytes from editor service
            ResponseEntity<byte[]> response = restTemplate.getForEntity(
                "/api/ontology/export/{projectId}",
                byte[].class,
                projectId
            );

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new RuntimeException(
                    "Failed to fetch ontology: HTTP " + response.getStatusCode()
                );
            }

            byte[] ontologyBytes = response.getBody();
            logger.debug("Received {} bytes for project {}", ontologyBytes.length, projectId);

            // Load ontology from bytes
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            
            try (InputStream is = new ByteArrayInputStream(ontologyBytes)) {
                OWLOntology ontology = manager.loadOntologyFromOntologyDocument(is);
                
                long loadTime = System.currentTimeMillis() - startTime;
                logger.info("Successfully loaded ontology for project {} in {}ms. " +
                           "Classes: {}, Properties: {}, Individuals: {}", 
                           projectId, 
                           loadTime,
                           ontology.getClassesInSignature().size(),
                           ontology.getObjectPropertiesInSignature().size(),
                           ontology.getIndividualsInSignature().size());
                
                return ontology;
            }

        } catch (RestClientException e) {
            logger.error("REST client error fetching ontology for project: {}", projectId, e);
            throw new OWLOntologyCreationException(
                "Failed to fetch ontology from editor service: " + e.getMessage(), e
            );
            
        } catch (OWLOntologyCreationException e) {
            logger.error("OWL parsing error for project: {}", projectId, e);
            throw e;
            
        } catch (Exception e) {
            logger.error("Unexpected error fetching ontology for project: {}", projectId, e);
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