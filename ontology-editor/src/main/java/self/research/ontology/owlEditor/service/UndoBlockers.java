package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class UndoBlockers {

    static final String STILL_LOADING = "The ontology is still loading after the merge. Try again in a moment";
    private static final int LOOKBACK = 200;
    private static final int NAMED = 3;

    private final HistoryChangeRepository history;
    private final ProjectImportService imports;

    @Autowired
    public UndoBlockers(HistoryChangeRepository history, @Lazy ProjectImportService imports) {
        this.history = history;
        this.imports = imports;
    }

    public boolean importRunning(String projectId) {
        return imports.isImportActiveOrQueued(projectId);
    }

    public List<String> laterChanges(String projectId, HistoryChange entry) {
        List<String> names = new ArrayList<>();
        if (entry == null || entry.getId() == null) {
            return names;
        }
        for (HistoryChange other : history.findByProjectId(projectId,
                PageRequest.of(0, LOOKBACK, Sort.by(Sort.Direction.DESC, "timestamp")))) {
            if (entry.getId().equals(other.getId())) {
                return names;
            }
            if (isStillInEffect(other) && !sameSet(entry, other)) {
                names.add(describe(other));
                if (names.size() == NAMED) {
                    return names;
                }
            }
        }
        return names;
    }

    public String changedSince(String projectId, HistoryChange entry, String fallback) {
        List<String> later = laterChanges(projectId, entry);
        return later.isEmpty() ? fallback : "Later changes come first. Undo these before this one: " + String.join(", ", later);
    }

    private static boolean isStillInEffect(HistoryChange change) {
        return !RollbackWorkSupport.isRollbackEntry(change) && !change.isReverted()
                && !"projectImported".equals(change.getOperationType());
    }

    private static boolean sameSet(HistoryChange a, HistoryChange b) {
        return a.getChangeSetId() != null && Objects.equals(a.getChangeSetId(), b.getChangeSetId());
    }

    private static String describe(HistoryChange change) {
        String label = change.getEntityLabel() != null && !change.getEntityLabel().isBlank() ? change.getEntityLabel()
                : change.getOperationType() == null ? "a change" : change.getOperationType();
        return change.getUsername() == null || change.getUsername().isBlank() ? label : label + " (" + change.getUsername() + ")";
    }
}
