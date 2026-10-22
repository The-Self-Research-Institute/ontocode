package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Kind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentReversalTest {

    private static final String BEFORE = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"name\":\"old\"}";
    private static final String AFTER = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"name\":\"new\"}";

    private static HistoryChange entry(String operation, String before, String after) {
        HistoryChange change = new HistoryChange.Builder("p", "edit-1", "u", "u").operationType(operation)
                .entityIRI("urn:fuzzyrule:6acf64ae94e5320b04bb880a").entityLabel("Rule").build();
        change.setDocumentStore("fuzzyRule");
        change.setDocumentId("6acf64ae94e5320b04bb880a");
        change.setDocumentBefore(before);
        change.setDocumentAfter(after);
        return change;
    }

    @Test
    void anEntryWithADocumentIsPlannedAsADocumentChange() {
        assertEquals(Kind.DOCUMENT, RollbackMutationPlanner.kindOf(entry("updateFuzzyRule", BEFORE, AFTER)));
    }

    @Test
    void undoingAnUpdateRestoresTheOldDocumentAndExpectsTheNewOne() {
        List<RollbackAction> actions = DocumentReversal.actions(entry("updateFuzzyRule", BEFORE, AFTER), true);

        assertEquals(1, actions.size());
        assertEquals(RollbackAction.RESTORE_DOCUMENT, actions.get(0).type());
        assertEquals(BEFORE, actions.get(0).document());
        assertEquals(AFTER, actions.get(0).expectedDocument());
    }

    @Test
    void redoingAnUpdateRestoresTheNewDocumentAndExpectsTheOldOne() {
        RollbackAction action = DocumentReversal.actions(entry("updateFuzzyRule", BEFORE, AFTER), false).get(0);

        assertEquals(AFTER, action.document());
        assertEquals(BEFORE, action.expectedDocument());
    }

    @Test
    void undoingACreationDeletesTheDocumentAndRedoingADeletionDoesToo() {
        RollbackAction undoCreate = DocumentReversal.actions(entry("createFuzzyRule", null, AFTER), true).get(0);
        RollbackAction redoDelete = DocumentReversal.actions(entry("deleteFuzzyRule", BEFORE, null), false).get(0);

        assertNull(undoCreate.document());
        assertEquals(AFTER, undoCreate.expectedDocument());
        assertNull(redoDelete.document());
        assertEquals(BEFORE, redoDelete.expectedDocument());
    }

    @Test
    void theStoredDocumentsPlanAnUndoThatTheServerOffers() {
        HistoryChange change = entry("updateFuzzyRule", BEFORE, AFTER);

        assertTrue(UndoAbility.of(change).undoable());
        assertFalse(new RollbackMutationPlanner().planUndo(change, List.of()).isEmpty());
        assertEquals(RollbackWorkSupport.Presence.IGNORED, RollbackWorkSupport.presence(change, Kind.DOCUMENT, true));
    }

    @Test
    void anEntryWithoutADocumentPlansNothing() {
        assertTrue(DocumentReversal.actions(new HistoryChange.Builder("p", "e", "u", "u").build(), true).isEmpty());
    }
}
