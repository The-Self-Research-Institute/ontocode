package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(origins = "*")
public class ReasonerController {

    @PostMapping("/{projectId}/reasoner/run")
    public ResponseEntity<Map<String,Object>> runReasoner(@PathVariable String projectId) {
        // NO-OP for forward-chaining stores like GraphDB
        // Integrate external reasoners (HermiT, Pellet, etc.) here if needed
        return ResponseEntity.accepted()
                .body(Map.of(
                    "success", true, 
                    "message", "Reasoner trigger accepted (no-op for configured stores)."
                ));
    }
}