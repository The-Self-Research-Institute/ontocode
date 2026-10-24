package self.research.ontology.owlEditor.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class UserUndoService {


    public record Identity(String primary, Set<String> aliases) {
        public static Identity of(String primary, String... others) {
            Set<String> all = new LinkedHashSet<>();
            all.add(primary);
            for (String other : others) {
                if (other != null && !other.isBlank()) {
                    all.add(other);
                }
            }
            return new Identity(primary, all);
        }

        Set<String> queryValues() {
            Set<String> values = new LinkedHashSet<>();
            for (String alias : aliases) {
                values.add(alias);
                values.add(alias.toLowerCase(Locale.ROOT));
            }
            return values;
        }

        public boolean owns(String userId) {
            if (userId == null) {
                return false;
            }
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(userId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Dependent(String changeId, String username, String entityIRI, String label) {}

    public record Next(boolean available, String direction, String changeId, String changeSetId, String auditId,
                       String entityIRI, String label, String reason, List<Dependent> dependents) {
        static Next none(String direction, String reason) {
            return new Next(false, direction, null, null, null, null, null, reason, List.of());
        }
    }

    private final HistoryChangeRepository history;
    private final RollbackAuditRepository audits;
    private final ChangeRollbackService rollback;

    public UserUndoService(HistoryChangeRepository history, RollbackAuditRepository audits,
                           ChangeRollbackService rollback) {
        this.history = history;
        this.audits = audits;
        this.rollback = rollback;
    }

    public Next nextUndo(String projectId, boolean draft, Identity who) {
        HistoryChange target = latestToUndo(projectId, draft, who);
        if (target == null) {
            return Next.none(ChangeRollbackService.UNDO, draft ? "You have no changes to undo" : "There is nothing to undo");
        }
        Next blocked = blockage(projectId, draft, who, target);
        if (blocked != null) {
            return blocked;
        }
        return new Next(true, ChangeRollbackService.UNDO, target.getId(), target.getChangeSetId(), null,
                target.getEntityIRI(), labelOf(target), null, List.of());
    }

    Next blockage(String projectId, boolean draft, Identity who, HistoryChange target) {
        String label = labelOf(target);
        if (isBlank(target.getEntityIRI()) && !undoableWithoutEntity(target)) {
            return new Next(false, ChangeRollbackService.UNDO, target.getId(), target.getChangeSetId(), null, null,
                    label, "Your change (" + label + ") can't be undone yet", List.of());
        }
        List<Dependent> dependents = draft || isBlank(target.getEntityIRI()) ? List.of() : dependentsOf(projectId, target, who);
        if (dependents.isEmpty()) {
            return null;
        }
        return new Next(false, ChangeRollbackService.UNDO, target.getId(), target.getChangeSetId(), null,
                target.getEntityIRI(), label,
                "Others have changed " + label + " since, so it can't be undone with the shortcut", dependents);
    }

    public Next nextRedo(String projectId, boolean draft, Identity who) {
        List<RollbackAudit> undos = audits.findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(
                projectId, ChangeRollbackService.UNDO, who.queryValues(), PageRequest.of(0, Limit.REDO_AUDIT_LOOKBACK.value()));
        HistoryChange latest = latestOwn(projectId, draft, who);
        String currentDraft = draft ? currentDraftSessionId(projectId, who) : null;
        for (RollbackAudit undo : undos) {
            List<HistoryChange> entries = rollback.entriesForUndo(projectId, undo.getId());
            if (entries.isEmpty() || entries.get(0).isDraft() != draft) {
                continue;
            }
            if (redoGuard != null && redoGuard.refusal(projectId, entries).isPresent()
                    || fromAnotherDraft(entries.get(0), draft, currentDraft)) {
                continue;
            }
            if (!stillUndone(entries, undo.getId())) {
                continue;
            }
            if (newerThan(latest, undo.getRevertedAt())) {
                return Next.none(ChangeRollbackService.REDO, "You made a new change after undoing, so there is nothing to redo");
            }
            HistoryChange first = entries.get(0);
            return new Next(true, ChangeRollbackService.REDO, first.getId(), undo.getChangeSetId(), undo.getId(),
                    first.getEntityIRI(), entries.size() == 1 ? labelOf(first) : entries.size() + " changes", null,
                    List.of());
        }
        HistoryChange reversal = draft ? null : latestOwnPrReversal(projectId, who);
        if (reversal != null) {
            String title = reversal.getEntityLabel() == null ? "" : reversal.getEntityLabel().replaceFirst("^Reverted PR: ", "");
            return new Next(true, ChangeRollbackService.REDO, reversal.getId(), null, null, reversal.getEntityIRI(),
                    "Apply again: " + title, null, List.of());
        }
        return Next.none(ChangeRollbackService.REDO, "Nothing to redo");
    }

    private HistoryChange latestOwnPrReversal(String projectId, Identity who) {
        List<HistoryChange> own = history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(
                projectId, false, who.queryValues(), PageRequest.of(0, Limit.UNDO_LOOKBACK.value()));
        for (HistoryChange entry : own) {
            if (isRollbackEntry(entry)) {
                continue;
            }
            boolean reversal = RollbackWorkSupport.PR_REVERTED.equals(entry.getOperationType())
                    && PrEntries.prIdOf(entry.getEntityIRI()) != null;
            if (reversal && !entry.isReverted()) {
                return entry;
            }
        }
        return null;
    }

    private HistoryChange latestOwn(String projectId, boolean draft, Identity who) {
        List<HistoryChange> own = history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(
                projectId, draft, who.queryValues(), PageRequest.of(0, Limit.UNDO_LOOKBACK.value()));
        return firstUndoable(own, draft, draft ? currentDraftSessionId(projectId, who) : null);
    }

    private HistoryChange latestToUndo(String projectId, boolean draft, Identity who) {
        if (draft) {
            return latestOwn(projectId, true, who);
        }
        List<HistoryChange> newest = history.findByProjectIdAndDraftOrderByTimestampDesc(
                projectId, false, PageRequest.of(0, Limit.UNDO_LOOKBACK.value()));
        return firstUndoable(newest, false, null);
    }

    private static HistoryChange firstUndoable(List<HistoryChange> rows, boolean draft, String currentDraft) {
        for (HistoryChange entry : rows) {
            if (!entry.isReverted() && !entry.isDraftDiscarded() && entry.getPublishedPrId() == null && !isRollbackEntry(entry)
                    && (!RollbackWorkSupport.isPublicationEntry(entry) || RollbackWorkSupport.isUndoablePublication(entry))
                    && !fromAnotherDraft(entry, draft, currentDraft)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean fromAnotherDraft(HistoryChange entry, boolean draft, String currentDraft) {
        return draft && entry.getDraftSessionId() != null && !entry.getDraftSessionId().equals(currentDraft);
    }

    private String currentDraftSessionId(String projectId, Identity who) {
        if (draftSessions == null) {
            return null;
        }
        for (String alias : who.aliases()) {
            Optional<DraftSession> session = draftSessions.findByProjectIdAndUserId(projectId, alias);
            if (session.isPresent()) {
                return session.get().getId();
            }
        }
        return null;
    }

    private DraftSessionRepository draftSessions;
    private RedoGuard redoGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRedoGuard(RedoGuard redoGuard) {
        this.redoGuard = redoGuard;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDraftSessions(DraftSessionRepository draftSessions) {
        this.draftSessions = draftSessions;
    }

    private List<Dependent> dependentsOf(String projectId, HistoryChange target, Identity who) {
        List<Dependent> found = new ArrayList<>();
        List<HistoryChange> onEntity = history.findByProjectIdAndEntityIRIOrderByTimestampDesc(
                projectId, target.getEntityIRI(), PageRequest.of(0, Limit.DEPENDENT_SCAN.value()));
        for (HistoryChange other : onEntity) {
            if (other.getTimestamp() == null || target.getTimestamp() == null
                    || !other.getTimestamp().isAfter(target.getTimestamp())) {
                break;
            }
            if (other.isDraft() || other.isReverted() || isRollbackEntry(other) || who.owns(other.getUserId())) {
                continue;
            }
            found.add(new Dependent(other.getId(), other.getUsername(), other.getEntityIRI(), labelOf(other)));
            if (found.size() >= Limit.MAX_DEPENDENTS_SHOWN.value()) {
                break;
            }
        }
        return found;
    }

    private static boolean stillUndone(List<HistoryChange> entries, String auditId) {
        for (HistoryChange entry : entries) {
            boolean entryUndone = entry.isReverted() && auditId.equals(entry.getRevertedAuditId());
            if (entryUndone || !RollbackMutationPlanner.revertedBy(entry, auditId).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean newerThan(HistoryChange change, LocalDateTime moment) {
        return change != null && change.getTimestamp() != null && moment != null && change.getTimestamp().isAfter(moment);
    }

    private static boolean undoableWithoutEntity(HistoryChange entry) {
        RollbackMutationPlanner.Kind kind = RollbackMutationPlanner.kindOf(entry);
        return kind == RollbackMutationPlanner.Kind.EXACT || kind == RollbackMutationPlanner.Kind.PREFIX;
    }

    static boolean isRollbackEntry(HistoryChange entry) {
        String op = entry.getOperationType();
        return ChangeOrigin.ROLLBACK.equals(entry.getSource())
                || (op != null && (op.startsWith("ROLLBACK_") || op.startsWith("REDO_")));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String labelOf(HistoryChange entry) {
        if (!isBlank(entry.getEntityLabel())) {
            return entry.getEntityLabel();
        }
        String named = nameFromOperation(entry);
        if (named != null) {
            return named;
        }
        if (!isBlank(entry.getDescription())) {
            return entry.getDescription();
        }
        return isBlank(entry.getEntityIRI()) ? "a change" : entry.getEntityIRI();
    }

    private static String nameFromOperation(HistoryChange entry) {
        String iri = entry.getEntityIRI();
        String operation = entry.getOperationType();
        if (isBlank(iri) || isBlank(operation) || iri.startsWith("urn:")) {
            return null;
        }
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        String name = cut >= 0 ? iri.substring(cut + 1) : iri;
        if (name.isBlank()) {
            return null;
        }
        String words = operation.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1) + " " + name;
    }
}
