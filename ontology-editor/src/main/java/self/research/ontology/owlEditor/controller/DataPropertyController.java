package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.OntologyQueryService;

import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class DataPropertyController {

    private final OntologyQueryService queryService;

    public DataPropertyController(OntologyQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/data-properties/{projectId}")
    public ResponseEntity<?> dataProperties(@PathVariable String projectId,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.properties(projectId, "DatatypeProperty", limit, offset)));
    }
}
