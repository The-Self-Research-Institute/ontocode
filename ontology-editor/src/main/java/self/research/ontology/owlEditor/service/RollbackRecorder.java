package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Item;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Request;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class RollbackRecorder {

    private final RollbackAuditRepository audits;
    private final OntologyHistoryService history;
    private final SimpMessagingTemplate messaging;

    RollbackRecorder(RollbackAuditRepository audits, OntologyHistoryService history, SimpMessagingTemplate messaging) {
        this.audits = audits;
        this.history = history;
        this.messaging = messaging;
    }

    RollbackAudit saveAudit(Request req, List<HistoryChange> entries, int skippedCount) {
        RollbackAudit audit = new RollbackAudit();
        audit.setProjectId(req.projectId());
        audit.setChangeSetId(req.changeSetId());
        audit.setDirection(req.direction());
        audit.setHistoryChangeId(req.changeId());
        audit.setSubChangeId(req.subChangeId());
        audit.setChangeIds(entries.stream().map(HistoryChange::getId).toList());
        audit.setSkippedCount(skippedCount);
        if (entries.size() == 1) {
            audit.setEntityIRI(entries.get(0).getEntityIRI());
            audit.setEntityLabel(entries.get(0).getEntityLabel());
        }
        audit.setRevertedByUserId(req.actor().userId());
        audit.setRevertedByUsername(req.actor().username());
        return audits.save(audit);
    }

    String recordHistory(Request req, List<HistoryChange> entries, List<Item> applied, String auditId) {
        HistoryChange first = entries.get(0);
        String opType = (ChangeRollbackService.UNDO.equals(req.direction()) ? "ROLLBACK_" : "REDO_") + req.level();
        String reverts = req.changeSetId() != null ? req.changeSetId() : first.getChangeSetId();
        String label = entries.size() == 1 ? first.getEntityLabel() : null;
        String description = RollbackDescriptions.describe(req.direction(), req.level(), applied, entries);
        history.recordEdit(req.projectId(), req.actor().userId(), req.actor().username(), opType,
                entries.size() == 1 ? first.getEntityIRI() : null, label, null, null, description,
                null, null, first.isDraft(), ChangeOrigin.rollback(reverts, auditId));
        return description;
    }

    void broadcast(Request req, String auditId, List<HistoryChange> entries, String description) {
        if (messaging == null) {
            return;
        }
        Map<String, Object> event = new HashMap<>();
        event.put("type", "ROLLBACK");
        event.put("direction", req.direction());
        event.put("projectId", req.projectId());
        event.put("changeSetId", req.changeSetId());
        event.put("changeId", req.changeId());
        event.put("subChangeId", req.subChangeId());
        event.put("auditId", auditId);
        event.put("description", description);
        if (entries.size() == 1) {
            event.put("entityIRI", entries.get(0).getEntityIRI());
            event.put("entityLabel", entries.get(0).getEntityLabel());
        }
        event.put("userId", req.actor().userId());
        event.put("username", req.actor().username());
        if (req.actor().userId() != null && req.actor().userId().contains("@")) {
            event.put("userEmail", req.actor().userId());
        }
        event.put("timestamp", System.currentTimeMillis());
        try {
            messaging.convertAndSend("/topic/ontology/" + req.projectId(), event);
        } catch (Exception e) {
            log.warn("[ROLLBACK] Could not broadcast rollback event for project {}: {}", req.projectId(), e.getMessage());
        }
    }
}
