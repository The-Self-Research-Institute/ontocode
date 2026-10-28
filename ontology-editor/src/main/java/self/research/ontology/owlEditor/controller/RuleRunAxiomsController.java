package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.RuleRunAccess;
import self.research.ontology.owlEditor.service.RuleRunPager;
import self.research.ontology.owlEditor.service.RuleRunStore;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology")
public class RuleRunAxiomsController {

    static final String EXPIRED = "This result has expired. Run Execute again.";

    private final RuleRunStore store;
    private final RuleRunAccess access;

    public RuleRunAxiomsController(RuleRunStore store, RuleRunAccess access) {
        this.store = store;
        this.access = access;
    }

    @GetMapping("/{projectId}/rules/execute/{runId}/axioms")
    public ResponseEntity<Map<String, Object>> axioms(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + RuleRunPager.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "conclusions") String kind,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean draft,
            HttpServletRequest request
    ) {
        RuleRunAccess.Decision decision = access.check(request, projectId);
        if (!decision.allowed()) {
            String message = decision.status() == 401 ? "Authentication required" : "You do not have access to this project";
            return ResponseEntity.status(decision.status()).body(Map.of("message", message));
        }
        Optional<RuleRunStore.Run> found = store.find(runId).filter(run -> run.projectId().equals(projectId)
                && run.draft() == draft && run.ownerId().equals(decision.userId()));
        if (found.isEmpty()) {
            return ResponseEntity.status(410).body(Map.of("message", EXPIRED));
        }
        if (!"conclusions".equals(kind) && !"structure".equals(kind)) {
            return ResponseEntity.badRequest().body(Map.of("message", "kind must be conclusions or structure"));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "offset must be 0 or more"));
        }
        if (limit < 1 || limit > RuleRunPager.MAX_LIMIT) {
            return ResponseEntity.badRequest().body(Map.of("message", "limit must be between 1 and " + RuleRunPager.MAX_LIMIT));
        }
        return ResponseEntity.ok(RuleRunPager.page(found.get(), kind, q, offset, limit));
    }
}
