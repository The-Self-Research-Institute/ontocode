package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.RuleWriteAccess;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
public class RuleAccessController {

    private final RuleWriteAccess access;

    public RuleAccessController(RuleWriteAccess access) {
        this.access = access;
    }

    @GetMapping("/{projectId}/rules-access")
    public ResponseEntity<Map<String, Object>> rulesAccess(@PathVariable String projectId, HttpServletRequest request) {
        RuleWriteAccess.Decision decision = access.decide(request.getHeader("Authorization"), request.getRemoteAddr(), projectId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("canWriteRules", decision.allowed());
        if (decision.reason() != null) {
            body.put("reason", decision.reason());
        }
        return ResponseEntity.ok(body);
    }
}
