package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;
import self.research.ontology.owlEditor.service.RollbackWorkSupport.Work;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetImportService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static self.research.ontology.owlEditor.service.RollbackWorkSupport.skip;

final class SpreadsheetImportUndo {

    private final SpreadsheetImportService imports;

    SpreadsheetImportUndo(SpreadsheetImportService imports) {
        this.imports = imports;
    }

    boolean handles(HistoryChange entry) {
        return SpreadsheetImportService.isImportEntry(entry.getOperationType());
    }

    Work evaluate(String projectId, boolean undo, String subChangeId, String undoAuditId, HistoryChange entry) {
        if (subChangeId != null) {
            return skip(entry, "A spreadsheet import is undone as a whole");
        }
        if (undo && entry.isReverted()) {
            return skip(entry, "Already undone");
        }
        boolean undone = entry.isReverted() && (undoAuditId == null || undoAuditId.equals(entry.getRevertedAuditId()));
        if (!undo && !undone) {
            return skip(entry, "It hasn't been undone");
        }
        List<String> updates = imports.rollbackUpdates(projectId, entry.getEntityIRI(), undo);
        if (updates == null) {
            return skip(entry, "The record of this import was not found");
        }
        Plan plan = new Plan(List.of(), updates, Set.of(), List.of());
        return new Work(entry, plan, List.of(), new ArrayList<>(), true, false);
    }

    boolean applyOnDesktop(String projectId, List<String> rawUpdates) {
        return imports.applyOnDesktop(projectId, rawUpdates);
    }

    void markUndone(String projectId, HistoryChange entry, boolean undo, String username) {
        imports.markUndone(projectId, entry.getEntityIRI(), undo, username);
    }
}
