package self.research.ontology.owlEditor.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.UserUndoService.Dependent;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UndoQueueService {

    public static final int MAX_COUNT = 25;

    public record Step(String changeId, String changeSetId, String entityIRI, String label, int changeCount,
                       String sourceView, boolean available, String source, String affected,
                       String restoring) {}

    public record Blocked(String changeId, String changeSetId, String label, String reason,
                          List<Dependent> dependents) {}

    public record Queue(List<Step> steps, Blocked blocked) {}

    private final HistoryChangeRepository history;
    private final UserUndoService undo;

    public UndoQueueService(HistoryChangeRepository history, UserUndoService undo) {
        this.history = history;
        this.undo = undo;
    }

    public Queue queue(String projectId, boolean draft, Identity who, int count) {
        int wanted = Math.max(1, Math.min(count, MAX_COUNT));
        List<HistoryChange> own = history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(
                projectId, draft, who.queryValues(), PageRequest.of(0, Limit.UNDO_LOOKBACK.value()));
        Map<String, List<HistoryChange>> groups = new LinkedHashMap<>();
        for (HistoryChange entry : own) {
            if (entry.isReverted() || UserUndoService.isRollbackEntry(entry)) {
                continue;
            }
            String key = entry.getChangeSetId() == null ? "entry:" + entry.getId() : "set:" + entry.getChangeSetId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }
        List<Step> steps = new ArrayList<>();
        for (List<HistoryChange> group : groups.values()) {
            if (steps.size() >= wanted) {
                break;
            }
            Blocked blocked = firstBlock(projectId, draft, who, group);
            if (blocked != null) {
                return new Queue(steps, blocked);
            }
            steps.add(step(group));
        }
        return new Queue(steps, null);
    }

    private Blocked firstBlock(String projectId, boolean draft, Identity who, List<HistoryChange> group) {
        for (HistoryChange entry : group) {
            UserUndoService.Next blocked = undo.blockage(projectId, draft, who, entry);
            if (blocked != null) {
                return new Blocked(blocked.changeId(), blocked.changeSetId(), blocked.label(), blocked.reason(),
                        blocked.dependents());
            }
        }
        return null;
    }

    private static Step step(List<HistoryChange> group) {
        HistoryChange head = group.get(0);
        int changes = 0;
        for (HistoryChange entry : group) {
            changes += RollbackWorkSupport.entryItems(entry).size();
        }
        String label = group.size() == 1 ? UserUndoService.labelOf(head) : changes + " changes";
        UndoStepDetail.Detail detail = UndoStepDetail.of(group);
        return new Step(head.getId(), head.getChangeSetId(), head.getEntityIRI(), label, changes, null, true,
                detail.source(), detail.affected(), detail.restoring());
    }
}
