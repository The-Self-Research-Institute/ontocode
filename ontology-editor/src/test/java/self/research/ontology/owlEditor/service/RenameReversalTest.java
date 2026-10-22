package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenameReversalTest {

    private static final String OLD = "http://x/Old";
    private static final String NEW = "http://x/New";

    private static HistoryChange rename() {
        HistoryChange change = new HistoryChange();
        change.setId("c1");
        change.setOperationType("renameEntity");
        change.setEntityIRI(NEW);
        change.setOldValue(OLD);
        change.setNewValue(NEW);
        return change;
    }

    @Test
    void renamesAreTheirOwnKind() {
        assertEquals(RollbackMutationPlanner.Kind.RENAME, RollbackMutationPlanner.kindOf(rename()));
    }

    @Test
    void undoRenamesBackToTheOldIri() {
        RollbackMutationPlanner.Plan plan = new RollbackMutationPlanner().planUndo(rename(), List.of());
        assertEquals(1, plan.rawUpdates().size());
        String update = plan.rawUpdates().get(0);
        assertTrue(update.contains("DELETE { <" + NEW + "> ?p ?o } INSERT { <" + OLD + "> ?p ?o }"), update);
        assertTrue(update.contains("DELETE { ?s ?p <" + NEW + "> } INSERT { ?s ?p <" + OLD + "> }"), update);
        assertTrue(update.contains("DELETE { ?s <" + NEW + "> ?o } INSERT { ?s <" + OLD + "> ?o }"), update);
        assertTrue(plan.unsupported().isEmpty());
    }

    @Test
    void redoRenamesForwardAgain() {
        RollbackMutationPlanner.Plan plan = new RollbackMutationPlanner().planRedo(rename(), List.of());
        assertTrue(plan.rawUpdates().get(0).contains("INSERT { <" + NEW + "> ?p ?o }"));
        assertTrue(plan.unsupported().isEmpty());
    }

    @Test
    void aRenameWithoutBothNamesCannotBeReversed() {
        HistoryChange change = rename();
        change.setOldValue(null);
        RollbackMutationPlanner.Plan plan = new RollbackMutationPlanner().planUndo(change, List.of());
        assertTrue(plan.isEmpty());
        assertEquals(1, plan.unsupported().size());
    }
}
