package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.GraphDBDatasetService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sparql")
@CrossOrigin
public class SparqlQueryController {

    private final GraphDBDatasetService datasetService;

    public SparqlQueryController(GraphDBDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping("/query/{projectId}")
    public ResponseEntity<?> query(@PathVariable String projectId,
                                   @RequestBody SparqlRequest request) {
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
        return ResponseEntity.ok(Map.of(
                "head", Map.of("vars", vars),
                "results", rows));
    }

    @PostMapping("/update/{projectId}")
    public ResponseEntity<?> update(@PathVariable String projectId,
                                    @RequestBody SparqlRequest request) {
        datasetService.execUpdate(projectId, request.query());
        return ResponseEntity.ok(Map.of("success", true));
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
}
