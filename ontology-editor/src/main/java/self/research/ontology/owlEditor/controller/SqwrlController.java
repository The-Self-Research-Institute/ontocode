package self.research.ontology.owlEditor.controller;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.service.Tdb2DatasetService;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/sqwrl/{projectId}")
@CrossOrigin(origins = "*")
public class SqwrlController {

    private final Tdb2DatasetService datasetService;

    public SqwrlController(Tdb2DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping("/query")
    public Mono<ResponseEntity<Map<String, Object>>> executeSqwrlQuery(
            @PathVariable String projectId,
            @RequestBody Map<String, String> body) {

        String sqwrlQuery = body.getOrDefault("query", "");

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

        String select = columns.length == 0
                ? "*"
                : String.join(" ", Arrays.stream(columns).map(c -> "?" + c).toList());

        String sparqlQuery = """
            SELECT %s WHERE { ?s ?p ?o . } LIMIT 50
            """.formatted(select);

        return Mono.fromCallable(() -> execute(projectId, sparqlQuery))
                .map(result -> ResponseEntity.ok(Map.of(
                        "columns", result.columns,
                        "rows", result.rows
                )));
    }

    private ResultSetPayload execute(String projectId, String sparql) {
        ResultSet rs = datasetService.execSelect(projectId, sparql);
        List<String> columns = rs.getResultVars();
        List<Map<String, Object>> rows = new ArrayList<>();

        while (rs.hasNext()) {
            QuerySolution solution = rs.next();
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : columns) {
                RDFNode node = solution.get(column);
                row.put(column, node == null ? "" : formatValue(node));
            }
            rows.add(row);
        }
        return new ResultSetPayload(columns, rows);
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

    private record ResultSetPayload(List<String> columns, List<Map<String, Object>> rows) {}
}