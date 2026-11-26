package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.service.ReasonerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class QuickUnsatReplies {

    private QuickUnsatReplies() {
    }

    static ResponseEntity<Map<String, Object>> skipped(String projectId, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("projectId", projectId);
        body.put("skipped", true);
        body.put("reason", reason);
        body.put("unsatisfiableClasses", List.of());
        return ResponseEntity.ok(body);
    }

    static ResponseEntity<Map<String, Object>> of(String projectId, ReasonerService.QuickUnsatResult result) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("projectId", projectId);
        body.put("unsatisfiableClasses", result.unsatisfiableClasses());
        body.put("inconsistent", result.inconsistent());
        return ResponseEntity.ok(body);
    }
}
