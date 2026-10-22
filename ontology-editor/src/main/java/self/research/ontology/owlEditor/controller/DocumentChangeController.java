package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DocumentHistory;
import self.research.ontology.owlEditor.service.DocumentStore;
import self.research.ontology.owlEditor.service.RequestActor;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class DocumentChangeController {

    private static final Set<String> REPORTED_OPERATIONS = Set.of("createSwrlRule", "updateSwrlRule", "deleteSwrlRule");

    private final DocumentHistory documentHistory;
    private final RollbackRequestSupport support;

    public DocumentChangeController(DocumentHistory documentHistory, RollbackRequestSupport support) {
        this.documentHistory = documentHistory;
        this.support = support;
    }

    @PostMapping("/{projectId}/changes/document")
    public ResponseEntity<Map<String, Object>> record(@PathVariable String projectId, @RequestBody DocumentChange change,
                                                      HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(List.of(), projectId, request);
        if (denied != null) {
            return denied;
        }
        if (!DocumentStore.SWRL_RULE.key().equals(change.store()) || !REPORTED_OPERATIONS.contains(change.operationType())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Unsupported document change"));
        }
        String problem = validate(projectId, change);
        if (problem != null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", problem));
        }
        String documentId = Document.parse(change.after() != null ? change.after() : change.before()).get("_id").toString();
        String changeSetId = documentHistory.record(projectId, RequestActor.of(request, null, null), DocumentStore.SWRL_RULE,
                change.before(), change.after(), change.operationType(), "urn:swrlrule:" + documentId, change.label(),
                change.oldText(), change.newText(), change.description());
        return ResponseEntity.ok(changeSetId == null
                ? Map.of("success", false, "error", "The change could not be recorded")
                : Map.of("success", true, "changeSetId", changeSetId));
    }

    private static String validate(String projectId, DocumentChange change) {
        if (change.before() == null && change.after() == null) {
            return "A change needs a before or after state";
        }
        Object id = null;
        for (String json : new String[] {change.before(), change.after()}) {
            if (json == null) {
                continue;
            }
            Document document;
            try {
                document = Document.parse(json);
            } catch (RuntimeException e) {
                return "The document is not valid JSON";
            }
            if (!projectId.equals(document.getString("projectId")) || document.get("_id") == null) {
                return "The document does not belong to this project";
            }
            if (id != null && !id.equals(document.get("_id"))) {
                return "The before and after states are different documents";
            }
            id = document.get("_id");
        }
        return null;
    }

    public record DocumentChange(String store, String operationType, String label, String description, String oldText,
                                 String newText, String before, String after) {}
}
