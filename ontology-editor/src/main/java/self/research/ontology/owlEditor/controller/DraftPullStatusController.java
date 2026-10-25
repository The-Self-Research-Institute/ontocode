package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology")
public class DraftPullStatusController {

    private final DraftSessionRepository sessions;
    private final MainGraphRevisionService revisions;

    public DraftPullStatusController(DraftSessionRepository sessions, MainGraphRevisionService revisions) {
        this.sessions = sessions;
        this.revisions = revisions;
    }

    @GetMapping("/{projectId}/draft/pull-status")
    public ResponseEntity<Map<String, Object>> pullStatus(@PathVariable String projectId, @RequestParam String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        Optional<DraftSession> session = sessions.findByProjectIdAndUserId(projectId, userId);
        if (session.isEmpty()) {
            body.put("hasDraft", false);
            body.put("pending", false);
            return ResponseEntity.ok(body);
        }
        long publicRevision = revisions.getRevision(projectId);
        long baselineRevision = session.get().getBaselineMainRevision();
        body.put("hasDraft", true);
        body.put("pending", publicRevision > baselineRevision);
        body.put("publicRevision", publicRevision);
        body.put("baselineRevision", baselineRevision);
        return ResponseEntity.ok(body);
    }
}
