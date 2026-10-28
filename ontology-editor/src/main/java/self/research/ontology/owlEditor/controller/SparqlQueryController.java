package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.query.BindingSet;
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
import org.eclipse.rdf4j.rio.RDFFormat;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlGraphSerializer;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.util.TripleStoreErrors;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sparql")
@CrossOrigin(exposedHeaders = SparqlQueryController.TRUNCATED_HEADER)
public class SparqlQueryController {

    private final SparqlDatasetService datasetService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SparqlQueryRepository queryRepository;
    private final OntologyMutationService mutationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InferredQueryHandler inferredQueryHandler;

    @org.springframework.beans.factory.annotation.Value("${ontocode.sparql.graph-result-cap:100000}")
    private int graphResultCap = 100000;

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
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record SparqlQueryResponse(String type, SparqlHead head, List<String> vars, List<Map<String, String>> results,
                               List<Map<String, Map<String, String>>> bindings, long executionTime, Boolean truncated) {}
    record SparqlBooleanResponse(String type, @com.fasterxml.jackson.annotation.JsonProperty("boolean") boolean value,
                                 long executionTime) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record SparqlGraphResponse(String type, String format, String content, long tripleCount, Long totalTriples,
                               boolean truncated, long executionTime) {}

    public static final String TRUNCATED_HEADER = "X-Ontocode-Truncated";

    @PostMapping("/query/{projectId}")
    public ResponseEntity<?> query(@PathVariable String projectId,
                                   @RequestBody SparqlRequest request,
                                   @org.springframework.web.bind.annotation.RequestHeader(value = "Accept", required = false) String accept) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query must not be empty"));
        }
        if (Boolean.TRUE.equals(request.includeInferred()) && inferredQueryHandler != null) {
            return inferredQueryHandler.handle(projectId, request.query(), request.format(),
                    Boolean.TRUE.equals(request.refresh()), accept);
        }
        long startTime = System.currentTimeMillis();
        try {
            RDFFormat format = request.format() != null && !request.format().isBlank()
                    ? SparqlGraphSerializer.formatByName(request.format())
                    : SparqlGraphSerializer.formatByAccept(accept);
            SparqlUserQueryResult result = datasetService.execUserQuery(projectId, request.query(), graphResultCap);
            if (result instanceof SparqlUserQueryResult.Ask ask) {
                return ResponseEntity.ok(new SparqlBooleanResponse("boolean", ask.value(),
                        System.currentTimeMillis() - startTime));
            }
            if (result instanceof SparqlUserQueryResult.Graph graph) {
                String content = SparqlGraphSerializer.serialize(graph.statements(), graph.namespaces(), format);
                SparqlGraphResponse body = new SparqlGraphResponse("graph", SparqlGraphSerializer.formatName(format),
                        content, graph.statements().size(), graph.truncated() ? null : (long) graph.statements().size(),
                        graph.truncated(), System.currentTimeMillis() - startTime);
                ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
                if (graph.truncated()) {
                    ok.header(TRUNCATED_HEADER, "true");
                }
                return ok.body(body);
            }
            SparqlUserQueryResult.Select select = (SparqlUserQueryResult.Select) result;
            List<String> vars = select.vars();
            List<Map<String, String>> rows = new ArrayList<>();
            List<Map<String, Map<String, String>>> bindings = new ArrayList<>();
            for (BindingSet sol : select.rows()) {
                Map<String, String> row = new LinkedHashMap<>();
                Map<String, Map<String, String>> binding = new LinkedHashMap<>();
                for (String var : vars) {
                    Value value = sol.hasBinding(var) ? sol.getValue(var) : null;
                    row.put(var, toValue(value));
                    if (value != null) {
                        binding.put(var, toBinding(value));
                    }
                }
                rows.add(row);
                bindings.add(binding);
            }
            long executionTime = System.currentTimeMillis() - startTime;
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (select.truncated()) {
                ok.header(TRUNCATED_HEADER, "true");
            }
            return ok.body(new SparqlQueryResponse("select", new SparqlHead(vars), vars, rows, bindings,
                    executionTime, select.truncated() ? Boolean.TRUE : null));
        } catch (self.research.ontology.owlEditor.service.BusyException busy) {
            throw busy;
        } catch (Exception e) {
            return sparqlFailure(e);
        }
    }

    @PostMapping("/update/{projectId}")
    public ResponseEntity<?> update(@PathVariable String projectId,
                                    @RequestBody SparqlRequest request,
                                    @RequestParam(required = false, defaultValue = "anonymous") String userId,
                                    @RequestParam(required = false, defaultValue = "Anonymous") String username,
                                    @RequestParam(required = false, defaultValue = "false") boolean draft,
                                    jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query must not be empty"));
        }
        try {
        final String actorId = self.research.ontology.owlEditor.service.RequestActor.forScope(httpRequest, userId, draft);
        final String actorName = "Anonymous".equals(username) ? actorId : username;

        mutationService.applyRawUpdateWithHistory(projectId, request.query(), draft, actorId, actorName);

        if (draft) {
            return ResponseEntity.ok(Map.of("success", true, "draft", true));
        }

        Map<String, Object> sparqlUpdateNotification = Map.of(
            "type", "SPARQL_UPDATE",
            "projectId", projectId,
            "userId", actorId,
            "username", actorName,
            "timestamp", System.currentTimeMillis(),
            "message", "SPARQL update executed - please refresh"
        );
        messagingTemplate.convertAndSend("/topic/ontology/" + projectId, sparqlUpdateNotification);
        return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return sparqlFailure(e);
        }
    }

    static Map<String, String> toBinding(Value node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isIRI()) {
            out.put("type", "uri");
            out.put("value", node.stringValue());
        } else if (node.isBNode()) {
            out.put("type", "bnode");
            out.put("value", ((org.eclipse.rdf4j.model.BNode) node).getID());
        } else {
            org.eclipse.rdf4j.model.Literal lit = (org.eclipse.rdf4j.model.Literal) node;
            out.put("type", "literal");
            out.put("value", lit.getLabel());
            if (lit.getLanguage().isPresent()) {
                out.put("xml:lang", lit.getLanguage().get());
            } else if (!org.eclipse.rdf4j.model.vocabulary.XSD.STRING.equals(lit.getDatatype())) {
                out.put("datatype", lit.getDatatype().stringValue());
            }
        }
        return out;
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

    public record SparqlRequest(String query, String format, Boolean includeInferred, Boolean refresh) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public SparqlRequest {
        }

        public SparqlRequest(String query, String format) {
            this(query, format, null, null);
        }

        public SparqlRequest(String query) {
            this(query, null);
        }
    }

    public record SavedQueryRequest(String name, String queryText) {}
}
