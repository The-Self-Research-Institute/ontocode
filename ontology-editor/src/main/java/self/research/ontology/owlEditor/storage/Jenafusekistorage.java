package self.research.ontology.owlEditor.storage;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.rio.RioOWLRDFParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * Apache Jena Fuseki storage implementation.
 * Handles large ontologies (millions of triples) with SPARQL support.
 */
public class JenaFusekiStorage implements OntologyStorage {

    private static final Logger log = LoggerFactory.getLogger(JenaFusekiStorage.class);

    private final String fusekiUrl;
    private final String dataset;
    private final long maxTripleCount;

    public JenaFusekiStorage(String fusekiUrl, String dataset) {
        this.fusekiUrl = fusekiUrl;
        this.dataset = dataset;
        this.maxTripleCount = 50_000_000; // 50M triples max
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.JENA_FUSEKI;
    }

    @Override
    public boolean canHandle(long tripleCount) {
        // Can handle from 100K to 50M triples
        return tripleCount >= 100_000 && tripleCount <= maxTripleCount;
    }

    @Override
    public String store(OWLOntology ontology, String ontologyId, Map<String, Object> metadata) 
            throws StorageException {
        try {
            log.info("Storing ontology {} in Jena Fuseki ({} axioms)", 
                ontologyId, ontology.getAxiomCount());

            // Convert OWL to RDF model
            Model model = convertOWLToJenaModel(ontology);

            // Create named graph URI
            String graphUri = getGraphUri(ontologyId);

            // Store in Fuseki
            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                // Clear existing data for this graph
                conn.update("CLEAR GRAPH <" + graphUri + ">");

                // Load new data
                conn.load(graphUri, model);

                // Store metadata
                storeMetadata(conn, ontologyId, metadata, ontology.getAxiomCount());
            }

            log.info("Successfully stored ontology {} with {} triples", 
                ontologyId, model.size());

            return ontologyId;

        } catch (Exception e) {
            throw new StorageException("Failed to store ontology in Jena Fuseki: " + ontologyId, e);
        }
    }

    @Override
    public OWLOntology load(String ontologyId) throws StorageException {
        try {
            log.info("Loading ontology {} from Jena Fuseki", ontologyId);

            String graphUri = getGraphUri(ontologyId);

            // Fetch RDF model from Fuseki
            Model model;
            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                model = conn.fetch(graphUri);
            }

            if (model == null || model.isEmpty()) {
                throw new StorageException("Ontology not found: " + ontologyId);
            }

            // Convert RDF model to OWL ontology
            OWLOntology ontology = convertJenaModelToOWL(model);

            log.info("Successfully loaded ontology {} with {} axioms", 
                ontologyId, ontology.getAxiomCount());

            return ontology;

        } catch (Exception e) {
            throw new StorageException("Failed to load ontology from Jena Fuseki: " + ontologyId, e);
        }
    }

    @Override
    public boolean exists(String ontologyId) {
        try {
            String graphUri = getGraphUri(ontologyId);
            
            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                String query = "ASK { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
                return conn.queryAsk(query);
            }
        } catch (Exception e) {
            log.error("Error checking ontology existence: " + ontologyId, e);
            return false;
        }
    }

    @Override
    public void delete(String ontologyId) throws StorageException {
        try {
            log.info("Deleting ontology {} from Jena Fuseki", ontologyId);

            String graphUri = getGraphUri(ontologyId);

            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                // Delete the named graph
                conn.update("CLEAR GRAPH <" + graphUri + ">");
                
                // Delete metadata
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
            
            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                String query = String.format("""
                    PREFIX meta: <http://ontocode.org/metadata/>
                    SELECT ?key ?value
                    WHERE {
                        meta:%s ?key ?value .
                    }
                    """, ontologyId);

                try (QueryExecution qexec = conn.query(query)) {
                    ResultSet results = qexec.execSelect();
                    while (results.hasNext()) {
                        QuerySolution soln = results.nextSolution();
                        String key = soln.get("key").toString();
                        String value = soln.get("value").toString();
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

            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                String query = "SELECT (COUNT(*) as ?count) WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
                
                try (QueryExecution qexec = conn.query(query)) {
                    ResultSet results = qexec.execSelect();
                    if (results.hasNext()) {
                        QuerySolution soln = results.nextSolution();
                        return soln.getLiteral("count").getLong();
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

        try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
            String query = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } FILTER(STRSTARTS(STR(?g), 'http://ontocode.org/ontology/')) }";
            
            try (QueryExecution qexec = conn.query(query)) {
                ResultSet results = qexec.execSelect();
                while (results.hasNext()) {
                    QuerySolution soln = results.nextSolution();
                    String graphUri = soln.getResource("g").getURI();
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
            
            // Inject FROM clause if not present
            if (!query.toUpperCase().contains("FROM")) {
                query = query.replaceFirst("(?i)WHERE", "FROM <" + graphUri + "> WHERE");
            }

            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                try (QueryExecution qexec = conn.query(query)) {
                    if (query.toUpperCase().contains("SELECT")) {
                        ResultSet results = qexec.execSelect();
                        return ResultSetFormatter.asText(results);
                    } else if (query.toUpperCase().contains("CONSTRUCT") || query.toUpperCase().contains("DESCRIBE")) {
                        Model model = qexec.execConstruct();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        RDFDataMgr.write(baos, model, RDFFormat.TURTLE);
                        return baos.toString();
                    } else if (query.toUpperCase().contains("ASK")) {
                        boolean result = qexec.execAsk();
                        return Boolean.toString(result);
                    }
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
            
            // Determine format
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
            // Load ontology from stream
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);

            // Store in Jena
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

            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                // Copy graph to version
                String update = "ADD <" + sourceGraphUri + "> TO <" + versionGraphUri + ">";
                conn.update(update);
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

        try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
            String pattern = "http://ontocode.org/ontology/" + ontologyId + "_v";
            String query = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } FILTER(STRSTARTS(STR(?g), '" + pattern + "')) }";
            
            try (QueryExecution qexec = conn.query(query)) {
                ResultSet results = qexec.execSelect();
                while (results.hasNext()) {
                    QuerySolution soln = results.nextSolution();
                    String graphUri = soln.getResource("g").getURI();
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

            try (RDFConnection conn = RDFConnectionFactory.connect(fusekiUrl + "/" + dataset)) {
                // Count total graphs (ontologies)
                String graphQuery = "SELECT (COUNT(DISTINCT ?g) as ?count) WHERE { GRAPH ?g { ?s ?p ?o } }";
                try (QueryExecution qexec = conn.query(graphQuery)) {
                    ResultSet results = qexec.execSelect();
                    if (results.hasNext()) {
                        stats.setTotalOntologies(results.nextSolution().getLiteral("count").getLong());
                    }
                }

                // Count total triples
                String tripleQuery = "SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }";
                try (QueryExecution qexec = conn.query(tripleQuery)) {
                    ResultSet results = qexec.execSelect();
                    if (results.hasNext()) {
                        stats.setTotalTriples(results.nextSolution().getLiteral("count").getLong());
                    }
                }
            }

            return stats;

        } catch (Exception e) {
            throw new StorageException("Failed to get statistics", e);
        }
    }

    // ==================== Helper Methods ====================

    private String getGraphUri(String ontologyId) {
        return "http://ontocode.org/ontology/" + ontologyId;
    }

    private String extractOntologyIdFromUri(String graphUri) {
        return graphUri.replace("http://ontocode.org/ontology/", "");
    }

    private Model convertOWLToJenaModel(OWLOntology ontology) throws OWLOntologyStorageException, IOException {
        // Convert OWL to RDF/XML
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.saveOntology(ontology, baos);

        // Parse RDF/XML into Jena model
        Model model = ModelFactory.createDefaultModel();
        model.read(new ByteArrayInputStream(baos.toByteArray()), null);
        
        return model;
    }

    private OWLOntology convertJenaModelToOWL(Model model) throws OWLOntologyCreationException, IOException {
        // Convert Jena model to RDF/XML
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RDFDataMgr.write(baos, model, RDFFormat.RDFXML);

        // Parse RDF/XML into OWL
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(new ByteArrayInputStream(baos.toByteArray()));
    }

    private void storeMetadata(RDFConnection conn, String ontologyId, Map<String, Object> metadata, int axiomCount) {
        // Store metadata as RDF triples
        String metadataUri = "http://ontocode.org/metadata/" + ontologyId;
        
        StringBuilder update = new StringBuilder("INSERT DATA { ");
        update.append("<").append(metadataUri).append("> ");
        
        // Add provided metadata
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            update.append("<http://ontocode.org/meta/").append(entry.getKey()).append("> ");
            update.append("\"").append(entry.getValue().toString()).append("\" ; ");
        }
        
        // Add axiom count
        update.append("<http://ontocode.org/meta/axiomCount> ").append(axiomCount).append(" . ");
        update.append("}");
        
        conn.update(update.toString());
    }

    private void deleteMetadata(RDFConnection conn, String ontologyId) {
        String metadataUri = "http://ontocode.org/metadata/" + ontologyId;
        String update = "DELETE WHERE { <" + metadataUri + "> ?p ?o }";
        conn.update(update);
    }

    private OWLDocumentFormat getOWLFormat(String format) {
        // Return appropriate format based on string
        // This is simplified - add more formats as needed
        return switch (format.toLowerCase()) {
            case "rdf", "rdfxml" -> new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
            case "turtle", "ttl" -> new org.semanticweb.owlapi.formats.TurtleDocumentFormat();
            case "owlxml" -> new org.semanticweb.owlapi.formats.OWLXMLDocumentFormat();
            default -> new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
        };
    }
}