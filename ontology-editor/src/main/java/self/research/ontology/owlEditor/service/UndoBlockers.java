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
    private static final int MAX_OTHERS = 20;

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

    public List<String> laterChanges(String projectId, HistoryChange entry, UserUndoService.Identity who) {
        List<String> names = new ArrayList<>();
        for (HistoryChange other : laterEntries(projectId, entry, who)) {
            names.add(describe(other));
        }
        return names;
    }

    private List<HistoryChange> laterEntries(String projectId, HistoryChange entry, UserUndoService.Identity who) {
        List<HistoryChange> later = new ArrayList<>();
        if (entry == null || entry.getId() == null) {
            return later;
        }
        for (HistoryChange other : history.findByProjectId(projectId,
                PageRequest.of(0, LOOKBACK, Sort.by(Sort.Direction.DESC, "timestamp")))) {
            if (entry.getId().equals(other.getId())) {
                return later;
            }
            if (blocks(entry, other, who)) {
                later.add(other);
                if (later.size() == NAMED) {
                    return later;
                }
            }
        }
        return later;
    }

    private static boolean blocks(HistoryChange entry, HistoryChange other, UserUndoService.Identity who) {
        if (!isStillInEffect(other) || other.isDraftDiscarded() || sameSet(entry, other)) {
            return false;
        }
        if (other.isDraft() != entry.isDraft()) {
            return false;
        }
        if (entry.isDraft() && entry.getDraftSessionId() != null && other.getDraftSessionId() != null
                && !entry.getDraftSessionId().equals(other.getDraftSessionId())) {
            return false;
        }
        if (entry.isDraft() && other.getUserId() != null && !sameAuthor(entry, other, who)) {
            return false;
        }
        return true;
    }

    public List<HistoryChange> othersOnTop(String projectId, HistoryChange entry, UserUndoService.Identity who) {
        List<HistoryChange> others = new ArrayList<>();
        if (entry == null || entry.getId() == null || entry.isDraft()) {
            return others;
        }
        for (HistoryChange other : history.findByProjectId(projectId,
                PageRequest.of(0, LOOKBACK, Sort.by(Sort.Direction.DESC, "timestamp")))) {
            if (entry.getId().equals(other.getId())) {
                return others;
            }
            if (blocks(entry, other, who) && !isMine(other, who)) {
                others.add(other);
                if (others.size() == MAX_OTHERS) {
                    return others;
                }
            }
        }
        return others;
    }

    private static boolean isMine(HistoryChange change, UserUndoService.Identity who) {
        return who != null && who.owns(change.getUserId());
    }

    public String changedSince(String projectId, HistoryChange entry, String fallback) {
        return changedSince(projectId, entry, fallback, null);
    }

    public String changedSince(String projectId, HistoryChange entry, String fallback, UserUndoService.Identity who) {
        List<HistoryChange> later = laterEntries(projectId, entry, who);
        if (later.isEmpty()) {
            return fallback;
        }
        String names = String.join(", ", later.stream().map(UndoBlockers::describe).toList());
        if (later.stream().anyMatch(other -> !sameAuthor(entry, other, who))) {
            return "Other people's changes are on top of this one: " + names
                    + ". Undo this edit by hand instead";
        }
        return "Later changes come first. Undo these before this one: " + names;
    }

    private static boolean sameAuthor(HistoryChange entry, HistoryChange other, UserUndoService.Identity who) {
        String first = entry.getUserId();
        String second = other.getUserId();
        if (first == null || second == null) {
            return false;
        }
        if (who != null && who.owns(first)) {
            return who.owns(second);
        }
        return first.equalsIgnoreCase(second);
    }

    private static boolean isStillInEffect(HistoryChange change) {
        return !RollbackWorkSupport.isRollbackEntry(change) && !RollbackWorkSupport.isPublicationEntry(change)
                && !change.isReverted()
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
