package self.research.ontology.owlEditor.service;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotationPropertyCountQueryTest {

    private static final String PREVIOUS_QUERY = """
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

    private static final String DATA = """
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix ex: <http://ex.org/> .
        @prefix swrl: <http://www.w3.org/2003/11/swrl#> .
        ex:declaredOnly a owl:AnnotationProperty .
        ex:declaredAndUsed a owl:AnnotationProperty .
        ex:A a owl:Class ; rdfs:label "A" ; rdfs:comment "a" ; ex:declaredAndUsed "x" ; ex:undeclaredLiteral "y" .
        ex:B a owl:Class ; rdfs:subClassOf ex:A ; ex:undeclaredIri ex:A ; ex:onlyBlank [ a ex:Thing ] .
        ex:op a owl:ObjectProperty . ex:dp a owl:DatatypeProperty .
        ex:i ex:op ex:j ; ex:dp "1" .
        ex:A ex:B "class used as predicate" .
        ex:rule swrl:body ex:A .
        ex:A owl:deprecated "true" .
        """;

    private static int count(Model model, String query) {
        try (QueryExecution execution = QueryExecution.model(model).query(query).build()) {
            return execution.execSelect().next().getLiteral("count").getInt();
        }
    }

    @Test
    void theRewrittenQueryCountsExactlyWhatThePreviousQueryCounted() {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(DATA), null, "TTL");

        int previous = count(model, PREVIOUS_QUERY);
        int rewritten = count(model, OntologyCountQueries.ANNOTATION_PROPERTY_COUNT_QUERY);

        assertEquals(previous, rewritten);
        assertEquals(7, rewritten);
    }

    @Test
    void anEmptyGraphCountsZero() {
        Model model = ModelFactory.createDefaultModel();

        assertEquals(0, count(model, OntologyCountQueries.ANNOTATION_PROPERTY_COUNT_QUERY));
        assertEquals(count(model, PREVIOUS_QUERY), count(model, OntologyCountQueries.ANNOTATION_PROPERTY_COUNT_QUERY));
    }
}
