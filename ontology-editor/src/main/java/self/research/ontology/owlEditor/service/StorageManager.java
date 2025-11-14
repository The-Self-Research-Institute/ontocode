package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.storage.*;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Storage Manager Service
 * Routes ontology storage operations to appropriate backend based on size and configuration.
 */
@Service
public class StorageManager {

    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);

    @Value("${ontocode.storage.strategy:HYBRID}")
    private String storageStrategy;

    @Value("${ontocode.storage.triplestore-threshold:100000}")
    private long triplestoreThreshold;

    @Value("${ontocode.storage.jena.enabled:true}")
    private boolean jenaEnabled;

    @Value("${ontocode.storage.jena.fuseki-url:http://localhost:3030}")
    private String jenaFusekiUrl;

    @Value("${ontocode.storage.jena.dataset:ontocode}")
    private String jenaDataset;

    @Value("${ontocode.storage.mongodb.enabled:true}")
    private boolean mongodbEnabled;

    private Map<OntologyStorage.StorageType, OntologyStorage> storageBackends = new HashMap<>();
    private Strategy activeStrategy;

    public enum Strategy {
        MONGODB_ONLY,    // Use only MongoDB (small ontologies)
        JENA_ONLY,       // Use only Jena (all ontologies)
        GRAPHDB_ONLY,    // Use only GraphDB (all ontologies)
        HYBRID           // Automatic selection based on size
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Storage Manager with strategy: {}", storageStrategy);

        // Parse strategy
        try {
            activeStrategy = Strategy.valueOf(storageStrategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid storage strategy '{}', defaulting to HYBRID", storageStrategy);
            activeStrategy = Strategy.HYBRID;
        }

        // Initialize storage backends
        initializeStorageBackends();

        log.info("Storage Manager initialized - Available backends: {}", storageBackends.keySet());
    }

    private void initializeStorageBackends() {
        // Initialize MongoDB storage (if enabled)
        if (mongodbEnabled) {
            try {
                // Create MongoDB storage wrapper
                OntologyStorage mongoStorage = new MongoDBStorage();
                storageBackends.put(OntologyStorage.StorageType.MONGODB, mongoStorage);
                log.info("MongoDB storage initialized");
            } catch (Exception e) {
                log.error("Failed to initialize MongoDB storage", e);
            }
        }

        // Initialize Jena storage (if enabled)
        if (jenaEnabled) {
            try {
                OntologyStorage jenaStorage = new JenaFusekiStorage(jenaFusekiUrl, jenaDataset);
                storageBackends.put(OntologyStorage.StorageType.JENA_FUSEKI, jenaStorage);
                log.info("Jena Fuseki storage initialized at {}", jenaFusekiUrl);
            } catch (Exception e) {
                log.error("Failed to initialize Jena storage", e);
            }
        }

        // Validate at least one storage is available
        if (storageBackends.isEmpty()) {
            throw new RuntimeException("No storage backends available! Enable at least one storage type.");
        }
    }

    /**
     * Select appropriate storage for ontology
     */
    public OntologyStorage selectStorage(long tripleCount) {
        return switch (activeStrategy) {
            case MONGODB_ONLY -> getMongoDBStorage();
            case JENA_ONLY -> getJenaStorage();
            case HYBRID -> selectHybridStorage(tripleCount);
            default -> getDefaultStorage();
        };
    }

    /**
     * Select storage by ontology ID (load existing)
     */
    public OntologyStorage selectStorageForOntology(String ontologyId) throws OntologyStorage.StorageException {
        // Check each backend to find which one has the ontology
        for (OntologyStorage storage : storageBackends.values()) {
            if (storage.exists(ontologyId)) {
                log.debug("Found ontology {} in {}", ontologyId, storage.getStorageType());
                return storage;
            }
        }
        
        throw new OntologyStorage.StorageException("Ontology not found in any storage backend: " + ontologyId);
    }

    /**
     * Store ontology (auto-select storage)
     */
    public String storeOntology(OWLOntology ontology, String ontologyId, Map<String, Object> metadata) 
            throws OntologyStorage.StorageException {
        
        long tripleCount = ontology.getAxiomCount(); // Approximation
        OntologyStorage storage = selectStorage(tripleCount);
        
        log.info("Storing ontology {} ({} axioms) using {}", 
            ontologyId, tripleCount, storage.getStorageType());
        
        return storage.store(ontology, ontologyId, metadata);
    }

    /**
     * Load ontology
     */
    public OWLOntology loadOntology(String ontologyId) throws OntologyStorage.StorageException {
        OntologyStorage storage = selectStorageForOntology(ontologyId);
        return storage.load(ontologyId);
    }

    /**
     * Delete ontology
     */
    public void deleteOntology(String ontologyId) throws OntologyStorage.StorageException {
        OntologyStorage storage = selectStorageForOntology(ontologyId);
        storage.delete(ontologyId);
    }

    /**
     * Execute SPARQL query
     */
    public String executeSparql(String ontologyId, String query) throws OntologyStorage.StorageException {
        OntologyStorage storage = selectStorageForOntology(ontologyId);
        return storage.executeSparql(ontologyId, query);
    }

    /**
     * Get ontology metadata
     */
    public Map<String, Object> getMetadata(String ontologyId) throws OntologyStorage.StorageException {
        OntologyStorage storage = selectStorageForOntology(ontologyId);
        return storage.getMetadata(ontologyId);
    }

    /**
     * List all ontologies (from all backends)
     */
    public Map<String, OntologyInfo> listAllOntologies() {
        Map<String, OntologyInfo> allOntologies = new HashMap<>();
        
        for (Map.Entry<OntologyStorage.StorageType, OntologyStorage> entry : storageBackends.entrySet()) {
            try {
                List<String> ontologyIds = entry.getValue().listOntologies();
                for (String id : ontologyIds) {
                    OntologyInfo info = new OntologyInfo();
                    info.setOntologyId(id);
                    info.setStorageType(entry.getKey());
                    info.setSize(entry.getValue().getSize(id));
                    allOntologies.put(id, info);
                }
            } catch (Exception e) {
                log.error("Error listing ontologies from " + entry.getKey(), e);
            }
        }
        
        return allOntologies;
    }

    /**
     * Get storage statistics
     */
    public Map<String, Object> getStorageStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("strategy", activeStrategy);
        stats.put("triplestoreThreshold", triplestoreThreshold);
        
        Map<String, OntologyStorage.StorageStatistics> backendStats = new HashMap<>();
        for (Map.Entry<OntologyStorage.StorageType, OntologyStorage> entry : storageBackends.entrySet()) {
            try {
                backendStats.put(entry.getKey().name(), entry.getValue().getStatistics());
            } catch (Exception e) {
                log.error("Error getting stats from " + entry.getKey(), e);
            }
        }
        stats.put("backends", backendStats);
        
        return stats;
    }

    /**
     * Migrate ontology to different storage
     */
    public void migrateOntology(String ontologyId, OntologyStorage.StorageType targetType) 
            throws OntologyStorage.StorageException {
        
        log.info("Migrating ontology {} to {}", ontologyId, targetType);
        
        // Load from current storage
        OntologyStorage sourceStorage = selectStorageForOntology(ontologyId);
        OWLOntology ontology = sourceStorage.load(ontologyId);
        Map<String, Object> metadata = sourceStorage.getMetadata(ontologyId);
        
        // Store in target storage
        OntologyStorage targetStorage = storageBackends.get(targetType);
        if (targetStorage == null) {
            throw new OntologyStorage.StorageException("Target storage type not available: " + targetType);
        }
        
        targetStorage.store(ontology, ontologyId, metadata);
        
        // Delete from source (optional)
        // sourceStorage.delete(ontologyId);
        
        log.info("Successfully migrated ontology {} to {}", ontologyId, targetType);
    }

    // ==================== Private Helper Methods ====================

    private OntologyStorage selectHybridStorage(long tripleCount) {
        if (tripleCount < triplestoreThreshold) {
            // Small ontology - use MongoDB if available
            if (mongodbEnabled) {
                return getMongoDBStorage();
            }
        }
        
        // Large ontology - use Jena if available
        if (jenaEnabled) {
            return getJenaStorage();
        }
        
        // Fallback
        return getDefaultStorage();
    }

    private OntologyStorage getMongoDBStorage() {
        OntologyStorage storage = storageBackends.get(OntologyStorage.StorageType.MONGODB);
        if (storage == null) {
            throw new RuntimeException("MongoDB storage not available");
        }
        return storage;
    }

    private OntologyStorage getJenaStorage() {
        OntologyStorage storage = storageBackends.get(OntologyStorage.StorageType.JENA_FUSEKI);
        if (storage == null) {
            throw new RuntimeException("Jena storage not available");
        }
        return storage;
    }

    private OntologyStorage getDefaultStorage() {
        // Return first available storage
        return storageBackends.values().iterator().next();
    }

    /**
     * Ontology info class
     */
    public static class OntologyInfo {
        private String ontologyId;
        private OntologyStorage.StorageType storageType;
        private long size;
        
        // Getters and setters
        public String getOntologyId() { return ontologyId; }
        public void setOntologyId(String id) { this.ontologyId = id; }
        
        public OntologyStorage.StorageType getStorageType() { return storageType; }
        public void setStorageType(OntologyStorage.StorageType type) { this.storageType = type; }
        
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
    }
}