package self.research.ontology.owlEditor.storage;

import org.semanticweb.owlapi.model.OWLOntology;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public interface OntologyStorage {

    enum StorageType {
        MONGODB,
        JENA_TDB,
        JENA_FUSEKI,
        GRAPHDB,
        VIRTUOSO
    }

    StorageType getStorageType();

    boolean canHandle(long tripleCount);

    String store(OWLOntology ontology, String ontologyId, Map<String, Object> metadata)
        throws StorageException;

    OWLOntology load(String ontologyId) throws StorageException;

    boolean exists(String ontologyId);

    void delete(String ontologyId) throws StorageException;

    Map<String, Object> getMetadata(String ontologyId) throws StorageException;

    long getSize(String ontologyId) throws StorageException;

    List<String> listOntologies();

    default String executeSparql(String ontologyId, String query) throws StorageException {
        throw new UnsupportedOperationException("SPARQL not supported by " + getStorageType());
    }

    void export(String ontologyId, OutputStream outputStream, String format)
        throws StorageException;

    String importOntology(InputStream inputStream, String ontologyId, Map<String, Object> metadata)
        throws StorageException;

    String createVersion(String ontologyId, String versionLabel) throws StorageException;

    List<String> listVersions(String ontologyId) throws StorageException;

    OWLOntology loadVersion(String ontologyId, String versionId) throws StorageException;

    StorageStatistics getStatistics() throws StorageException;

    class StorageStatistics {
        private long totalOntologies;
        private long totalTriples;
        private long storageSize;
        private Map<String, Object> additionalStats;

        public long getTotalOntologies() { return totalOntologies; }
        public void setTotalOntologies(long count) { this.totalOntologies = count; }

        public long getTotalTriples() { return totalTriples; }
        public void setTotalTriples(long count) { this.totalTriples = count; }

        public long getStorageSize() { return storageSize; }
        public void setStorageSize(long size) { this.storageSize = size; }

        public Map<String, Object> getAdditionalStats() { return additionalStats; }
        public void setAdditionalStats(Map<String, Object> stats) { this.additionalStats = stats; }
    }

    class StorageException extends Exception {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}