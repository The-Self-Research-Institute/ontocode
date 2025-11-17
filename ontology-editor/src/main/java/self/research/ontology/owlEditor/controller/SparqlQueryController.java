package self.research.ontology.owlEditor.controller;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.Tdb2DatasetService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sparql")
@CrossOrigin
public class SparqlQueryController {

    private final Tdb2DatasetService datasetService;

    public SparqlQueryController(Tdb2DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping("/query/{projectId}")
    public ResponseEntity<?> query(@PathVariable String projectId,
                                   @RequestBody SparqlRequest request) {
        ResultSet rs = datasetService.execSelect(projectId, request.query());
        List<String> vars = rs.getResultVars();
        List<Map<String, String>> rows = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            Map<String, String> row = new LinkedHashMap<>();
            for (String var : vars) {
                row.put(var, toValue(sol.get(var)));
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

    private String toValue(RDFNode node) {
        if (node == null) {
            return null;
        }
        if (node.isLiteral()) {
            return node.asLiteral().getString();
        }
        if (node.isResource()) {
            return node.asResource().getURI();
        }
        return node.toString();
    }

    public record SparqlRequest(String query) {}
}
