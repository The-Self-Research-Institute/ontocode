package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.util.*;

/**
 * Service for managing citations in ontologies.
 * Handles insertion of citation RDF data into GraphDB repositories.
 */
@Service
public class CitationService {

    private static final Logger log = LoggerFactory.getLogger(CitationService.class);

    @Autowired
    private GraphDBDatasetService datasetService;

    @Autowired
    private GraphDBHistoryService historyService;

    /**
     * Insert citation RDF data into the project's GraphDB repository at a specific line
     * 
     * @param projectId - the project ID
     * @param citationContent - the RDF content (Turtle or RDF/XML)
     * @param format - "turtle" or "rdfxml"
     * @param metadata - optional metadata about the citation (title, authors, etc.)
     * @param lineNumber - line number where citation should be inserted (0 = end of file)
     */
    public void insertCitation(String projectId, String citationContent, String format, Map<String, Object> metadata, int lineNumber) {
        try {
            log.info("[CitationService] Inserting citation into project: {} at line: {}", projectId, lineNumber);

            // Parse the RDF content
            RDFFormat rdfFormat = format.equalsIgnoreCase("turtle") ? RDFFormat.TURTLE : RDFFormat.RDFXML;
            
            Model citationModel;
            try (StringReader reader = new StringReader(citationContent)) {
                citationModel = Rio.parse(reader, "", rdfFormat);
            }

            log.info("[CitationService] Parsed {} RDF statements from citation", citationModel.size());

            if (lineNumber > 0) {
                // Insert at specific line by manipulating the source code
                insertCitationAtLine(projectId, citationContent, format, citationModel, lineNumber);
            } else {
                // Insert at end (traditional method)
                insertCitationAtEnd(projectId, citationModel, format);
            }

            // Record in history
            String title = metadata != null && metadata.containsKey("title") 
                ? metadata.get("title").toString() 
                : "Citation";
            
            historyService.recordEdit(
                projectId,
                "system",
                "Citation Manager",
                "INSERT_CITATION",
                getCitationIRI(citationModel),
                title,
                null,
                citationContent,
                "Inserted citation at line " + lineNumber + ": " + title,
                null
            );

            log.info("[CitationService] Successfully inserted citation into project: {}", projectId);

        } catch (Exception e) {
            log.error("[CitationService] Error inserting citation into project: {}", projectId, e);
            throw new RuntimeException("Failed to insert citation: " + e.getMessage(), e);
        }
    }

    /**
     * Insert citation at a specific line position
     * Note: RDF triples are inserted into the graph at the semantic layer.
     * Line numbers are a syntactic property of the serialized format.
     * The file persistence (with citations at specific lines) is handled by the frontend upload.
     * This method ensures the citation triples are in the graph regardless of file position.
     */
    private void insertCitationAtLine(String projectId, String citationContent, String format, Model citationModel, int lineNumber) throws Exception {
        log.info("[CitationService] Insert citation at line {} - storing RDF triples in graph", lineNumber);
        
        // Insert RDF triples into the graph (line positioning is handled by frontend file upload)
        insertCitationAtEnd(projectId, citationModel, format);
        
        log.info("[CitationService] Citation RDF triples inserted into graph (file line positioning handled by frontend)");
    }

    /**
     * Insert citation at the end of the ontology
     */
    private void insertCitationAtEnd(String projectId, Model citationModel, String format) throws Exception {
        // Convert model to SPARQL INSERT query
        StringBuilder insertQuery = new StringBuilder();
        insertQuery.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n");
        insertQuery.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n");
        insertQuery.append("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n");
        insertQuery.append("PREFIX dc: <http://purl.org/dc/elements/1.1/>\n");
        insertQuery.append("PREFIX dcterms: <http://purl.org/dc/terms/>\n");
        insertQuery.append("PREFIX bibo: <http://purl.org/ontology/bibo/>\n");
        insertQuery.append("PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n");
        insertQuery.append("PREFIX prov: <http://www.w3.org/ns/prov#>\n");
        insertQuery.append("PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n");
        insertQuery.append("\n");
        insertQuery.append("INSERT DATA {\n");

        for (Statement st : citationModel) {
            insertQuery.append("  ");
            insertQuery.append(formatValue(st.getSubject().toString()));
            insertQuery.append(" ");
            insertQuery.append(formatValue(st.getPredicate().toString()));
            insertQuery.append(" ");
            
            if (st.getObject() instanceof org.eclipse.rdf4j.model.Literal) {
                org.eclipse.rdf4j.model.Literal literal = (org.eclipse.rdf4j.model.Literal) st.getObject();
                insertQuery.append("\"\"\"").append(escapeLiteral(literal.stringValue())).append("\"\"\"");
                
                if (literal.getLanguage().isPresent()) {
                    insertQuery.append("@").append(literal.getLanguage().get());
                } else if (literal.getDatatype() != null) {
                    insertQuery.append("^^").append(formatValue(literal.getDatatype().toString()));
                }
            } else {
                insertQuery.append(formatValue(st.getObject().toString()));
            }
            
            insertQuery.append(" .\n");
        }

        insertQuery.append("}");

        // Execute SPARQL update
        String sparqlUpdate = insertQuery.toString();
        log.debug("[CitationService] SPARQL Update:\n{}", sparqlUpdate);
        datasetService.execUpdate(projectId.replaceAll("\\$.*", ""), sparqlUpdate);
    }

    /**
     * Overloaded method for backward compatibility - inserts at end of file (lineNumber = 0)
     */
    public void insertCitation(String projectId, String citationContent, String format, Map<String, Object> metadata) {
        insertCitation(projectId, citationContent, format, metadata, 0);
    }

    /**
     * Get all citations from a project's ontology
     * 
     * @param projectId - the project ID
     * @return list of citation metadata
     */
    public List<Map<String, Object>> getCitations(String projectId) {
        try {
            log.info("[CitationService] Retrieving citations for project: {}", projectId);

            // Query for bibliographic resources (using common citation ontologies)
            String sparqlQuery = """
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX dc: <http://purl.org/dc/elements/1.1/>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                PREFIX bibo: <http://purl.org/ontology/bibo/>
                PREFIX prov: <http://www.w3.org/ns/prov#>
                
                SELECT DISTINCT ?citation ?title ?creator ?date ?doi
                WHERE {
                  {
                    ?citation rdf:type bibo:Article .
                  } UNION {
                    ?citation rdf:type bibo:Book .
                  } UNION {
                    ?citation rdf:type bibo:Document .
                  } UNION {
                    ?citation rdf:type prov:Entity .
                    ?citation dc:title ?anyTitle .
                  }
                  
                  OPTIONAL { ?citation dc:title ?title }
                  OPTIONAL { ?citation dcterms:title ?title }
                  OPTIONAL { ?citation dc:creator ?creator }
                  OPTIONAL { ?citation dcterms:creator ?creator }
                  OPTIONAL { ?citation dc:date ?date }
                  OPTIONAL { ?citation dcterms:issued ?date }
                  OPTIONAL { ?citation bibo:doi ?doi }
                }
                ORDER BY ?title
                """;

            List<Map<String, Object>> citations = new ArrayList<>();
            
            // Use GraphDBDatasetService to execute query
            RepositoryConnection conn = datasetService.getConnection();
            try {
                TupleQueryResult results = datasetService.executeQuery(conn, projectId, sparqlQuery);
                
                while (results.hasNext()) {
                    BindingSet binding = results.next();
                    Map<String, Object> citation = new HashMap<>();
                    
                    if (binding.hasBinding("citation")) {
                        citation.put("iri", binding.getValue("citation").stringValue());
                    }
                    
                    if (binding.hasBinding("title")) {
                        citation.put("title", binding.getValue("title").stringValue());
                    }
                    
                    if (binding.hasBinding("creator")) {
                        citation.put("creator", binding.getValue("creator").stringValue());
                    }
                    
                    if (binding.hasBinding("date")) {
                        citation.put("date", binding.getValue("date").stringValue());
                    }
                    
                    if (binding.hasBinding("doi")) {
                        citation.put("doi", binding.getValue("doi").stringValue());
                    }
                    
                    citations.add(citation);
                }
                
                results.close();
            } finally {
                conn.close();
            }

            log.info("[CitationService] Found {} citations for project: {}", citations.size(), projectId);
            return citations;

        } catch (Exception e) {
            log.error("[CitationService] Error retrieving citations for project: {}", projectId, e);
            throw new RuntimeException("Failed to retrieve citations: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a citation from the ontology
     * 
     * @param projectId - the project ID
     * @param citationIRI - the IRI of the citation to delete
     */
    public void deleteCitation(String projectId, String citationIRI) {
        try {
            log.info("[CitationService] Deleting citation {} from project: {}", citationIRI, projectId);

            // Delete all triples where citation is the subject OR object
            String sparqlUpdate = String.format("""
                DELETE {
                  <%s> ?p ?o .
                  ?s ?p2 <%s> .
                }
                WHERE {
                  {
                    <%s> ?p ?o .
                  }
                  UNION
                  {
                    ?s ?p2 <%s> .
                  }
                }
                """, citationIRI, citationIRI, citationIRI, citationIRI);

            datasetService.execUpdate(projectId, sparqlUpdate);

            log.info("[CitationService] Successfully deleted citation from project: {}", projectId);

            // Record in history
            historyService.recordEdit(
                projectId,
                "system",
                "Citation Manager",
                "DELETE_CITATION",
                citationIRI,
                "Citation",
                citationIRI,
                null,
                "Deleted citation: " + citationIRI,
                null
            );

        } catch (Exception e) {
            log.error("[CitationService] Error deleting citation from project: {}", projectId, e);
            throw new RuntimeException("Failed to delete citation: " + e.getMessage(), e);
        }
    }

    /**
     * Format an RDF value for SPARQL (wrap in angle brackets if IRI)
     */
    private String formatValue(String value) {
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("urn:")) {
            return "<" + value + ">";
        }
        return value;
    }

    /**
     * Escape special characters in literals
     */
    private String escapeLiteral(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * Extract the main citation IRI from the RDF model
     */
    private String getCitationIRI(Model model) {
        // Find the first subject that's a bibliographic resource
        for (Statement st : model) {
            if (st.getPredicate().toString().contains("type") && 
                (st.getObject().toString().contains("Article") || 
                 st.getObject().toString().contains("Book") ||
                 st.getObject().toString().contains("Document") ||
                 st.getObject().toString().contains("Entity"))) {
                return st.getSubject().toString();
            }
        }
        
        // Fallback: return first subject
        if (!model.isEmpty()) {
            return model.iterator().next().getSubject().toString();
        }
        
        return "unknown";
    }
}
