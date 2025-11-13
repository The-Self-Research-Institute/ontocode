package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.model.SparqlQueryEntity;
import self.research.ontology.owlEditor.service.SparqlQueryService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sparql/{projectId}")
@CrossOrigin(origins = "*")
public class SparqlQueryController {

    private static final Logger log = LoggerFactory.getLogger(SparqlQueryController.class);
    
    @Autowired
    private SparqlQueryService service;

    @GetMapping("/queries")
    public ResponseEntity<List<SparqlQueryEntity>> listQueries(@PathVariable String projectId) {
        return ResponseEntity.ok(service.list(projectId));
    }

    @PostMapping("/queries")
    public ResponseEntity<SparqlQueryEntity> createQuery(
            @PathVariable String projectId,
            @RequestBody Map<String,String> body) {
        String name = body.getOrDefault("name", "New Query");
        String queryText = body.getOrDefault("queryText", "");
        return ResponseEntity.ok(service.create(projectId, name, queryText));
    }

    @PutMapping("/queries/{id}")
    public ResponseEntity<SparqlQueryEntity> updateQuery(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody Map<String,String> body) {
        try {
            String name = body.getOrDefault("name", "Query");
            String queryText = body.getOrDefault("queryText", "");
            SparqlQueryEntity updated = service.update(id, name, queryText);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            log.error("Failed to update query: {}", id, e);
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/queries/{id}")
    public ResponseEntity<Void> deleteQuery(
            @PathVariable String projectId,
            @PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/execute")
    public Mono<ResponseEntity<JsonNode>> executeQuery(
            @PathVariable String projectId,
            @RequestBody Map<String,String> body) {
        String queryText = body.getOrDefault("queryText", "SELECT * WHERE { ?s ?p ?o } LIMIT 10");
        return service.execute(projectId, queryText)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.internalServerError().build());
    }
}