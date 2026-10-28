package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlGraphSerializer;
import self.research.ontology.owlEditor.service.SparqlQueryContext;
import self.research.ontology.owlEditor.service.SparqlQueryRejectedException;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;
import self.research.ontology.owlEditor.service.infer.InconsistentOntologyException;
import self.research.ontology.owlEditor.service.infer.InferenceException;
import self.research.ontology.owlEditor.service.infer.InferenceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class InferredQueryHandler {

    private final SparqlDatasetService datasetService;
    private final InferenceService inferenceService;

    @Value("${ontocode.sparql.graph-result-cap:100000}")
    private int graphResultCap = 100000;

    @Autowired
    public InferredQueryHandler(SparqlDatasetService datasetService, InferenceService inferenceService) {
        this.datasetService = datasetService;
        this.inferenceService = inferenceService;
    }

    public ResponseEntity<?> handle(String projectId, String query, String format, boolean refresh, String accept) {
        long started = System.currentTimeMillis();
        try {
            RDFFormat rdfFormat = format != null && !format.isBlank()
                    ? SparqlGraphSerializer.formatByName(format) : SparqlGraphSerializer.formatByAccept(accept);
            SparqlQueryScope scope = datasetService.resolveUserQueryScope(projectId);
            String userId = SparqlQueryContext.getUserId();
            if (scope.draft() && (userId == null || userId.isBlank())) {
                throw new SparqlQueryRejectedException("A signed-in user is required to query a draft.");
            }
            InferenceService.Answer answer = inferenceService.query(scope, userId, query, refresh, graphResultCap);
            return shape(answer, rdfFormat, started);
        } catch (InferenceException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("needsReasoning", true);
            body.put("reason", e.reason());
            body.put("message", e.getMessage());
            if (e.computedAt() != null) {
                body.put("computedAt", e.computedAt().toString());
            }
            return ResponseEntity.status(409).body(body);
        } catch (InconsistentOntologyException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "INCONSISTENT");
            body.put("message", e.getMessage());
            body.put("explanation", e.explanation());
            return ResponseEntity.status(409).body(body);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    private static ResponseEntity<?> shape(InferenceService.Answer answer, RDFFormat format, long started) {
        SparqlUserQueryResult result = answer.result();
        Map<String, Object> body = new LinkedHashMap<>();
        if (result instanceof SparqlUserQueryResult.Ask ask) {
            body.put("type", "boolean");
            body.put("boolean", ask.value());
            body.put("executionTime", System.currentTimeMillis() - started);
            body.put("inferred", answer.inferred());
            return ResponseEntity.ok(body);
        }
        if (result instanceof SparqlUserQueryResult.Graph graph) {
            body.put("type", "graph");
            body.put("format", SparqlGraphSerializer.formatName(format));
            body.put("content", SparqlGraphSerializer.serialize(graph.statements(), graph.namespaces(), format));
            body.put("tripleCount", (long) graph.statements().size());
            body.put("truncated", graph.truncated());
            body.put("executionTime", System.currentTimeMillis() - started);
            body.put("inferred", answer.inferred());
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (graph.truncated()) {
                ok.header(SparqlQueryController.TRUNCATED_HEADER, "true");
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
                org.eclipse.rdf4j.model.Value value = sol.hasBinding(var) ? sol.getValue(var) : null;
                row.put(var, value == null ? null : (value.isBNode() ? value.toString() : value.stringValue()));
                if (value != null) {
                    binding.put(var, termOf(value));
                }
            }
            rows.add(row);
            bindings.add(binding);
        }
        body.put("type", "select");
        body.put("head", Map.of("vars", vars));
        body.put("vars", vars);
        body.put("results", rows);
        body.put("bindings", bindings);
        body.put("executionTime", System.currentTimeMillis() - started);
        body.put("inferred", answer.inferred());
        return ResponseEntity.ok(body);
    }

    private static Map<String, String> termOf(org.eclipse.rdf4j.model.Value node) {
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
}
