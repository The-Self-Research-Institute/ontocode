package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;

import java.util.HashMap;
import java.util.Map;

@Slf4j
final class OntologyCountQueries {

    private static final String PREFIXES = OntologyMetadataService.PREFIXES;

    private static final String ANNOTATION_PROPERTY_COUNT_QUERY = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        SELECT (COUNT(DISTINCT ?annProp) AS ?count) WHERE {
          {
            ?annProp a owl:AnnotationProperty .
          }
          UNION
          {
            ?s ?annProp ?o .
            FILTER(isLiteral(?o) || isIRI(?o))
            FILTER NOT EXISTS { ?annProp a owl:ObjectProperty }
            FILTER NOT EXISTS { ?annProp a owl:DatatypeProperty }
            FILTER NOT EXISTS { ?annProp a owl:Class }
            FILTER(STRSTARTS(STR(?annProp), "http://www.w3.org/2003/11/swrl#") = false)
            FILTER(?annProp NOT IN (
              rdf:type, rdfs:subClassOf, rdfs:subPropertyOf, rdfs:domain, rdfs:range,
              owl:equivalentClass, owl:disjointWith, owl:equivalentProperty, owl:inverseOf,
              owl:onProperty, owl:someValuesFrom, owl:allValuesFrom, owl:hasValue, owl:onClass, owl:onDataRange,
              owl:intersectionOf, owl:unionOf, owl:complementOf, owl:oneOf, owl:members, owl:distinctMembers,
              rdf:first, rdf:rest, owl:imports, owl:versionIRI, owl:sameAs, owl:differentFrom,
              owl:minCardinality, owl:maxCardinality, owl:cardinality,
              owl:minQualifiedCardinality, owl:maxQualifiedCardinality, owl:qualifiedCardinality,
              owl:propertyChainAxiom, owl:withRestrictions, owl:onDatatype,
              owl:annotatedSource, owl:annotatedProperty, owl:annotatedTarget,
              <http://www.w3.org/2001/XMLSchema#maxExclusive>,
              <http://www.w3.org/2001/XMLSchema#minExclusive>,
              <http://www.w3.org/2001/XMLSchema#maxInclusive>,
              <http://www.w3.org/2001/XMLSchema#minInclusive>,
              <http://www.w3.org/2001/XMLSchema#length>,
              <http://www.w3.org/2001/XMLSchema#minLength>,
              <http://www.w3.org/2001/XMLSchema#maxLength>,
              <http://www.w3.org/2001/XMLSchema#pattern>
            ))
          }
          FILTER(!isBlank(?annProp))
        }
        """;

    private static final String TYPED_AXIOM_COUNTS_QUERY = """
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        SELECT ?key (COUNT(*) AS ?count) WHERE {
          {
            VALUES ?pred { rdfs:domain rdfs:range }
            ?s ?pred ?o .
            {
              SELECT DISTINCT ?s ?kind WHERE {
                ?s a ?t .
                VALUES (?t ?kind) {
                  (owl:ObjectProperty            "ObjectProperty")
                  (owl:TransitiveProperty        "ObjectProperty")
                  (owl:SymmetricProperty         "ObjectProperty")
                  (owl:AsymmetricProperty        "ObjectProperty")
                  (owl:ReflexiveProperty         "ObjectProperty")
                  (owl:IrreflexiveProperty       "ObjectProperty")
                  (owl:InverseFunctionalProperty "ObjectProperty")
                  (owl:DatatypeProperty          "DatatypeProperty")
                  (owl:AnnotationProperty        "AnnotationProperty")
                }
              }
            }
            BIND(CONCAT(?kind, "|", STRAFTER(STR(?pred), "#")) AS ?key)
          }
          UNION
          {
            ?s owl:equivalentClass ?o .
            ?s a owl:Class .
            FILTER NOT EXISTS { ?s a rdfs:Datatype }
            BIND("equivalentClasses" AS ?key)
          }
          UNION
          {
            ?axiom a owl:AllDisjointClasses .
            BIND("allDisjointClasses" AS ?key)
          }
        }
        GROUP BY ?key
        """;

    private static final String CLASS_ASSERTION_COUNT_QUERY = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        SELECT (COUNT(*) AS ?count) WHERE {
          ?s rdf:type ?o .
          FILTER(isIRI(?o))
          FILTER(
            ?o = owl:Thing
            || !(
              STRSTARTS(STR(?o), "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
              || STRSTARTS(STR(?o), "http://www.w3.org/2000/01/rdf-schema#")
              || STRSTARTS(STR(?o), "http://www.w3.org/2002/07/owl#")
              || STRSTARTS(STR(?o), "http://www.w3.org/2003/11/swrl#")
              || STRSTARTS(STR(?o), "http://www.w3.org/2003/11/swrlb#")
            )
          )
        }
        """;

    private static final String ANNOTATION_ASSERTION_COUNT_QUERY = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        SELECT (COUNT(*) AS ?count) WHERE {
          ?s ?annProp ?o .
          FILTER(isLiteral(?o) || isIRI(?o))
          FILTER(
            EXISTS { ?annProp a owl:AnnotationProperty }
            || ?annProp IN (
              rdfs:label, rdfs:comment, rdfs:seeAlso, rdfs:isDefinedBy,
              owl:deprecated, owl:versionInfo, owl:backwardCompatibleWith, owl:incompatibleWith, owl:priorVersion
            )
          )
          FILTER NOT EXISTS { ?annProp a owl:ObjectProperty }
          FILTER NOT EXISTS { ?annProp a owl:DatatypeProperty }
          FILTER NOT EXISTS {
            ?s a ?excludedType .
            VALUES ?excludedType {
              owl:Ontology owl:Axiom owl:Annotation
              owl:AllDisjointClasses owl:AllDisjointProperties
              owl:AllDifferent owl:NegativePropertyAssertion
            }
          }
          FILTER(STRSTARTS(STR(?annProp), "http://www.w3.org/2003/11/swrl#") = false)
          FILTER(?annProp NOT IN (
            rdf:type, rdfs:subClassOf, rdfs:subPropertyOf, rdfs:domain, rdfs:range,
            owl:equivalentClass, owl:disjointWith, owl:equivalentProperty, owl:inverseOf,
            owl:onProperty, owl:someValuesFrom, owl:allValuesFrom, owl:hasValue, owl:onClass, owl:onDataRange,
            owl:intersectionOf, owl:unionOf, owl:complementOf, owl:oneOf, owl:members, owl:distinctMembers,
            rdf:first, rdf:rest, owl:imports, owl:versionIRI, owl:sameAs, owl:differentFrom,
            owl:minCardinality, owl:maxCardinality, owl:cardinality,
            owl:minQualifiedCardinality, owl:maxQualifiedCardinality, owl:qualifiedCardinality,
            owl:propertyChainAxiom, owl:withRestrictions, owl:onDatatype,
            owl:annotatedSource, owl:annotatedProperty, owl:annotatedTarget
          ))
        }
        """;

    private static final String DATATYPE_USAGE_COUNT_QUERY = """
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        SELECT (COUNT(DISTINCT ?dt) AS ?count) WHERE {
          {
            ?dt a rdfs:Datatype .
          }
          UNION
          {
            ?prop a owl:DatatypeProperty .
            ?prop rdfs:range ?dt .
          }
          UNION
          {
            ?s ?p ?o .
            FILTER(isLiteral(?o))
            BIND(DATATYPE(?o) AS ?dt)
          }
          FILTER(isIRI(?dt))
        }
        """;

    private final SparqlDatasetService datasetService;

    OntologyCountQueries(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    int getTripleCountWithPredicateType(String projectId, String type) {
        String query = PREFIXES + String.format("SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o . ?p a %s . }", type);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting triple count for predicate type " + type, e);
        }
        return 0;
    }

    Map<String, Integer> getTypedAxiomCounts(String projectId) {
        Map<String, Integer> counts = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, TYPED_AXIOM_COUNTS_QUERY);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("key") && sol.hasBinding("count")) {
                    counts.put(sol.getValue("key").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            log.error("Error getting typed axiom counts for project {}", projectId, e);
        }
        return counts;
    }

    int getCount(String projectId, String type) {
        String query = PREFIXES + String.format("SELECT (COUNT(DISTINCT ?s) AS ?count) WHERE { ?s a %s . }", type);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting count for type " + type, e);
        }
        return 0;
    }

    int getAnnotationPropertyUsageCount(String projectId) {
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, ANNOTATION_PROPERTY_COUNT_QUERY);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting annotation property usage count for project {}", projectId, e);
        }
        return 0;
    }

    int getDatatypeUsageCount(String projectId) {
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, DATATYPE_USAGE_COUNT_QUERY);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting datatype usage count for project {}", projectId, e);
        }
        return 0;
    }

    int getGCICount(String projectId, String ontologyIri) {
        if (ontologyIri == null) return 0;
        String formattedOntologyIri = OntologyMetadataService.formatResource(ontologyIri);

        String query = PREFIXES + String.format("""
            SELECT (COUNT(?gci) AS ?count) WHERE {
              {
                %s <http://ontocode.org/resource/gci> ?gci .
              }
              UNION
              {
                ?sub rdfs:subClassOf ?super .
                FILTER(isBlank(?sub))
                BIND(?sub AS ?gci)
              }
            }
            """, formattedOntologyIri);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting GCI count", e);
        }
        return 0;
    }

    int getClassAssertionCount(String projectId) {
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, CLASS_ASSERTION_COUNT_QUERY);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting class assertion count for project {}", projectId, e);
        }
        return 0;
    }

    int getAnnotationAssertionCount(String projectId) {
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, ANNOTATION_ASSERTION_COUNT_QUERY);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting annotation assertion count for project {}", projectId, e);
        }
        return 0;
    }
}
