package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoAbilityTest {

    private static HistoryChange change(String op, String iri) {
        HistoryChange change = new HistoryChange("p", "e", "u", "u");
        change.setId("c1");
        change.setOperationType(op);
        change.setEntityIRI(iri);
        change.setSubChanges(new ArrayList<>());
        return change;
    }

    @Test
    void anUndoneChangeIsNotUndoable() {
        HistoryChange change = change("createClass", "http://ex/A");
        change.setReverted(true);
        UndoAbility.Result result = UndoAbility.of(change);
        assertFalse(result.undoable());
        assertEquals("Already undone", result.reason());
    }

    @Test
    void aSpreadsheetImportIsUndoable() {
        UndoAbility.Result result = UndoAbility.of(change("SPREADSHEET_IMPORT", "import-1"));
        assertTrue(result.undoable());
        assertNull(result.reason());
    }

    @Test
    void anUndoneSpreadsheetImportIsNotUndoableAgain() {
        HistoryChange change = change("SPREADSHEET_IMPORT", "import-1");
        change.setReverted(true);
        assertFalse(UndoAbility.of(change).undoable());
    }

    @Test
    void anUndoOrRedoRowCannotBeUndone() {
        HistoryChange change = change("ROLLBACK_CHANGESET", "http://ex/A");
        change.setSource(ChangeOrigin.ROLLBACK);
        assertFalse(UndoAbility.of(change).undoable());
    }

    @Test
    void anEntityCreationIsUndoable() {
        assertTrue(UndoAbility.of(change("createClass", "http://ex/A")).undoable());
    }

    @Test
    void aChangeWithNoEntityAndNoStoredDeltaIsNotUndoableYet() {
        UndoAbility.Result result = UndoAbility.of(change("codeViewStructuralEdit", null));
        assertFalse(result.undoable());
        assertEquals("This kind of change can't be undone yet", result.reason());
    }

    @Test
    void aChangeThatStoredItsExactTriplesIsUndoableEvenWithoutAnEntity() {
        HistoryChange change = change("codeViewStructuralEdit", null);
        change.setExactInserted("<http://ex/a> <http://ex/p> <http://ex/b> .\n");
        assertTrue(UndoAbility.of(change).undoable());
    }

    @Test
    void aPrefixChangeWithBothNamespacesIsUndoable() {
        HistoryChange change = change("prefixModified", null);
        change.setEntityLabel("ex");
        change.setOldValue("http://old/");
        change.setNewValue("http://new/");
        assertTrue(UndoAbility.of(change).undoable());
    }

    @Test
    void aPrefixChangeThatLostItsOldNamespaceIsNot() {
        HistoryChange change = change("prefixModified", null);
        change.setEntityLabel("ex");
        change.setNewValue("http://new/");
        UndoAbility.Result result = UndoAbility.of(change);
        assertFalse(result.undoable());
        assertTrue(result.reason().startsWith("Can't undo"));
    }

    @Test
    void anOperationTheServerCannotReverseGivesAReason() {
        UndoAbility.Result result = UndoAbility.of(change("somethingNew", "http://ex/A"));
        assertFalse(result.undoable());
        assertTrue(result.reason().contains("somethingNew"));
        assertNull(UndoAbility.of(change("createClass", "http://ex/A")).reason());
    }

    @Test
    void classificationPutsAssertionsUnderIndividualsAndAddsTheNewBuckets() {
        assertEquals("INDIVIDUAL", HistoryClassification.entityType("addClassAssertion"));
        assertEquals("INDIVIDUAL", HistoryClassification.entityType("addObjectPropertyAssertion"));
        assertEquals("PREFIX", HistoryClassification.entityType("prefixAdded"));
        assertEquals("RENAME", HistoryClassification.entityType("renameEntity"));
        assertEquals("IMPORT", HistoryClassification.entityType("addImport"));
        assertEquals("RULE", HistoryClassification.entityType("addSwrlRule"));
        assertEquals("DATATYPE", HistoryClassification.entityType("addDatatypeDefinition"));
        assertEquals("CLASS", HistoryClassification.entityType("addEquivalentClass"));
        assertEquals("PROPERTY", HistoryClassification.entityType("addPropertyDomain"));
        assertEquals("ANNOTATION", HistoryClassification.entityType("updateAnnotation"));
        assertEquals("OTHER", HistoryClassification.entityType(null));
    }

    @Test
    void targetBasedOperationsRecordTheirTargetAsTheValue() {
        MutationOp add = new MutationOp("addPropertyDomain", "http://ex/p", null, null, null, null, "http://ex/A", null, null,
                null, null, null, null, null, null);
        MutationOp remove = new MutationOp("deleteEquivalentClass", "http://ex/A", null, null, null, null, "http://ex/B", null,
                null, null, null, null, null, null, null);
        assertEquals("http://ex/A", HistoryClassification.newValue(add));
        assertNull(HistoryClassification.oldValue(add));
        assertEquals("http://ex/B", HistoryClassification.oldValue(remove));
        assertNull(HistoryClassification.newValue(remove));
    }
}
