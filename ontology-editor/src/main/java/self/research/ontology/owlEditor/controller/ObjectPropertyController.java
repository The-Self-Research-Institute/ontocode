package self.research.ontology.owlEditor.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.OntologyQueryService;
import self.research.ontology.owlEditor.service.OwlApiFastPathSupport;
import self.research.ontology.owlEditor.service.owlapi.OwlApiPropertyQueryService;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class ObjectPropertyController {

    private final OntologyQueryService queryService;
    private final OwlApiFastPathSupport fastPath;

    @Autowired(required = false) @Nullable
    private OwlApiPropertyQueryService owlApiPropertyQueryService;

    public ObjectPropertyController(OntologyQueryService queryService, OwlApiFastPathSupport fastPath) {
        this.queryService = queryService;
        this.fastPath = fastPath;
    }

    @GetMapping("/object-properties/{projectId}")
    public ResponseEntity<?> objectProperties(@PathVariable String projectId,
                                              @RequestParam(defaultValue = "100") int limit,
                                              @RequestParam(defaultValue = "0") int offset) {

        Optional<ResponseEntity<?>> owl = fastPath.owlApiOnlyOrWarming(projectId, () -> {
            if (owlApiPropertyQueryService == null) {
                throw new IllegalStateException("OWLAPI property service unavailable");
            }
            return ResponseEntity.ok(Map.of("success", true, "data",
                    owlApiPropertyQueryService.list(projectId, "object", limit, offset)));
        });
        if (owl.isPresent()) {
            return owl.get();
        }
        return ResponseEntity.ok(Map.of("success", true, "data",
                queryService.properties(projectId, "object", limit, offset)));
    }
}
