package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OntologyIndexService {

    private static final Logger log = LoggerFactory.getLogger(OntologyIndexService.class);

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

    private final SparqlDatasetService datasetService;
    private final OntologyMetadataService metadataService;

    public OntologyIndexService(SparqlDatasetService datasetService, OntologyMetadataService metadataService) {
        this.datasetService = datasetService;
        this.metadataService = metadataService;
    }

    public Map<String, Object> computeMetadata(String projectId) {
        long totalStart = System.nanoTime();
        log.info("[IndexService {}] ═══ computeMetadata STARTED", projectId);

        long queryStart = System.nanoTime();
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
        log.info("[IndexService {}] [TIMING] Metrics query (counts): {} ms", projectId, (System.nanoTime() - queryStart) / 1_000_000);

        // Get ontology IRI
        queryStart = System.nanoTime();
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
        log.info("[IndexService {}] [TIMING] Ontology IRI query: {} ms", projectId, (System.nanoTime() - queryStart) / 1_000_000);

        // Count axiom types — batched into a single SPARQL query using subqueries
        // to avoid 14+ sequential round-trips to GraphDB (reduces ~60s to ~5-8s)
        queryStart = System.nanoTime();
        Map<String, Integer> axiomCounts = new LinkedHashMap<>();
        String batchedAxiomQuery = PREFIXES + """
            SELECT ?subClassOf ?equivalentClasses ?disjointClasses
                   ?subObjectPropertyOf ?inverseObjectProperties
                   ?objectPropertyDomain ?objectPropertyRange
                   ?dataPropertyDomain ?dataPropertyRange
                   ?classAssertion ?objectPropertyAssertion ?dataPropertyAssertion
                   ?annotationAssertion ?datatypeCount ?importsCount
            WHERE {
              { SELECT (COUNT(*) AS ?subClassOf) WHERE { ?sub rdfs:subClassOf ?super } }
              { SELECT (COUNT(*) AS ?equivalentClasses) WHERE { ?c1 owl:equivalentClass ?c2 } }
              { SELECT (COUNT(*) AS ?disjointClasses) WHERE { ?c1 owl:disjointWith ?c2 } }
              { SELECT (COUNT(*) AS ?subObjectPropertyOf) WHERE { ?p a owl:ObjectProperty . ?p rdfs:subPropertyOf ?super . } }
              { SELECT (COUNT(*) AS ?inverseObjectProperties) WHERE { ?p owl:inverseOf ?q . } }
              { SELECT (COUNT(*) AS ?objectPropertyDomain) WHERE { ?p a owl:ObjectProperty . ?p rdfs:domain ?c . } }
              { SELECT (COUNT(*) AS ?objectPropertyRange) WHERE { ?p a owl:ObjectProperty . ?p rdfs:range ?c . } }
              { SELECT (COUNT(*) AS ?dataPropertyDomain) WHERE { ?p a owl:DatatypeProperty . ?p rdfs:domain ?c . } }
              { SELECT (COUNT(*) AS ?dataPropertyRange) WHERE { ?p a owl:DatatypeProperty . ?p rdfs:range ?d . } }
              { SELECT (COUNT(*) AS ?classAssertion) WHERE { ?ind rdf:type ?class . FILTER(?class != owl:NamedIndividual) } }
              { SELECT (COUNT(*) AS ?objectPropertyAssertion) WHERE { ?s ?p ?o . ?p a owl:ObjectProperty . } }
              { SELECT (COUNT(*) AS ?dataPropertyAssertion) WHERE { ?s ?p ?o . ?p a owl:DatatypeProperty . } }
              { SELECT (COUNT(*) AS ?annotationAssertion) WHERE { ?s ?p ?o . ?p a owl:AnnotationProperty . } }
              { SELECT (COUNT(DISTINCT ?dt) AS ?datatypeCount) WHERE { ?dt a rdfs:Datatype . } }
              { SELECT (COUNT(*) AS ?importsCount) WHERE { ?ont a owl:Ontology . ?ont owl:imports ?imp . } }
            }
            """;
        int datatypeCount = 0;
        int importsCount = 0;
        TupleQueryResult axiomRs = datasetService.execSelect(projectId, batchedAxiomQuery);
        if (axiomRs.hasNext()) {
            BindingSet sol = axiomRs.next();
            axiomCounts.put("subClassOf", literalToInt(sol, "subClassOf"));
            axiomCounts.put("equivalentClasses", literalToInt(sol, "equivalentClasses"));
            axiomCounts.put("disjointClasses", literalToInt(sol, "disjointClasses"));
            axiomCounts.put("subObjectPropertyOf", literalToInt(sol, "subObjectPropertyOf"));
            axiomCounts.put("inverseObjectProperties", literalToInt(sol, "inverseObjectProperties"));
            axiomCounts.put("objectPropertyDomain", literalToInt(sol, "objectPropertyDomain"));
            axiomCounts.put("objectPropertyRange", literalToInt(sol, "objectPropertyRange"));
            axiomCounts.put("dataPropertyDomain", literalToInt(sol, "dataPropertyDomain"));
            axiomCounts.put("dataPropertyRange", literalToInt(sol, "dataPropertyRange"));
            axiomCounts.put("classAssertion", literalToInt(sol, "classAssertion"));
            axiomCounts.put("objectPropertyAssertion", literalToInt(sol, "objectPropertyAssertion"));
            axiomCounts.put("dataPropertyAssertion", literalToInt(sol, "dataPropertyAssertion"));
            axiomCounts.put("annotationAssertion", literalToInt(sol, "annotationAssertion"));
            datatypeCount = literalToInt(sol, "datatypeCount");
            importsCount = literalToInt(sol, "importsCount");
        } else {
            // Fallback: all zeros
            for (String key : List.of("subClassOf", "equivalentClasses", "disjointClasses",
                    "subObjectPropertyOf", "inverseObjectProperties", "objectPropertyDomain",
                    "objectPropertyRange", "dataPropertyDomain", "dataPropertyRange",
                    "classAssertion", "objectPropertyAssertion", "dataPropertyAssertion",
                    "annotationAssertion")) {
                axiomCounts.put(key, 0);
            }
        }
        log.info("[IndexService {}] [TIMING] Batched axiom count query: {} ms", projectId, (System.nanoTime() - queryStart) / 1_000_000);
        
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
        queryStart = System.nanoTime();
        meta.put("annotations", metadataService.getOntologyAnnotations(projectId));
        log.info("[IndexService {}] [TIMING] Annotations query: {} ms", projectId, (System.nanoTime() - queryStart) / 1_000_000);
        
        // Add ontology imports
        queryStart = System.nanoTime();
        meta.put("imports", metadataService.getOntologyImports(projectId));
        log.info("[IndexService {}] [TIMING] Imports query: {} ms", projectId, (System.nanoTime() - queryStart) / 1_000_000);
        
        // Add general class axioms
        queryStart = System.nanoTime();
        meta.put("axioms", metadataService.getGeneralClassAxioms(projectId));
        log.info("[IndexService {}] [TIMING] General class axioms query: {} ms", projectId, (System.nanoTime() - queryStart) / 1_000_000);
        
        // Add axiom counts for display
        meta.put("axiomCount", logicalAxioms + declarations);
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
        
        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
        log.info("[IndexService {}] ═══ computeMetadata COMPLETED in {} ms ({} sec)", projectId, totalMs, totalMs / 1000);

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

