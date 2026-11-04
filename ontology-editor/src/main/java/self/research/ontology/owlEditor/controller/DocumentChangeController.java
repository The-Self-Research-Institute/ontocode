package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class DocumentChangeController {

    private final RollbackRequestSupport support;

    public DocumentChangeController(RollbackRequestSupport support) {
        this.support = support;
    }

    @PostMapping("/{projectId}/changes/document")
    public ResponseEntity<Map<String, Object>> record(@PathVariable String projectId, @RequestBody DocumentChange change,
                                                      HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(List.of(), projectId, request);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.status(410).body(Map.of("success", false,
                "error", "SWRL rules are now saved inside the ontology, so no separate rule history is recorded"));
    }

    public record DocumentChange(String store, String operationType, String label, String description, String oldText,
                                 String newText, String before, String after) {}
}
