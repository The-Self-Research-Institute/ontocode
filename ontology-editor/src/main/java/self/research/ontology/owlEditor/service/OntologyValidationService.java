package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
public class OntologyValidationService {
    
    private final OntologySparqlService query;
    
    public OntologyValidationService(OntologySparqlService query) {
        this.query = query;
    }
    
    public Mono<Map<String,Object>> validateOntology(String projectId) {
        return Mono.zip(
            checkOrphanClasses(projectId),
            checkUnusedProperties(projectId),
            checkMissingLabels(projectId),
            checkCircularDependencies(projectId)
        ).map(tuple -> {
            Map<String,Object> result = new HashMap<>();
            result.put("orphanClasses", tuple.getT1());
            result.put("unusedProperties", tuple.getT2());
            result.put("missingLabels", tuple.getT3());
            result.put("circularDependencies", tuple.getT4());
            
            boolean isValid = ((List<?>)tuple.getT1()).isEmpty() && 
                             ((List<?>)tuple.getT2()).isEmpty() &&
                             ((List<?>)tuple.getT4()).isEmpty();
            result.put("isValid", isValid);
            
            return result;
        });
    }
    
    private Mono<List<String>> checkOrphanClasses(String projectId) {
        String g = query.graph(projectId);
        String q = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?class WHERE {
              GRAPH <%s> {
                ?class a owl:Class .
                FILTER NOT EXISTS { ?class rdfs:subClassOf ?parent }
                FILTER NOT EXISTS { ?child rdfs:subClassOf ?class }
                FILTER NOT EXISTS { ?ind a ?class }
                FILTER(?class != owl:Thing)
              }
            }
        """.formatted(g);
        
        return query.executeSparqlQuery(q).map(json -> {
            List<String> orphans = new ArrayList<>();
            json.path("results").path("bindings")
                .forEach(b -> orphans.add(query.val(b, "class")));
            return orphans;
        });
    }
    
    private Mono<List<String>> checkUnusedProperties(String projectId) {
        String g = query.graph(projectId);
        String q = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            SELECT ?prop WHERE {
              GRAPH <%s> {
                { ?prop a owl:ObjectProperty } UNION { ?prop a owl:DatatypeProperty }
                FILTER NOT EXISTS {
                  ?s ?prop ?o .
                  FILTER(?s != ?prop)
                }
              }
            }
        """.formatted(g);
        
        return query.executeSparqlQuery(q).map(json -> {
            List<String> unused = new ArrayList<>();
            json.path("results").path("bindings")
                .forEach(b -> unused.add(query.val(b, "prop")));
            return unused;
        });
    }
    
    private Mono<List<String>> checkMissingLabels(String projectId) {
        String g = query.graph(projectId);
        String q = """
            PREFIX owl:<http://www.w3.org/2002/07/owl#>
            PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?entity WHERE {
              GRAPH <%s> {
                { ?entity a owl:Class } UNION 
                { ?entity a owl:ObjectProperty } UNION 
                { ?entity a owl:DatatypeProperty } UNION
                { ?entity a owl:NamedIndividual }
                FILTER NOT EXISTS { ?entity rdfs:label ?label }
              }
            } LIMIT 100
        """.formatted(g);
        
        return query.executeSparqlQuery(q).map(json -> {
            List<String> missing = new ArrayList<>();
            json.path("results").path("bindings")
                .forEach(b -> missing.add(query.val(b, "entity")));
            return missing;
        });
    }
    
    private Mono<List<String>> checkCircularDependencies(String projectId) {
        // Simplified check - in production, use proper transitive closure queries
        // This is a placeholder for now
        return Mono.just(List.of());
    }
}