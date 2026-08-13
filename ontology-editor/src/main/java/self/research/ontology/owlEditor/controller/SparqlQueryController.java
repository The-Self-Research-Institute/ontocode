package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.SparqlQueryEntity;
import self.research.ontology.owlEditor.repository.SparqlQueryRepository;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.util.TripleStoreErrors;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sparql")
@CrossOrigin
public class SparqlQueryController {

    private final SparqlDatasetService datasetService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SparqlQueryRepository queryRepository;
    private final OntologyMutationService mutationService;

    @org.springframework.beans.factory.annotation.Value("${ontocode.desktop.owlapi-first:false}")
    private boolean desktopOwlApiFirst;

    private ResponseEntity<?> sparqlFailure(Exception e) {
        if (desktopOwlApiFirst && TripleStoreErrors.isConnectionRefused(e)) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", TripleStoreErrors.STORE_STARTING_MESSAGE,
                    "retryable", true));
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    public SparqlQueryController(SparqlDatasetService datasetService,
                                SimpMessagingTemplate messagingTemplate,
                                SparqlQueryRepository queryRepository,
                                @org.springframework.context.annotation.Lazy OntologyMutationService mutationService) {
        this.datasetService = datasetService;
        this.messagingTemplate = messagingTemplate;
        this.queryRepository = queryRepository;
        this.mutationService = mutationService;
    }

    @GetMapping("/{projectId}/queries")
    public ResponseEntity<List<SparqlQueryEntity>> getSavedQueries(@PathVariable String projectId) {
        List<SparqlQueryEntity> queries = queryRepository.findByProjectId(projectId);
        return ResponseEntity.ok(queries);
    }

    @PostMapping("/{projectId}/queries")
    public ResponseEntity<SparqlQueryEntity> createQuery(
            @PathVariable String projectId,
            @RequestBody SavedQueryRequest request) {
        SparqlQueryEntity entity = new SparqlQueryEntity();
        entity.setProjectId(projectId);
        entity.setName(request.name());
        entity.setQueryText(request.queryText());
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());

        SparqlQueryEntity saved = queryRepository.save(entity);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{projectId}/queries/{queryId}")
    public ResponseEntity<SparqlQueryEntity> updateQuery(
            @PathVariable String projectId,
            @PathVariable String queryId,
            @RequestBody SavedQueryRequest request) {
        return queryRepository.findById(queryId)
                .map(entity -> {
                    entity.setName(request.name());
                    entity.setQueryText(request.queryText());
                    entity.setUpdatedAt(new Date());
                    SparqlQueryEntity updated = queryRepository.save(entity);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{projectId}/queries/{queryId}")
    public ResponseEntity<?> deleteQuery(
            @PathVariable String projectId,
            @PathVariable String queryId) {
        if (queryRepository.existsById(queryId)) {
            queryRepository.deleteById(queryId);
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.notFound().build();
    }

    record SparqlHead(List<String> vars) {}
    record SparqlQueryResponse(SparqlHead head, List<Map<String, String>> results, long executionTime) {}

    @PostMapping("/query/{projectId}")
    public ResponseEntity<?> query(@PathVariable String projectId,
                                   @RequestBody SparqlRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query must not be empty"));
        }
        long startTime = System.currentTimeMillis();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, request.query());
            List<String> vars = rs.getBindingNames();
            List<Map<String, String>> rows = new ArrayList<>();
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                Map<String, String> row = new LinkedHashMap<>();
                for (String var : vars) {
                    row.put(var, toValue(sol.hasBinding(var) ? sol.getValue(var) : null));
                }
                rows.add(row);
            }
            long executionTime = System.currentTimeMillis() - startTime;
            return ResponseEntity.ok(new SparqlQueryResponse(new SparqlHead(vars), rows, executionTime));
        } catch (Exception e) {
            return sparqlFailure(e);
        }
    }

    @PostMapping("/update/{projectId}")
    public ResponseEntity<?> update(@PathVariable String projectId,
                                    @RequestBody SparqlRequest request,
                                    @RequestParam(required = false, defaultValue = "anonymous") String userId,
                                    @RequestParam(required = false, defaultValue = "Anonymous") String username,
                                    @RequestParam(required = false, defaultValue = "false") boolean draft) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query must not be empty"));
        }
        try {

        mutationService.applyRawUpdate(projectId, request.query(), draft, userId);

        if (draft) {
            return ResponseEntity.ok(Map.of("success", true, "draft", true));
        }

        Map<String, Object> sparqlUpdateNotification = Map.of(
            "type", "SPARQL_UPDATE",
            "projectId", projectId,
            "userId", userId,
            "username", username,
            "timestamp", System.currentTimeMillis(),
            "message", "SPARQL update executed - please refresh"
        );
        messagingTemplate.convertAndSend("/topic/ontology/" + projectId, sparqlUpdateNotification);
        return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return sparqlFailure(e);
        }
    }

    private String toValue(Value node) {
        if (node == null) {
            return null;
        }
        if (node.isLiteral()) {
            return node.stringValue();
        }
        if (node.isIRI()) {
            return node.stringValue();
        }
        return node.toString();
    }

    public record SparqlRequest(String query) {}

    public record SavedQueryRequest(String name, String queryText) {}
}
