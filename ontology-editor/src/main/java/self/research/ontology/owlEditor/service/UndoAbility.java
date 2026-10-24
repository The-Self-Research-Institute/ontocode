package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Kind;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetImportService;

public final class UndoAbility {

    public record Result(boolean undoable, String reason) {}

    private static final RollbackMutationPlanner PLANNER = new RollbackMutationPlanner();

    private UndoAbility() {
    }

    public static Result of(HistoryChange change) {
        if (change.isReverted()) {
            return new Result(false, "Already undone");
        }
        if (RollbackWorkSupport.isRollbackEntry(change)) {
            return new Result(false, "This is an undo or redo, so it can't be undone itself");
        }
        if (change.isDraftDiscarded()) {
            return new Result(false, RedoGuard.DISCARDED);
        }
        if (change.getPublishedPrId() != null) {
            return new Result(false, PrEntries.MERGED_PR_PART);
        }
        if (RollbackWorkSupport.isPublicationEntry(change)) {
            return new Result(false, PrEntries.PUBLICATION_RECORD);
        }
        if (change.getUndoBlockedReason() != null) {
            return new Result(false, change.getUndoBlockedReason());
        }
        if (SpreadsheetImportService.isImportEntry(change.getOperationType())) {
            return new Result(true, null);
        }
        Kind kind = RollbackMutationPlanner.kindOf(change);
        if (kind == Kind.EXACT || kind == Kind.DOCUMENT || kind == Kind.MERGE) {
            return new Result(true, null);
        }
        boolean entityless = change.getEntityIRI() == null || change.getEntityIRI().isBlank();
        if (entityless && kind != Kind.PREFIX) {
            return new Result(false, "This kind of change can't be undone yet");
        }
        try {
            Plan plan = PLANNER.planUndo(change, RollbackMutationPlanner.active(change));
            if (!plan.unsupported().isEmpty()) {
                return new Result(false, "Can't undo " + describe(change) + " yet");
            }
        } catch (RuntimeException e) {
            return new Result(false, "Can't tell whether " + describe(change) + " can be undone");
        }
        return new Result(true, null);
    }

    private static String describe(HistoryChange change) {
        String op = change.getOperationType();
        return op == null || op.isBlank() ? "this change" : "a " + op + " change";
    }
}
