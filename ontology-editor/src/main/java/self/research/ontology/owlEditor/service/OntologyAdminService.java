package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class OntologyAdminService {

    private static final Logger log = LoggerFactory.getLogger(OntologyAdminService.class);

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        """;

    private final SparqlDatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;

    public OntologyAdminService(SparqlDatasetService datasetService,
                                OntologyIndexService indexService,
                                ProjectMetadataService metadataService) {
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
    }

    public Map<String, String> getOntologyId(String projectId) {
        String query = PREFIXES + """
            SELECT ?ont ?version WHERE {
              ?ont a owl:Ontology .
              OPTIONAL { ?ont owl:versionIRI ?version }
            } LIMIT 1
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        Map<String, String> result = new LinkedHashMap<>();
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            if (sol.hasBinding("ont")) {
                result.put("ontologyIRI", sol.getValue("ont").stringValue());
            }
            if (sol.hasBinding("version")) {
                result.put("versionIRI", sol.getValue("version").stringValue());
            }
        }
        return result;
    }

    public List<Map<String, String>> listOntologyAnnotations(String projectId) {
        String query = PREFIXES + """
            SELECT ?prop ?value ?lang ?datatype WHERE {
              ?ont a owl:Ontology .
              ?ont ?prop ?value .
              FILTER(?prop != rdf:type)
              FILTER(?prop != owl:imports)
              FILTER(?prop != owl:versionIRI)
              OPTIONAL { BIND(lang(?value) AS ?lang) }
              OPTIONAL { BIND(datatype(?value) AS ?datatype) }
            }
            ORDER BY ?prop ?value
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<Map<String, String>> annotations = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("propertyIri", sol.getValue("prop").stringValue());
            Value value = sol.getValue("value");
            row.put("value", value != null ? value.stringValue() : "");
            if (sol.hasBinding("lang")) {
                String lang = sol.getValue("lang").stringValue();
                if (lang != null && !lang.isBlank()) {
                    row.put("lang", lang);
                }
            }
            if (sol.hasBinding("datatype")) {
                String datatype = sol.getValue("datatype").stringValue();
                if (datatype != null && !datatype.isBlank()) {
                    row.put("datatype", datatype);
                }
            }
            annotations.add(row);
        }
        return annotations;
    }

    public void addOntologyAnnotation(String projectId, String propertyIri, String value, String datatypeIri) {
        String ontologyIri = getOntologyId(projectId).get("ontologyIRI");
        if (ontologyIri == null || ontologyIri.isBlank()) {
            throw new IllegalStateException("Ontology IRI not found");
        }
        String literal = toTypedLiteral(value, datatypeIri);
        String update = PREFIXES + """
            INSERT DATA {
              <%s> <%s> %s .
            }
            """.formatted(ontologyIri, propertyIri, literal);
        datasetService.execUpdate(projectId, update);
        refreshMetadata(projectId);
    }

    public void updateOntologyAnnotation(String projectId, String propertyIri, String oldValue, String newValue, String datatypeIri) {
        String ontologyIri = getOntologyId(projectId).get("ontologyIRI");
        if (ontologyIri == null || ontologyIri.isBlank()) {
            throw new IllegalStateException("Ontology IRI not found");
        }
        String oldLiteral = toTypedLiteral(oldValue, datatypeIri);
        String newLiteral = toTypedLiteral(newValue, datatypeIri);
        String update = PREFIXES + """
            DELETE DATA { <%s> <%s> %s . };
            INSERT DATA { <%s> <%s> %s . }
            """.formatted(ontologyIri, propertyIri, oldLiteral, ontologyIri, propertyIri, newLiteral);
        datasetService.execUpdate(projectId, update);
        refreshMetadata(projectId);
    }

    public void deleteOntologyAnnotation(String projectId, String propertyIri, String value, String datatypeIri) {
        String ontologyIri = getOntologyId(projectId).get("ontologyIRI");
        if (ontologyIri == null || ontologyIri.isBlank()) {
            throw new IllegalStateException("Ontology IRI not found");
        }
        String literal = toTypedLiteral(value, datatypeIri);
        String update = PREFIXES + """
            DELETE DATA {
              <%s> <%s> %s .
            }
            """.formatted(ontologyIri, propertyIri, literal);
        datasetService.execUpdate(projectId, update);
        refreshMetadata(projectId);
    }

    public void addImport(String projectId, String importIri) {
        String ontologyIri = getOntologyId(projectId).get("ontologyIRI");
        if (ontologyIri == null || ontologyIri.isBlank()) {
            throw new IllegalStateException("Ontology IRI not found");
        }
        String update = PREFIXES + """
            INSERT DATA {
              <%s> owl:imports <%s> .
            }
            """.formatted(ontologyIri, importIri);
        datasetService.execUpdate(projectId, update);
        refreshMetadata(projectId);
    }

    public void removeImport(String projectId, String importIri) {
        String ontologyIri = getOntologyId(projectId).get("ontologyIRI");
        if (ontologyIri == null || ontologyIri.isBlank()) {
            throw new IllegalStateException("Ontology IRI not found");
        }
        String update = PREFIXES + """
            DELETE DATA {
              <%s> owl:imports <%s> .
            }
            """.formatted(ontologyIri, importIri);
        datasetService.execUpdate(projectId, update);
        refreshMetadata(projectId);
    }

    public void updateOntologyId(String projectId, String newOntologyIri, String versionIri) {
        Map<String, String> current = getOntologyId(projectId);
        String currentOntologyIri = current.get("ontologyIRI");
        if (newOntologyIri == null || newOntologyIri.isBlank()) {
            throw new IllegalArgumentException("ontologyIRI is required");
        }

        if (currentOntologyIri != null && !currentOntologyIri.equals(newOntologyIri)) {
            String move = PREFIXES + """
                DELETE { <%s> ?p ?o }
                INSERT { <%s> ?p ?o }
                WHERE  { <%s> ?p ?o }
                """.formatted(currentOntologyIri, newOntologyIri, currentOntologyIri);
            datasetService.execUpdate(projectId, move);
        }

        if (versionIri != null) {
            String updateVersion = PREFIXES + """
                DELETE { <%s> owl:versionIRI ?old }
                INSERT { <%s> owl:versionIRI <%s> }
                WHERE  { OPTIONAL { <%s> owl:versionIRI ?old } }
                """.formatted(newOntologyIri, newOntologyIri, versionIri, newOntologyIri);
            datasetService.execUpdate(projectId, updateVersion);
        }

        refreshMetadata(projectId);
    }

    public List<String> getImports(String projectId) {
        String query = PREFIXES + """
            SELECT DISTINCT ?import WHERE {
              ?ont a owl:Ontology .
              ?ont owl:imports ?import .
            }
            ORDER BY ?import
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<String> imports = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            if (sol.hasBinding("import")) {
                imports.add(sol.getValue("import").stringValue());
            }
        }
        return imports;
    }

    public Map<String, String> getPrefixes(String projectId) {
        return datasetService.getPrefixes(projectId);
    }

    public void updatePrefixes(String projectId, Map<String, String> prefixes) {
        try (RepositoryConnection conn = datasetService.getConnection()) {
            // Remove prefixes that were explicitly set to empty
            for (Map.Entry<String, String> entry : prefixes.entrySet()) {
                String prefix = entry.getKey();
                if (prefix != null && prefix.endsWith(":")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                }
                
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    if (prefix != null) {
                        conn.removeNamespace(prefix);
                    }
                }
            }
            // Set/update prefixes with values
            for (Map.Entry<String, String> entry : prefixes.entrySet()) {
                String prefix = entry.getKey();
                if (prefix != null && prefix.endsWith(":")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                }

                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    conn.setNamespace(prefix, entry.getValue());
                }
            }
        }
    }

    private void refreshMetadata(String projectId) {
        try {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            metadataService.writeMeta(projectId, meta);
        } catch (Exception e) {
            log.warn("Failed to refresh metadata for project {}: {}", projectId, e.getMessage());
        }
    }

    private String toTypedLiteral(String value, String datatypeIri) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        if (datatypeIri == null || datatypeIri.isBlank() || "xsd:string".equals(datatypeIri)) {
            return "\"" + escaped + "\"";
        }
        return "\"" + escaped + "\"^^<" + datatypeIri + ">";
    }
}
