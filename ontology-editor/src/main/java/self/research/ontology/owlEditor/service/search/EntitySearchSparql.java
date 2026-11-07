package self.research.ontology.owlEditor.service.search;

import self.research.ontology.owlEditor.service.search.EntitySearch.EntityType;
import self.research.ontology.owlEditor.service.search.EntitySearch.AnnotationValue;
import self.research.ontology.owlEditor.service.search.EntitySearch.SearchRequest;
import self.research.ontology.owlEditor.service.search.EntitySearch.EntityHit;
import self.research.ontology.owlEditor.service.search.EntitySearch.BuiltInEntities;
import self.research.ontology.owlEditor.service.search.EntitySearch.SparqlText;
import self.research.ontology.owlEditor.service.search.EntitySearch.EntityMatching;
import self.research.ontology.owlEditor.service.search.EntitySearch.EntitySearchResult;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.service.ClassSearchResult;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.util.SparqlSafety;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Service
public class EntitySearchSparql {

    private static final String PREFIXES = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            """;

    private static final String ANNOTATION_PROPERTY_FILTER = """
            FILTER((isLiteral(?v) && NOT EXISTS { ?p a owl:DatatypeProperty })
                   || ?p IN (rdfs:seeAlso, rdfs:isDefinedBy)
                   || EXISTS { ?p a owl:AnnotationProperty })
            """;

    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final int MAX_CANDIDATES = 5001;

    private final SparqlDatasetService datasetService;

    public EntitySearchSparql(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public Map<String, Object> search(String projectId, SearchRequest request) {
        EntitySearchResult.Builder out = EntitySearchResult.builder(request.limitPerType());
        if (request.matcher().isEmpty()) {
            return out.build().toResponse(List.of());
        }
        for (EntityType type : request.types()) {
            List<String> iris = findCandidates(projectId, type, request);
            if (iris.isEmpty()) {
                continue;
            }
            Map<String, List<AnnotationValue>> values = hydrate(projectId, iris);
            Map<String, String> propertyLabels = propertyLabels(values);
            UnaryOperator<String> propertyLabel = p -> propertyLabels.getOrDefault(p, EntityMatching.localName(p));
            List<EntityMatching.Candidate> candidates = new ArrayList<>();
            for (String iri : iris) {
                EntityMatching.Evaluation evaluation = EntityMatching.evaluate(
                        iri, values.getOrDefault(iri, List.of()), request, propertyLabel);
                if (evaluation.matches().isEmpty() || (request.hideDeprecated() && evaluation.deprecated())) {
                    continue;
                }
                candidates.add(new EntityMatching.Candidate(iri, evaluation.label(), evaluation.matches()));
            }
            EntityMatching.addSorted(out, type, candidates);
        }
        EntitySearchResult result = out.build();
        List<String> classHits = result.groups().get(EntityType.CLASS).stream().map(EntityHit::id).toList();
        return result.toResponse(classAncestors(projectId, classHits));
    }

    private List<String> findCandidates(String projectId, EntityType type, SearchRequest request) {
        String query = PREFIXES + candidateQuery(type, request);
        List<String> iris = new ArrayList<>();
        try (TupleQueryResult rs = datasetService.execSelect(projectId, query, false, QUERY_TIMEOUT_SECONDS)) {
            while (rs.hasNext()) {
                Value e = rs.next().getValue("e");
                if (e != null) {
                    iris.add(e.stringValue());
                }
            }
        }
        return iris;
    }

    static String candidateQuery(EntityType type, SearchRequest request) {
        String entityMatch = matchExpression("STR(?e)", request.matcher());
        if (request.matcher().isRegex()) {
            entityMatch += " || " + matchExpression("REPLACE(STR(?e), \"^.*[/#]\", \"\")", request.matcher());
        }
        String valueMatch = matchExpression("STR(?v)", request.matcher());
        String valueBinding = request.annotations()
                ? "?e ?p ?v . FILTER(isLiteral(?v) || isIRI(?v)) " + ANNOTATION_PROPERTY_FILTER
                : "?e rdfs:label ?v .";
        StringBuilder q = new StringBuilder("SELECT DISTINCT ?e WHERE {\n");
        q.append(typePattern(type)).append('\n');
        q.append("  FILTER(isIRI(?e))\n");
        if (type == EntityType.CLASS) {
            q.append("  FILTER(!STRSTARTS(STR(?e), \"http://www.w3.org/2001/XMLSchema#\"))\n");
            q.append("  FILTER(!STRSTARTS(STR(?e), \"http://www.w3.org/2000/01/rdf-schema#\"))\n");
            q.append("  FILTER(!STRSTARTS(STR(?e), \"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"))\n");
            q.append("  FILTER(!STRSTARTS(STR(?e), \"http://org.semanticweb.owlapi/error#\"))\n");
            q.append("  FILTER(?e != owl:Nothing)\n");
        }
        if (request.hideBuiltins() && !BuiltInEntities.of(type).isEmpty()) {
            String list = BuiltInEntities.of(type).stream().map(i -> "<" + i + ">").collect(Collectors.joining(", "));
            q.append("  FILTER(?e NOT IN (").append(list).append("))\n");
        }
        if (request.hideDeprecated()) {
            q.append("  FILTER NOT EXISTS { ?e owl:deprecated ?dep . FILTER(LCASE(STR(?dep)) = \"true\") }\n");
        }
        q.append("  OPTIONAL { ").append(valueBinding).append(" FILTER(").append(valueMatch).append(") }\n");
        q.append("  FILTER(").append(entityMatch).append(" || BOUND(?v))\n");
        q.append("}\nORDER BY ?e\nLIMIT ").append(candidateLimit(request));
        return q.toString();
    }

    private static int candidateLimit(SearchRequest request) {
        boolean javaNarrows = request.matcher().isWholeWords() || !request.searchIris();
        return javaNarrows ? MAX_CANDIDATES : request.limitPerType() + 1;
    }

    static String typePattern(EntityType type) {
        return switch (type) {
            case CLASS -> "  { ?e a owl:Class } UNION { ?e rdfs:subClassOf ?superClass }";
            case OBJECT_PROPERTY -> "  ?e a owl:ObjectProperty .";
            case DATA_PROPERTY -> "  ?e a owl:DatatypeProperty .";
            case ANNOTATION_PROPERTY -> "  ?e a owl:AnnotationProperty .";
            case DATATYPE -> "  ?e a rdfs:Datatype .";
            case INDIVIDUAL -> "  ?e a owl:NamedIndividual .";
        };
    }

    static String matchExpression(String target, SearchMatcher matcher) {
        String flags = matcher.isCaseSensitive() ? "" : ", \"i\"";
        if (matcher.prefilterRegex() != null) {
            return "REGEX(" + target + ", \"" + SparqlText.escape(matcher.prefilterRegex()) + "\"" + flags + ")";
        }
        if (matcher.isCaseSensitive()) {
            return "CONTAINS(" + target + ", \"" + SparqlText.escape(matcher.raw()) + "\")";
        }
        return "CONTAINS(LCASE(" + target + "), \"" + SparqlText.escape(matcher.lowerNeedle()) + "\")";
    }

    private Map<String, List<AnnotationValue>> hydrate(String projectId, List<String> iris) {
        String values = iris.stream()
                .map(iri -> "<" + SparqlSafety.safeIri(iri) + ">")
                .collect(Collectors.joining(" "));
        String query = PREFIXES + """
                SELECT ?e ?p ?v WHERE {
                  VALUES ?e { %s }
                  ?e ?p ?v .
                  FILTER(isLiteral(?v) || isIRI(?v))
                  %s
                }
                """.formatted(values, ANNOTATION_PROPERTY_FILTER);
        Map<String, List<AnnotationValue>> byEntity = new LinkedHashMap<>();
        try (TupleQueryResult rs = datasetService.execSelect(projectId, query, false, QUERY_TIMEOUT_SECONDS)) {
            while (rs.hasNext()) {
                BindingSet row = rs.next();
                Value e = row.getValue("e");
                Value p = row.getValue("p");
                Value v = row.getValue("v");
                if (e == null || p == null || v == null) {
                    continue;
                }
                byEntity.computeIfAbsent(e.stringValue(), k -> new ArrayList<>())
                        .add(new AnnotationValue(p.stringValue(), v.stringValue(), v.isLiteral()));
            }
        }
        return byEntity;
    }

    private Map<String, String> propertyLabels(Map<String, List<AnnotationValue>> values) {
        Set<String> properties = new LinkedHashSet<>();
        values.values().forEach(list -> list.forEach(v -> properties.add(v.property())));
        Map<String, String> labels = new HashMap<>();
        properties.forEach(p -> labels.put(p, EntityMatching.localName(p)));
        return labels;
    }

    private List<Map<String, Object>> classAncestors(String projectId, List<String> classHits) {
        if (classHits.isEmpty()) {
            return List.of();
        }
        String values = classHits.stream()
                .map(iri -> "<" + SparqlSafety.safeIri(iri) + ">")
                .collect(Collectors.joining(" "));
        String query = PREFIXES + """
                SELECT DISTINCT ?x ?p WHERE {
                  VALUES ?c { %s }
                  ?c rdfs:subClassOf* ?x .
                  ?x rdfs:subClassOf ?p .
                  FILTER(isIRI(?x) && isIRI(?p) && ?x != ?p)
                }
                """.formatted(values);
        Map<String, Set<String>> parentsOf = new HashMap<>();
        try (TupleQueryResult rs = datasetService.execSelect(projectId, query, false, QUERY_TIMEOUT_SECONDS)) {
            while (rs.hasNext()) {
                BindingSet row = rs.next();
                Value x = row.getValue("x");
                Value p = row.getValue("p");
                if (x != null && p != null) {
                    parentsOf.computeIfAbsent(x.stringValue(), k -> new LinkedHashSet<>()).add(p.stringValue());
                }
            }
        }
        return ClassSearchResult.ancestorsOf(classHits, parentsOf);
    }
}
