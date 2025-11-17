package self.research.ontology.owlEditor.service;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OntologyValidationService {

    private final Tdb2DatasetService datasetService;

    public OntologyValidationService(Tdb2DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public Mono<Map<String, Object>> validateOntology(String projectId) {
        return Mono.zip(
                checkOrphanClasses(projectId),
                checkUnusedProperties(projectId),
                checkMissingLabels(projectId),
                checkCircularDependencies(projectId)
        ).map(tuple -> {
            Map<String, Object> result = new HashMap<>();
            List<String> orphans = tuple.getT1();
            List<String> unused = tuple.getT2();
            List<String> missing = tuple.getT3();
            List<String> circular = tuple.getT4();

            result.put("orphanClasses", orphans);
            result.put("unusedProperties", unused);
            result.put("missingLabels", missing);
            result.put("circularDependencies", circular);
            result.put("isValid", orphans.isEmpty() && unused.isEmpty() && circular.isEmpty());
            return result;
        });
    }

    private Mono<List<String>> checkOrphanClasses(String projectId) {
        String sparql = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?class WHERE {
                ?class a owl:Class .
                FILTER NOT EXISTS { ?class rdfs:subClassOf ?parent }
                FILTER NOT EXISTS { ?child rdfs:subClassOf ?class }
                FILTER NOT EXISTS { ?ind a ?class }
                FILTER(?class != owl:Thing)
            }
            """;
        return runSelect(projectId, sparql, "class");
    }

    private Mono<List<String>> checkUnusedProperties(String projectId) {
        String sparql = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            SELECT ?prop WHERE {
                { ?prop a owl:ObjectProperty } UNION { ?prop a owl:DatatypeProperty }
                FILTER NOT EXISTS { ?s ?prop ?o . FILTER(?s != ?prop) }
            }
            """;
        return runSelect(projectId, sparql, "prop");
    }

    private Mono<List<String>> checkMissingLabels(String projectId) {
        String sparql = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?entity WHERE {
                { ?entity a owl:Class } UNION
                { ?entity a owl:ObjectProperty } UNION
                { ?entity a owl:DatatypeProperty } UNION
                { ?entity a owl:NamedIndividual }
                FILTER NOT EXISTS { ?entity rdfs:label ?label }
            } LIMIT 100
            """;
        return runSelect(projectId, sparql, "entity");
    }

    private Mono<List<String>> checkCircularDependencies(String projectId) {
        return Mono.just(List.of());
    }

    private Mono<List<String>> runSelect(String projectId, String query, String varName) {
        return Mono.fromCallable(() -> {
            ResultSet rs = datasetService.execSelect(projectId, query);
            List<String> values = new ArrayList<>();
            while (rs.hasNext()) {
                QuerySolution solution = rs.next();
                RDFNode node = solution.get(varName);
                if (node != null) {
                    values.add(formatValue(node));
                }
            }
            return values;
        });
    }

    private String formatValue(RDFNode node) {
        if (node.isResource()) {
            return node.asResource().getURI();
        }
        if (node.isLiteral()) {
            return node.asLiteral().getString();
        }
        return node.toString();
    }
}

