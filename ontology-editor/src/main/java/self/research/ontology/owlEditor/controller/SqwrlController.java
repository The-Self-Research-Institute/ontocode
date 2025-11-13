package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.service.OntologySparqlService;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/sqwrl/{projectId}")
@CrossOrigin(origins = "*")
public class SqwrlController {
    
    private final OntologySparqlService sparql;

    public SqwrlController(OntologySparqlService sparql) { 
        this.sparql = sparql; 
    }

    @PostMapping("/query")
    public Mono<ResponseEntity<Map<String,Object>>> executeSqwrlQuery(
            @PathVariable String projectId,
            @RequestBody Map<String,String> body) {

        String sqwrlQuery = body.getOrDefault("query", "");
        
        // Very simple SQWRL extraction: sqwrl:select(?x, ?y)
        Pattern pattern = Pattern.compile("sqwrl:select\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sqwrlQuery);
        
        if (!matcher.find()) {
            return Mono.just(ResponseEntity.ok(Map.of(
                "columns", List.of(),
                "rows", List.of()
            )));
        }
        
        String[] columns = Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .map(s -> s.replace("?", ""))
                .toArray(String[]::new);

        // Naive mapping: treat atoms like X(?x) as rdf:type patterns
        // In production, integrate a real SQWRL engine
        String where = " ?s ?p ?o . ";
        String select = String.join(" ", Arrays.stream(columns).map(c -> "?" + c).toList());
        String sparqlQuery = """
            SELECT %s WHERE { %s } LIMIT 50
        """.formatted(select.isBlank() ? "*" : select, where);

        return sparql.executeSparqlQuery(sparqlQuery).map(json -> {
            // Convert standard SPARQL JSON to SQWRL result shape
            var head = json.path("head").path("vars");
            List<String> outColumns = new ArrayList<>();
            head.forEach(node -> outColumns.add(node.asText()));
            
            List<Map<String,Object>> rows = new ArrayList<>();
            json.path("results").path("bindings").forEach(binding -> {
                Map<String,Object> row = new LinkedHashMap<>();
                outColumns.forEach(col -> row.put(col, binding.path(col).path("value").asText("")));
                rows.add(row);
            });
            
            return ResponseEntity.ok(Map.of("columns", outColumns, "rows", rows));
        });
    }
}