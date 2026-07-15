package self.research.ontology.owlEditor.storage;

import org.semanticweb.owlapi.model.OWLOntology;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Storage abstraction interface for ontology persistence.
 * Supports multiple backends: MongoDB, Apache Jena, GraphDB, etc.
 */
public interface OntologyStorage {

    /**
     * Storage type enum
     */
    enum StorageType {
        MONGODB,    // In-memory OWL API + MongoDB GridFS
        JENA_TDB,   // Apache Jena TDB2 (persistent)
        JENA_FUSEKI, // Apache Jena Fuseki (server)
        GRAPHDB,    // Ontotext GraphDB
        VIRTUOSO    // OpenLink Virtuoso
    }

    /**
     * Get storage type
     */
    StorageType getStorageType();

    /**
     * Check if this storage can handle the given ontology size
     */
    boolean canHandle(long tripleCount);

    /**
     * Store an ontology
     */
    String store(OWLOntology ontology, String ontologyId, Map<String, Object> metadata) 
        throws StorageException;

    /**
     * Load an ontology
     */
    OWLOntology load(String ontologyId) throws StorageException;

    /**
     * Check if ontology exists
     */
    boolean exists(String ontologyId);

    /**
     * Delete an ontology
     */
    void delete(String ontologyId) throws StorageException;

    /**
     * Get ontology metadata
     */
    Map<String, Object> getMetadata(String ontologyId) throws StorageException;

    /**
     * Get ontology size (triple count)
     */
    long getSize(String ontologyId) throws StorageException;

    /**
     * List all ontology IDs
     */
    List<String> listOntologies();

    /**
     * Execute SPARQL query (if supported)
     */
    default String executeSparql(String ontologyId, String query) throws StorageException {
        throw new UnsupportedOperationException("SPARQL not supported by " + getStorageType());
    }

    /**
     * Export ontology to stream
     */
    void export(String ontologyId, OutputStream outputStream, String format) 
        throws StorageException;

    /**
     * Import ontology from stream
     */
    String importOntology(InputStream inputStream, String ontologyId, Map<String, Object> metadata) 
        throws StorageException;

    /**
     * Create a version snapshot
     */
    String createVersion(String ontologyId, String versionLabel) throws StorageException;

    /**
     * List versions
     */
    List<String> listVersions(String ontologyId) throws StorageException;

    /**
     * Load a specific version
     */
    OWLOntology loadVersion(String ontologyId, String versionId) throws StorageException;

    /**
     * Get storage statistics
     */
    StorageStatistics getStatistics() throws StorageException;

    /**
     * Storage statistics
     */
    class StorageStatistics {
        private long totalOntologies;
        private long totalTriples;
        private long storageSize;  // bytes
        private Map<String, Object> additionalStats;

        // Getters and setters
        public long getTotalOntologies() { return totalOntologies; }
        public void setTotalOntologies(long count) { this.totalOntologies = count; }

        public long getTotalTriples() { return totalTriples; }
        public void setTotalTriples(long count) { this.totalTriples = count; }

        public long getStorageSize() { return storageSize; }
        public void setStorageSize(long size) { this.storageSize = size; }

        public Map<String, Object> getAdditionalStats() { return additionalStats; }
        public void setAdditionalStats(Map<String, Object> stats) { this.additionalStats = stats; }
    }

    /**
     * Storage exception
     */
    class StorageException extends Exception {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}