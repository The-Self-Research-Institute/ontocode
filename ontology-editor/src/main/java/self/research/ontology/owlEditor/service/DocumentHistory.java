package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

@Service
public class DocumentHistory {

    private final OntologyHistoryService historyService;

    public DocumentHistory(OntologyHistoryService historyService) {
        this.historyService = historyService;
    }

    public String record(String projectId, RequestActor actor, DocumentStore store, String beforeJson, String afterJson,
                         String operationType, String entityIri, String label, String oldText, String newText,
                         String description) {
        return record(projectId, actor, store, beforeJson, afterJson, operationType, entityIri, label, oldText, newText,
                description, false);
    }

    public String record(String projectId, RequestActor actor, DocumentStore store, String beforeJson, String afterJson,
                         String operationType, String entityIri, String label, String oldText, String newText,
                         String description, boolean draft) {
        String documentId = DocumentSnapshots.idOf(afterJson != null ? afterJson : beforeJson);
        if (documentId == null) {
            return null;
        }
        ChangeOrigin origin = ChangeOrigin.manual().withExact(new DocumentCarrier(store.key(), documentId, beforeJson, afterJson));
        try {
            historyService.recordEdit(projectId, actor.userId(), actor.username(), operationType, entityIri, label, oldText,
                    newText, description, null, null, draft, origin);
            return origin.changeSetId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
