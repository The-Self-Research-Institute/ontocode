package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OntologyIndexService {

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        """;

    private static final String METRICS_QUERY = PREFIXES + """
        SELECT
          (COUNT(DISTINCT ?class) AS ?classCount)
          (COUNT(DISTINCT ?objProp) AS ?objectPropertyCount)
          (COUNT(DISTINCT ?dataProp) AS ?dataPropertyCount)
          (COUNT(DISTINCT ?individual) AS ?individualCount)
          (COUNT(DISTINCT ?annProp) AS ?annotationPropertyCount)
          (COUNT(?triple) AS ?tripleCount)
        WHERE {
          {
            {
              ?class a owl:Class .
            } UNION {
              ?class rdfs:subClassOf ?any .
            } UNION {
              ?any rdfs:subClassOf ?class .
            }
            FILTER(isIRI(?class) && ?class != owl:Thing)
          }
          UNION
          {
            ?objProp a owl:ObjectProperty .
            FILTER(!isBlank(?objProp))
          }
          UNION
          {
            ?dataProp a owl:DatatypeProperty .
            FILTER(!isBlank(?dataProp))
          }
          UNION
          {
            ?individual a owl:NamedIndividual .
          }
          UNION
          {
            ?annProp a owl:AnnotationProperty .
            FILTER(!isBlank(?annProp))
          }
          UNION
          {
            ?s ?p ?o .
            BIND(?s AS ?triple)
          }
        }
        """;

    private final GraphDBDatasetService datasetService;
    private final OntologyMetadataService metadataService;

    public OntologyIndexService(GraphDBDatasetService datasetService, OntologyMetadataService metadataService) {
        this.datasetService = datasetService;
        this.metadataService = metadataService;
    }

    public Map<String, Object> computeMetadata(String projectId) {
        TupleQueryResult rs = datasetService.execSelect(projectId, METRICS_QUERY);
        Map<String, Object> counts = new LinkedHashMap<>();
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            counts.put("classes", literalToInt(sol, "classCount"));
            counts.put("objectProperties", literalToInt(sol, "objectPropertyCount"));
            counts.put("dataProperties", literalToInt(sol, "dataPropertyCount"));
            counts.put("individuals", literalToInt(sol, "individualCount"));
            counts.put("annotationProperties", literalToInt(sol, "annotationPropertyCount"));
            counts.put("triples", literalToInt(sol, "tripleCount"));
        }

        // Get ontology IRI
        String ontologyIri = null;
        String versionIri = null;
        String ontQuery = PREFIXES + "SELECT ?ont ?version WHERE { ?ont a owl:Ontology . OPTIONAL { ?ont owl:versionIRI ?version } } LIMIT 1";
        TupleQueryResult ontRs = datasetService.execSelect(projectId, ontQuery);
        if (ontRs.hasNext()) {
            BindingSet sol = ontRs.next();
            if (sol.hasBinding("ont")) {
                ontologyIri = sol.getValue("ont").stringValue();
            }
            if (sol.hasBinding("version")) {
                versionIri = sol.getValue("version").stringValue();
            }
        }

        // Count axiom types
        Map<String, Integer> axiomCounts = new LinkedHashMap<>();
        
        // SubClassOf axioms
        String subClassQuery = PREFIXES + "SELECT (COUNT(*) AS ?count) WHERE { ?sub rdfs:subClassOf ?super }";
        axiomCounts.put("subClassOf", querySingleCount(projectId, subClassQuery));
        
        // EquivalentClasses (simplified - full tracking requires reasoning)
        String equivClassQuery = PREFIXES + "SELECT (COUNT(*) AS ?count) WHERE { ?c1 owl:equivalentClass ?c2 }";
        axiomCounts.put("equivalentClasses", querySingleCount(projectId, equivClassQuery));
        
        // DisjointClasses
        String disjointQuery = PREFIXES + "SELECT (COUNT(*) AS ?count) WHERE { ?c1 owl:disjointWith ?c2 }";
        axiomCounts.put("disjointClasses", querySingleCount(projectId, disjointQuery));

        // Object property axioms
        String subObjectPropertyQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?p a owl:ObjectProperty .
              ?p rdfs:subPropertyOf ?super .
            }
            """;
        axiomCounts.put("subObjectPropertyOf", querySingleCount(projectId, subObjectPropertyQuery));

        String inverseObjectPropertyQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?p owl:inverseOf ?q .
            }
            """;
        axiomCounts.put("inverseObjectProperties", querySingleCount(projectId, inverseObjectPropertyQuery));

        String objectPropertyDomainQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?p a owl:ObjectProperty .
              ?p rdfs:domain ?c .
            }
            """;
        axiomCounts.put("objectPropertyDomain", querySingleCount(projectId, objectPropertyDomainQuery));

        String objectPropertyRangeQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?p a owl:ObjectProperty .
              ?p rdfs:range ?c .
            }
            """;
        axiomCounts.put("objectPropertyRange", querySingleCount(projectId, objectPropertyRangeQuery));

        // Data property axioms
        String dataPropertyDomainQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?p a owl:DatatypeProperty .
              ?p rdfs:domain ?c .
            }
            """;
        axiomCounts.put("dataPropertyDomain", querySingleCount(projectId, dataPropertyDomainQuery));

        String dataPropertyRangeQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?p a owl:DatatypeProperty .
              ?p rdfs:range ?d .
            }
            """;
        axiomCounts.put("dataPropertyRange", querySingleCount(projectId, dataPropertyRangeQuery));

        // Assertion axioms
        String classAssertionQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?ind rdf:type ?class .
              FILTER(?class != owl:NamedIndividual)
            }
            """;
        axiomCounts.put("classAssertion", querySingleCount(projectId, classAssertionQuery));

        String objectPropertyAssertionQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?s ?p ?o .
              ?p a owl:ObjectProperty .
            }
            """;
        axiomCounts.put("objectPropertyAssertion", querySingleCount(projectId, objectPropertyAssertionQuery));

        String dataPropertyAssertionQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?s ?p ?o .
              ?p a owl:DatatypeProperty .
            }
            """;
        axiomCounts.put("dataPropertyAssertion", querySingleCount(projectId, dataPropertyAssertionQuery));

        String annotationAssertionQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?s ?p ?o .
              ?p a owl:AnnotationProperty .
            }
            """;
        axiomCounts.put("annotationAssertion", querySingleCount(projectId, annotationAssertionQuery));

        String datatypeCountQuery = PREFIXES + """
            SELECT (COUNT(DISTINCT ?dt) AS ?count) WHERE {
              ?dt a rdfs:Datatype .
            }
            """;
        int datatypeCount = querySingleCount(projectId, datatypeCountQuery);

        String importsCountQuery = PREFIXES + """
            SELECT (COUNT(*) AS ?count) WHERE {
              ?ont a owl:Ontology .
              ?ont owl:imports ?imp .
            }
            """;
        int importsCount = querySingleCount(projectId, importsCountQuery);
        
        // Declaration axioms (classes + properties + individuals)
        int declarations = (int) counts.getOrDefault("classes", 0) 
                         + (int) counts.getOrDefault("objectProperties", 0)
                         + (int) counts.getOrDefault("dataProperties", 0)
                         + (int) counts.getOrDefault("individuals", 0)
                         + (int) counts.getOrDefault("annotationProperties", 0);
        axiomCounts.put("declaration", declarations);
        
        // Total logical axioms (rough estimate)
        int logicalAxioms = axiomCounts.get("subClassOf") 
                          + axiomCounts.get("equivalentClasses")
                          + axiomCounts.get("disjointClasses");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("counts", counts);
        meta.put("prefixes", datasetService.getPrefixes(projectId));
        meta.put("lastUpdated", Instant.now().toString());
        meta.put("cacheComplete", true);  // Mark cache as complete for fast loading
        meta.put("cachedAt", Instant.now().toString());
        
        // Add ontology identity
        if (ontologyIri != null) {
            meta.put("ontologyIRI", ontologyIri);
        }
        if (versionIri != null) {
            meta.put("versionIRI", versionIri);
        }
        
        // Add ontology annotations
        meta.put("annotations", metadataService.getOntologyAnnotations(projectId));
        
        // Add ontology imports
        meta.put("imports", metadataService.getOntologyImports(projectId));
        
        // Add general class axioms
        meta.put("axioms", metadataService.getGeneralClassAxioms(projectId));
        
        // Add axiom counts for Protégé-like display
        meta.put("axiomCount", (int) counts.getOrDefault("triples", 0));
        meta.put("logicalAxiomCount", logicalAxioms);
        meta.put("declarationAxiomCount", declarations);
        meta.put("classCount", counts.getOrDefault("classes", 0));
        meta.put("objectPropertyCount", counts.getOrDefault("objectProperties", 0));
        meta.put("dataPropertyCount", counts.getOrDefault("dataProperties", 0));
        meta.put("individualCount", counts.getOrDefault("individuals", 0));
        meta.put("annotationPropertyCount", counts.getOrDefault("annotationProperties", 0));
        meta.put("datatypeCount", datatypeCount);
        meta.put("importsCount", importsCount);
        meta.put("prefixCount", meta.containsKey("prefixes") ? ((Map<?, ?>) meta.get("prefixes")).size() : 0);
        meta.put("subClassOfAxiomCount", axiomCounts.get("subClassOf"));
        meta.put("equivalentClassesAxiomCount", axiomCounts.get("equivalentClasses"));
        meta.put("disjointClassesAxiomCount", axiomCounts.get("disjointClasses"));
        meta.put("subObjectPropertyOfAxiomCount", axiomCounts.get("subObjectPropertyOf"));
        meta.put("inverseObjectPropertiesAxiomCount", axiomCounts.get("inverseObjectProperties"));
        meta.put("objectPropertyDomainAxiomCount", axiomCounts.get("objectPropertyDomain"));
        meta.put("objectPropertyRangeAxiomCount", axiomCounts.get("objectPropertyRange"));
        meta.put("dataPropertyDomainAxiomCount", axiomCounts.get("dataPropertyDomain"));
        meta.put("dataPropertyRangeAxiomCount", axiomCounts.get("dataPropertyRange"));
        meta.put("classAssertionAxiomCount", axiomCounts.get("classAssertion"));
        meta.put("objectPropertyAssertionCount", axiomCounts.get("objectPropertyAssertion"));
        meta.put("dataPropertyAssertionCount", axiomCounts.get("dataPropertyAssertion"));
        meta.put("annotationAssertionCount", axiomCounts.get("annotationAssertion"));
        meta.put("gciCount", 0);  // General Class Inclusions - requires reasoning
        meta.put("hiddenGciCount", 0);
        
        return meta;
    }
    
    private int querySingleCount(String projectId, String query) {
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            if (sol.hasBinding("count")) {
                return Integer.parseInt(sol.getValue("count").stringValue());
            }
        }
        return 0;
    }

    private int literalToInt(BindingSet sol, String var) {
        if (sol.hasBinding(var) && sol.getValue(var) != null) {
            return Integer.parseInt(sol.getValue(var).stringValue());
        }
        return 0;
    }
}

