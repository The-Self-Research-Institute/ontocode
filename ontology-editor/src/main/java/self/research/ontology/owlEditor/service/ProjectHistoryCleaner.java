package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.repository.UndoIntentRepository;

@Slf4j
@Service
public class ProjectHistoryCleaner {

    private final HistoryChangeRepository history;
    private final RollbackAuditRepository audits;
    private final UndoIntentRepository intents;

    public ProjectHistoryCleaner(HistoryChangeRepository history, RollbackAuditRepository audits,
                                 UndoIntentRepository intents) {
        this.history = history;
        this.audits = audits;
        this.intents = intents;
    }

    public void purge(String projectId) {
        try {
            history.deleteByProjectId(projectId);
            audits.deleteByProjectId(projectId);
            intents.deleteByProjectId(projectId);
        } catch (RuntimeException e) {
            log.warn("[ProjectLoadController] Failed to delete change history for {}: {}", projectId, e.getMessage());
        }
    }
}
