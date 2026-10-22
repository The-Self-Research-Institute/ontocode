package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;

public final class DocumentReversal {

    private DocumentReversal() {
    }

    public static List<RollbackAction> actions(HistoryChange change, boolean undo) {
        if (!change.carriesDocument()) {
            return List.of();
        }
        String target = undo ? change.getDocumentBefore() : change.getDocumentAfter();
        String expected = undo ? change.getDocumentAfter() : change.getDocumentBefore();
        return List.of(RollbackAction.restoreDocument(change.getDocumentStore(), change.getDocumentId(), target, expected));
    }
}
