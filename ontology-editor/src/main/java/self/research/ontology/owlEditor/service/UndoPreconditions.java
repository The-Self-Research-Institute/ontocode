package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Kind;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;
import self.research.ontology.owlEditor.service.delta.ExactReversal;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetImportService;

import java.util.List;
import java.util.Optional;

@Service
public class UndoPreconditions {

    private final RollbackMutationPlanner planner;
    private final RollbackGraphFacts facts;
    private UndoBlockers blockers;

    public UndoPreconditions(RollbackMutationPlanner planner, RollbackGraphFacts facts) {
        this.planner = planner;
        this.facts = facts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBlockers(UndoBlockers blockers) {
        this.blockers = blockers;
    }

    public Optional<String> violation(String projectId, List<HistoryChange> entries) {
        return violation(projectId, entries, null);
    }

    public Optional<String> violation(String projectId, List<HistoryChange> entries, UserUndoService.Identity who) {
        org.eclipse.rdf4j.model.Model own = RollbackWorkSupport.insertedTriples(entries);
        for (HistoryChange entry : entries) {
            Optional<String> problem = check(projectId, entry, own, who);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private Optional<String> check(String projectId, HistoryChange entry, org.eclipse.rdf4j.model.Model own,
                                   UserUndoService.Identity who) {
        String label = labelOf(entry);
        if (entry.isReverted()) {
            return Optional.of(label + " was already undone");
        }
        if (entry.getUndoBlockedReason() != null) {
            return Optional.of(entry.getUndoBlockedReason());
        }
        if (SpreadsheetImportService.isImportEntry(entry.getOperationType())) {
            return Optional.empty();
        }
        Kind kind = RollbackMutationPlanner.kindOf(entry);
        boolean entityless = entry.getEntityIRI() == null || entry.getEntityIRI().isBlank();
        if (entityless && kind != Kind.EXACT && kind != Kind.PREFIX) {
            return Optional.of("This kind of change can't be undone yet");
        }
        if (kind == Kind.DELETE && facts.storeRecordsExact(projectId, entry.isDraft())) {
            return Optional.of(RollbackWorkSupport.INEXACT_DELETE);
        }
        RollbackWorkSupport.Presence presence = RollbackWorkSupport.presence(entry, kind, true);
        boolean exists = presence != RollbackWorkSupport.Presence.IGNORED
                && facts.entityExists(projectId, entry.getEntityIRI(), entry.isDraft(), entry.getUserId());
        if (presence == RollbackWorkSupport.Presence.FORBIDDEN && exists) {
            return Optional.of(label + " was created again since this change");
        }
        if (presence == RollbackWorkSupport.Presence.REQUIRED && !exists) {
            return Optional.of(label + " no longer exists");
        }
        if (kind == Kind.RENAME && facts.entityExists(projectId, entry.getOldValue(), entry.isDraft(), entry.getUserId())) {
            return Optional.of("The old name is in use again");
        }
        List<HistoryChange.SubChange> active = RollbackMutationPlanner.active(entry);
        Plan plan = planner.planUndo(entry, active);
        if (!plan.unsupported().isEmpty()) {
            return Optional.of("This kind of change can't be undone yet");
        }
        if (kind == Kind.MERGE && blockers != null && blockers.importRunning(projectId)) {
            return Optional.of(UndoBlockers.STILL_LOADING);
        }
        if (kind == Kind.MERGE && blockers != null) {
            List<String> later = blockers.laterChanges(projectId, entry, who);
            if (!later.isEmpty()) {
                return Optional.of(blockers.changedSince(projectId, entry, label + " was changed since this edit", who));
            }
        }
        if (entry.carriesExactDelta() && !facts.deltaHolds(projectId, entry, true)) {
            return Optional.of(changedSince(projectId, entry, label, who));
        }
        if (facts.laterUsesNewName(projectId, entry)) {
            return Optional.of("Others have added items that use the new name of " + label);
        }
        for (HistoryChange.SubChange sub : active) {
            if (kind != Kind.EXACT && plan.handledSubChangeIds().contains(sub.getId()) && changedSince(projectId, entry, sub)) {
                return Optional.of(changedSince(projectId, entry, label, who));
            }
        }
        if (RollbackWorkSupport.isCreation(entry, kind)
                && facts.referencedOutside(projectId, entry.getEntityIRI(), entry.isDraft(), entry.getUserId(), own)) {
            return Optional.of("Other items still refer to " + label);
        }
        return Optional.empty();
    }

    private String changedSince(String projectId, HistoryChange entry, String label, UserUndoService.Identity who) {
        String plain = label + " was changed since this edit";
        return blockers == null ? plain : blockers.changedSince(projectId, entry, plain, who);
    }

    private boolean changedSince(String projectId, HistoryChange entry, HistoryChange.SubChange sub) {
        String value = sub.isAddition() ? sub.getNewValue() : sub.getOldValue();
        Boolean present = facts.statementPresent(projectId, entry.getEntityIRI(), sub.getPredicate(), value,
                entry.isDraft(), entry.getUserId());
        return present != null && present != sub.isAddition();
    }

    private static String labelOf(HistoryChange entry) {
        if (entry.getEntityLabel() != null && !entry.getEntityLabel().isBlank()) {
            return entry.getEntityLabel();
        }
        return entry.getEntityIRI() == null ? "This change" : entry.getEntityIRI();
    }
}
