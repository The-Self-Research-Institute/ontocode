package self.research.ontology.owlEditor.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.types.ObjectId;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import self.research.ontology.owlEditor.config.SparqlProperties;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class OwlParsingService {
    
    private static final Logger log = LoggerFactory.getLogger(OwlParsingService.class);
    
    @Autowired
    private MongoTemplate mongo;

    // @Autowired
    // private Neo4jSyncService neo4jSyncService; // TODO: Implement Neo4j sync service
    
    @Autowired
    private GridFsTemplate gridfs;
    
    @Autowired
    private SparqlProperties props;
    
    @Autowired
    private SparqlDatasetService datasetService;
    
    private final WebClient updateClient;

    public OwlParsingService(WebClient.Builder builder, SparqlProperties props) {
        this.props = props;
        
        WebClient.Builder webClientBuilder = builder.baseUrl(props.getUpdateEndpointUrl());
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            webClientBuilder.defaultHeaders(headers -> 
                headers.setBasicAuth(props.getUsername(), props.getPassword())
            );
        }
        this.updateClient = webClientBuilder.build();
    }

    /**
     * Parse ontology file from GridFS and index to triple store
     */
    @Async("owlParsingExecutor") //
    public CompletableFuture<Void> parseAndIndex(String projectId, ObjectId fileId) {
        log.info("Starting async parsing for project: {}, fileId: {}", projectId, fileId);
        updateStatus(projectId, "PROCESSING", "Parsing ontology...");
        
        try {
            // Retrieve file from GridFS
            GridFSFile gf = gridfs.findOne(new Query(Criteria.where("_id").is(fileId))); //
            if (gf == null) {
                throw new RuntimeException("File not found in GridFS: " + fileId);
            }
            
            GridFsResource resource = gridfs.getResource(gf);
            
            try (InputStream inputStream = resource.getInputStream()) {
                // Parse with OWL API
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream); //
                
                log.info("Successfully parsed ontology for project: {}", projectId);
                log.info("Ontology IRI: {}", ontology.getOntologyID().getOntologyIRI().orElse(null));
                log.info("Axiom count: {}", ontology.getAxiomCount());
                
                // Extract metadata for MongoDB
                Map<String, Object> metadata = extractMetadata(projectId, ontology); //
                saveMetadataToMongo(projectId, metadata); //
                
                // Register prefixes in GraphDB as well
                if (metadata.containsKey("prefixes")) {
                    Map<String, String> prefixes = (Map<String, String>) metadata.get("prefixes");
                    log.info("Registering {} prefixes in GraphDB for project: {}", prefixes.size(), projectId);
                    try {
                        datasetService.setPrefixes(projectId, prefixes);
                    } catch (Exception e) {
                        log.warn("Failed to register prefixes in GraphDB: {}", e.getMessage());
                    }
                }
                
                // Sync to Neo4j - TODO: Implement Neo4j sync service
                // neo4jSyncService.syncOntologyToNeo4j(projectId, ontology);
                
                updateStatus(projectId, "COMPLETED", "Ontology processed successfully.");
                log.info("Completed processing for project: {}", projectId);
            }
            
        } catch (Exception e) {
            log.error("Error processing ontology for project: {}", projectId, e);
            updateStatus(projectId, "ERROR", "Processing failed: " + e.getMessage());
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    private Map<String, Object> extractMetadata(String projectId, OWLOntology ontology) {
        Map<String, Object> metadata = new HashMap<>();
        
        // Basic counts
        metadata.put("classCount", ontology.getClassesInSignature().size()); //
        metadata.put("objectPropertyCount", ontology.getObjectPropertiesInSignature().size()); //
        metadata.put("dataPropertyCount", ontology.getDataPropertiesInSignature().size()); //
        metadata.put("individualCount", ontology.getIndividualsInSignature().size()); //
        metadata.put("axiomCount", ontology.getAxiomCount()); //
        metadata.put("logicalAxiomCount", ontology.getLogicalAxiomCount()); //
        
        // Ontology IRIs
        ontology.getOntologyID().getOntologyIRI()
                .ifPresent(iri -> metadata.put("ontologyIRI", iri.toString())); //
        ontology.getOntologyID().getVersionIRI()
                .ifPresent(iri -> metadata.put("versionIRI", iri.toString())); //
        
        // Axiom type counts
        metadata.put("declarationAxiomCount", ontology.getAxiomCount(AxiomType.DECLARATION)); //
        metadata.put("subClassOfAxiomCount", ontology.getAxiomCount(AxiomType.SUBCLASS_OF)); //
        metadata.put("equivalentClassesAxiomCount", ontology.getAxiomCount(AxiomType.EQUIVALENT_CLASSES)); //
        metadata.put("disjointClassesAxiomCount", ontology.getAxiomCount(AxiomType.DISJOINT_CLASSES)); //
        
        // GCI count (General Class Inclusions - anonymous subclass axioms)
        long gciCount = ontology.getAxioms(AxiomType.SUBCLASS_OF).stream()
                .filter(axiom -> axiom.getSubClass().isAnonymous())
                .count(); //
        metadata.put("gciCount", (int) gciCount);
        metadata.put("hiddenGciCount", 0); // Placeholder for reasoning results
        
        // Annotation properties (excluding built-in)
        long annPropCount = ontology.getAnnotationPropertiesInSignature().stream()
                .filter(ap -> !ap.isBuiltIn())
                .count(); //
        metadata.put("annotationPropertyCount", (int) annPropCount);

        // Extract prefixes
        Map<String, String> prefixes = new HashMap<>();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDocumentFormat format = manager.getOntologyFormat(ontology);
        
        if (format instanceof PrefixDocumentFormat) {
            PrefixDocumentFormat prefixFormat = (PrefixDocumentFormat) format;
            Map<String, String> map = prefixFormat.getPrefixName2PrefixMap();
            log.info("Found {} prefixes in ontology format", map.size());
            prefixes.putAll(map);
        } else {
            log.warn("Ontology format is not a PrefixDocumentFormat: {}", format != null ? format.getClass().getName() : "null");
            // Try to get from the manager's default prefix mapper if available
            // (Though usually it's in the format)
        }
        
        // Normalize prefixes: ensure they end with colon
        Map<String, String> normalizedPrefixes = new HashMap<>();
        prefixes.forEach((k, v) -> {
            String key = k;
            if (!key.endsWith(":") && !key.isEmpty()) {
                key += ":";
            } else if (key.isEmpty()) {
                key = ":";
            }
            normalizedPrefixes.put(key, v);
        });
        
        // Ensure common prefixes are present if not found
        if (!normalizedPrefixes.containsKey("rdf:")) normalizedPrefixes.put("rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        if (!normalizedPrefixes.containsKey("rdfs:")) normalizedPrefixes.put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        if (!normalizedPrefixes.containsKey("owl:")) normalizedPrefixes.put("owl:", "http://www.w3.org/2002/07/owl#");
        if (!normalizedPrefixes.containsKey("xsd:")) normalizedPrefixes.put("xsd:", "http://www.w3.org/2001/XMLSchema#");
        
        // Add ontology IRI as default prefix if not present
        ontology.getOntologyID().getOntologyIRI().ifPresent(iri -> {
            String iriStr = iri.toString();
            if (!iriStr.endsWith("/") && !iriStr.endsWith("#")) {
                iriStr += "#";
            }
            if (!normalizedPrefixes.containsKey(":")) {
                normalizedPrefixes.put(":", iriStr);
            }
        });
        
        metadata.put("prefixes", normalizedPrefixes);
        log.info("Extracted {} prefixes for project: {}", normalizedPrefixes.size(), projectId);
        normalizedPrefixes.forEach((k, v) -> log.debug("Prefix: {} -> {}", k, v));
        
        return metadata;
    }
    
    private void saveMetadataToMongo(String projectId, Map<String, Object> metadata) {
        Query query = new Query(Criteria.where("_id").is(projectId));
        Update update = new Update()
                .set("metadata", metadata)
                .set("updatedAt", new Date());
        
        mongo.upsert(query, update, "projects"); //
        log.info("Saved metadata to MongoDB for project: {}", projectId);
    }
    
    private void writeToTripleStore(String projectId, OWLOntology ontology) throws Exception {
        log.info("Writing ontology to triple store for project: {}", projectId);
        
        // Convert ontology to RDF/XML
        ByteArrayOutputStream rdfStream = new ByteArrayOutputStream();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.saveOntology(ontology, new RDFXMLDocumentFormat(), rdfStream); //
        byte[] rdfBytes = rdfStream.toByteArray();
        
        log.info("Converted ontology to RDF/XML: {} bytes", rdfBytes.length);
        
        // Get named graph URI
        String graphUri = props.getProjectGraphUri(projectId); //
        
        // Clear existing data in graph (best effort)
        clearGraph(graphUri); //
        
        // POST RDF data to triple store
        // For GraphDB: POST to /repositories/{repo}/statements?context={graph}
        // For Fuseki: POST to /{dataset}/data?graph={graph}
        String response = updateClient.post()
                .uri(uriBuilder -> uriBuilder
                        .queryParam("context", graphUri) //
                        .build())
                .header(HttpHeaders.CONTENT_TYPE, "application/rdf+xml") //
                .bodyValue(rdfBytes)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        
        log.info("Successfully wrote ontology to triple store");
    }
    
    private void clearGraph(String graphUri) {
        log.info("Clearing graph: {}", graphUri);
        
        String deleteQuery = String.format("CLEAR GRAPH <%s>", graphUri); //
        
        try {
            updateClient.post()
                    .uri("")
                    .header(HttpHeaders.CONTENT_TYPE, "application/sparql-update") //
                    .bodyValue(deleteQuery)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            log.info("Successfully cleared graph: {}", graphUri);
        } catch (Exception e) {
            log.warn("Failed to clear graph (may not exist yet): {}", e.getMessage());
        }
    }
    
    private void updateStatus(String projectId, String status, String message) {
        Query query = new Query(Criteria.where("_id").is(projectId));
        Update update = new Update()
                .set("status", status)
                .set("statusMessage", message)
                .set("updatedAt", new Date());
        
        mongo.updateFirst(query, update, "projects"); //
        log.info("Updated project status: {} - {}", projectId, status);
    }
}