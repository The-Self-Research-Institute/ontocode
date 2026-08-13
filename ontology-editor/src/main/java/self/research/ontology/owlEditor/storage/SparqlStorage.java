package self.research.ontology.owlEditor.storage;

import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.query.resultio.text.tsv.SPARQLResultsTSVWriter;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class SparqlStorage implements OntologyStorage {

    private static final Logger log = LoggerFactory.getLogger(SparqlStorage.class);

    private final String queryEndpoint;
    private final String updateEndpoint;
    private final Repository repository;
    private final long maxTripleCount;

    public SparqlStorage(String queryEndpoint, String updateEndpoint) {
        this.queryEndpoint = queryEndpoint;
        this.updateEndpoint = updateEndpoint;
        this.maxTripleCount = 100_000_000;

        SPARQLRepository sparqlRepo = new SPARQLRepository(queryEndpoint, updateEndpoint);
        sparqlRepo.init();
        this.repository = sparqlRepo;
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.GRAPHDB;
    }

    @Override
    public boolean canHandle(long tripleCount) {

        return tripleCount >= 100_000 && tripleCount <= maxTripleCount;
    }

    @Override
    public String store(OWLOntology ontology, String ontologyId, Map<String, Object> metadata)
            throws StorageException {
        try {
            log.info("Storing ontology {} in GraphDB ({} axioms)",
                ontologyId, ontology.getAxiomCount());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            manager.saveOntology(ontology, baos);

            String graphUri = getGraphUri(ontologyId);

            try (RepositoryConnection conn = repository.getConnection()) {

                conn.clear(conn.getValueFactory().createIRI(graphUri));

                try (InputStream inputStream = new ByteArrayInputStream(baos.toByteArray())) {
                    conn.add(inputStream, graphUri, RDFFormat.RDFXML,
                            conn.getValueFactory().createIRI(graphUri));
                }

                storeMetadata(conn, ontologyId, metadata, ontology.getAxiomCount());
            }

            log.info("Successfully stored ontology {} in GraphDB", ontologyId);

            return ontologyId;

        } catch (Exception e) {
            throw new StorageException("Failed to store ontology in GraphDB: " + ontologyId, e);
        }
    }

    @Override
    public OWLOntology load(String ontologyId) throws StorageException {
        try {
            log.info("Loading ontology {} from GraphDB", ontologyId);

            String graphUri = getGraphUri(ontologyId);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            try (RepositoryConnection conn = repository.getConnection()) {
                RDFWriter writer = Rio.createWriter(RDFFormat.RDFXML, baos);
                conn.export(writer, conn.getValueFactory().createIRI(graphUri));
            }

            if (baos.size() == 0) {
                throw new StorageException("Ontology not found: " + ontologyId);
            }

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                new ByteArrayInputStream(baos.toByteArray()));

            log.info("Successfully loaded ontology {} with {} axioms",
                ontologyId, ontology.getAxiomCount());

            return ontology;

        } catch (Exception e) {
            throw new StorageException("Failed to load ontology from GraphDB: " + ontologyId, e);
        }
    }

    @Override
    public boolean exists(String ontologyId) {
        try {
            String graphUri = getGraphUri(ontologyId);

            try (RepositoryConnection conn = repository.getConnection()) {
                String query = "ASK { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
                BooleanQuery booleanQuery = conn.prepareBooleanQuery(query);
                return booleanQuery.evaluate();
            }
        } catch (Exception e) {
            log.error("Error checking ontology existence: " + ontologyId, e);
            return false;
        }
    }

    @Override
    public void delete(String ontologyId) throws StorageException {
        try {
            log.info("Deleting ontology {} from GraphDB", ontologyId);

            String graphUri = getGraphUri(ontologyId);

            try (RepositoryConnection conn = repository.getConnection()) {

                conn.clear(conn.getValueFactory().createIRI(graphUri));

                deleteMetadata(conn, ontologyId);
            }

            log.info("Successfully deleted ontology {}", ontologyId);

        } catch (Exception e) {
            throw new StorageException("Failed to delete ontology: " + ontologyId, e);
        }
    }

    @Override
    public Map<String, Object> getMetadata(String ontologyId) throws StorageException {
        try {
            Map<String, Object> metadata = new HashMap<>();

            try (RepositoryConnection conn = repository.getConnection()) {
                String query = String.format("""
                    PREFIX meta: <http://ontocode.org/metadata/>
                    SELECT ?key ?value
                    WHERE {
                        meta:%s ?key ?value .
                    }
                    """, ontologyId);

                TupleQuery tupleQuery = conn.prepareTupleQuery(query);
                try (TupleQueryResult result = tupleQuery.evaluate()) {
                    while (result.hasNext()) {
                        BindingSet bindings = result.next();
                        String key = bindings.getValue("key").stringValue();
                        String value = bindings.getValue("value").stringValue();
                        metadata.put(key, value);
                    }
                }
            }

            return metadata;

        } catch (Exception e) {
            throw new StorageException("Failed to get metadata: " + ontologyId, e);
        }
    }

    @Override
    public long getSize(String ontologyId) throws StorageException {
        try {
            String graphUri = getGraphUri(ontologyId);

            try (RepositoryConnection conn = repository.getConnection()) {
                String query = "SELECT (COUNT(*) as ?count) WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }";

                TupleQuery tupleQuery = conn.prepareTupleQuery(query);
                try (TupleQueryResult result = tupleQuery.evaluate()) {
                    if (result.hasNext()) {
                        BindingSet bindings = result.next();
                        return Long.parseLong(bindings.getValue("count").stringValue());
                    }
                }
            }

            return 0;

        } catch (Exception e) {
            throw new StorageException("Failed to get size: " + ontologyId, e);
        }
    }

    @Override
    public List<String> listOntologies() {
        List<String> ontologyIds = new ArrayList<>();

        try (RepositoryConnection conn = repository.getConnection()) {
            String query = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } FILTER(STRSTARTS(STR(?g), 'http://ontocode.org/')) }";

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);
            try (TupleQueryResult result = tupleQuery.evaluate()) {
                while (result.hasNext()) {
                    BindingSet bindings = result.next();
                    String graphUri = bindings.getValue("g").stringValue();
                    String ontologyId = extractOntologyIdFromUri(graphUri);
                    ontologyIds.add(ontologyId);
                }
            }
        } catch (Exception e) {
            log.error("Error listing ontologies", e);
        }

        return ontologyIds;
    }

    @Override
    public String executeSparql(String ontologyId, String query) throws StorageException {
        try {
            String graphUri = getGraphUri(ontologyId);

            if (!query.toUpperCase().contains("FROM")) {
                query = query.replaceFirst("(?i)WHERE", "FROM <" + graphUri + "> WHERE");
            }

            try (RepositoryConnection conn = repository.getConnection()) {
                if (query.toUpperCase().contains("SELECT")) {
                    TupleQuery tupleQuery = conn.prepareTupleQuery(query);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    SPARQLResultsTSVWriter writer = new SPARQLResultsTSVWriter(baos);

                    try (TupleQueryResult result = tupleQuery.evaluate()) {
                        writer.startQueryResult(result.getBindingNames());
                        while (result.hasNext()) {
                            writer.handleSolution(result.next());
                        }
                        writer.endQueryResult();
                    }

                    return baos.toString();

                } else if (query.toUpperCase().contains("CONSTRUCT") || query.toUpperCase().contains("DESCRIBE")) {
                    GraphQuery graphQuery = conn.prepareGraphQuery(query);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    RDFWriter writer = Rio.createWriter(RDFFormat.TURTLE, baos);

                    try (GraphQueryResult result = graphQuery.evaluate()) {
                        writer.startRDF();
                        while (result.hasNext()) {
                            writer.handleStatement(result.next());
                        }
                        writer.endRDF();
                    }

                    return baos.toString();

                } else if (query.toUpperCase().contains("ASK")) {
                    BooleanQuery booleanQuery = conn.prepareBooleanQuery(query);
                    boolean result = booleanQuery.evaluate();
                    return Boolean.toString(result);
                }
            }

            return "";

        } catch (Exception e) {
            throw new StorageException("Failed to execute SPARQL query", e);
        }
    }

    @Override
    public void export(String ontologyId, OutputStream outputStream, String format)
            throws StorageException {
        try {
            OWLOntology ontology = load(ontologyId);
            OWLOntologyManager manager = ontology.getOWLOntologyManager();

            OWLDocumentFormat documentFormat = getOWLFormat(format);
            manager.saveOntology(ontology, documentFormat, outputStream);

        } catch (Exception e) {
            throw new StorageException("Failed to export ontology: " + ontologyId, e);
        }
    }

    @Override
    public String importOntology(InputStream inputStream, String ontologyId, Map<String, Object> metadata)
            throws StorageException {
        try {

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);

            return store(ontology, ontologyId, metadata);

        } catch (Exception e) {
            throw new StorageException("Failed to import ontology", e);
        }
    }

    @Override
    public String createVersion(String ontologyId, String versionLabel) throws StorageException {
        try {
            String versionId = ontologyId + "_v" + versionLabel;
            String sourceGraphUri = getGraphUri(ontologyId);
            String versionGraphUri = getGraphUri(versionId);

            try (RepositoryConnection conn = repository.getConnection()) {

                String update = "ADD <" + sourceGraphUri + "> TO <" + versionGraphUri + ">";
                Update updateQuery = conn.prepareUpdate(update);
                updateQuery.execute();
            }

            log.info("Created version {} for ontology {}", versionLabel, ontologyId);
            return versionId;

        } catch (Exception e) {
            throw new StorageException("Failed to create version: " + ontologyId, e);
        }
    }

    @Override
    public List<String> listVersions(String ontologyId) throws StorageException {
        List<String> versions = new ArrayList<>();

        try (RepositoryConnection conn = repository.getConnection()) {
            String pattern = "http://ontocode.org/" + ontologyId + "_v";
            String query = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } FILTER(STRSTARTS(STR(?g), '" + pattern + "')) }";

            TupleQuery tupleQuery = conn.prepareTupleQuery(query);
            try (TupleQueryResult result = tupleQuery.evaluate()) {
                while (result.hasNext()) {
                    BindingSet bindings = result.next();
                    String graphUri = bindings.getValue("g").stringValue();
                    versions.add(extractOntologyIdFromUri(graphUri));
                }
            }
        } catch (Exception e) {
            throw new StorageException("Failed to list versions", e);
        }

        return versions;
    }

    @Override
    public OWLOntology loadVersion(String ontologyId, String versionId) throws StorageException {
        return load(versionId);
    }

    @Override
    public StorageStatistics getStatistics() throws StorageException {
        try {
            StorageStatistics stats = new StorageStatistics();

            try (RepositoryConnection conn = repository.getConnection()) {

                String graphQuery = "SELECT (COUNT(DISTINCT ?g) as ?count) WHERE { GRAPH ?g { ?s ?p ?o } }";
                TupleQuery tupleQuery = conn.prepareTupleQuery(graphQuery);
                try (TupleQueryResult result = tupleQuery.evaluate()) {
                    if (result.hasNext()) {
                        BindingSet bindings = result.next();
                        stats.setTotalOntologies(Long.parseLong(bindings.getValue("count").stringValue()));
                    }
                }

                String tripleQuery = "SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }";
                tupleQuery = conn.prepareTupleQuery(tripleQuery);
                try (TupleQueryResult result = tupleQuery.evaluate()) {
                    if (result.hasNext()) {
                        BindingSet bindings = result.next();
                        stats.setTotalTriples(Long.parseLong(bindings.getValue("count").stringValue()));
                    }
                }
            }

            return stats;

        } catch (Exception e) {
            throw new StorageException("Failed to get statistics", e);
        }
    }

    private String getGraphUri(String ontologyId) {
        return "http://ontocode.org/" + ontologyId;
    }

    private String extractOntologyIdFromUri(String graphUri) {
        return graphUri.replace("http://ontocode.org/", "");
    }

    private void storeMetadata(RepositoryConnection conn, String ontologyId, Map<String, Object> metadata, int axiomCount) {

        String metadataUri = "http://ontocode.org/metadata/" + ontologyId;

        StringBuilder update = new StringBuilder("INSERT DATA { ");
        update.append("<").append(metadataUri).append("> ");

        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            update.append("<http://ontocode.org/meta/").append(entry.getKey()).append("> ");
            update.append("\"").append(entry.getValue().toString()).append("\" ; ");
        }

        update.append("<http://ontocode.org/meta/axiomCount> ").append(axiomCount).append(" . ");
        update.append("}");

        Update updateQuery = conn.prepareUpdate(update.toString());
        updateQuery.execute();
    }

    private void deleteMetadata(RepositoryConnection conn, String ontologyId) {
        String metadataUri = "http://ontocode.org/metadata/" + ontologyId;
        String update = "DELETE WHERE { <" + metadataUri + "> ?p ?o }";
        Update updateQuery = conn.prepareUpdate(update);
        updateQuery.execute();
    }

    private OWLDocumentFormat getOWLFormat(String format) {
        return switch (format.toLowerCase()) {
            case "rdf", "rdfxml" -> new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
            case "turtle", "ttl" -> new org.semanticweb.owlapi.formats.TurtleDocumentFormat();
            case "owlxml" -> new org.semanticweb.owlapi.formats.OWLXMLDocumentFormat();
            default -> new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
        };
    }

    public void shutdown() {
        if (repository != null && repository.isInitialized()) {
            repository.shutDown();
            log.info("SPARQL repository connection closed");
        }
    }
}
