package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Kind;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackMutationPlannerTest {

    private static final String E = "http://example.org/Pizzanew";
    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String DISJOINT = "http://www.w3.org/2002/07/owl#disjointWith";

    private final RollbackMutationPlanner planner = new RollbackMutationPlanner();

    static HistoryChange.SubChange sub(String id, String predicate, String oldValue, String newValue, boolean addition) {
        HistoryChange.SubChange sc = new HistoryChange.SubChange(predicate, oldValue, newValue,
                LABEL.equals(predicate) ? predicate : null, addition);
        sc.setId(id);
        return sc;
    }

    static HistoryChange entry(String op, HistoryChange.SubChange... subs) {
        HistoryChange change = new HistoryChange.Builder("p", "e-" + op, "u@x.com", "u@x.com")
                .operationType(op).entityIRI(E).entityLabel("Pizzanew")
                .subChanges(new ArrayList<>(List.of(subs))).build();
        change.setId("c-" + op);
        return change;
    }

    private static List<String> types(Plan plan) {
        return plan.mutations().stream().map(MutationOp::type).toList();
    }

    @Test
    void classifiesEntries() {
        assertEquals(Kind.CREATE, RollbackMutationPlanner.kindOf(entry("createClass")));
        assertEquals(Kind.DELETE, RollbackMutationPlanner.kindOf(entry("deleteObjectProperty")));
        assertEquals(Kind.GROUPED_MODIFY, RollbackMutationPlanner.kindOf(entry("addStatement", sub("s", LABEL, null, "x", true))));
        assertEquals(Kind.SINGLE, RollbackMutationPlanner.kindOf(entry("addAnnotation")));
    }

    @Test
    void undoingACreatedClassDeletesItAndCoversEveryActiveSubChange() {
        HistoryChange change = entry("createClass", sub("a", LABEL, null, "Pizzanew", true),
                sub("b", DISJOINT, null, "http://example.org/Other", true));
        Plan plan = planner.planUndo(change, RollbackMutationPlanner.active(change));
        assertEquals(List.of("deleteClass"), types(plan));
        assertEquals(java.util.Set.of("a", "b"), plan.handledSubChangeIds());
    }

    @Test
    void activeLeavesOutSubChangesThatWereAlreadyUndone() {
        HistoryChange.SubChange undone = sub("b", DISJOINT, null, "http://example.org/Other", true);
        undone.setReverted(true);
        HistoryChange change = entry("addStatement", sub("a", LABEL, null, "x", true), undone);
        Plan plan = planner.planUndo(change, RollbackMutationPlanner.active(change));
        assertEquals(List.of("deleteAnnotation"), types(plan));
        assertTrue(plan.rawUpdates().isEmpty());
        assertEquals(java.util.Set.of("a"), plan.handledSubChangeIds());
    }

    @Test
    void undoingADeletionRecreatesTheEntityUnderItsOldParentAndRestoresTheRest() {
        HistoryChange change = entry("deleteClass",
                sub("parent", RollbackMutationPlanner.RDFS_SUBCLASSOF, "http://example.org/Food", null, false),
                sub("label", LABEL, "Pizzanew", null, false));
        Plan plan = planner.planUndo(change, RollbackMutationPlanner.active(change));
        assertEquals(List.of("createClass", "addAnnotation"), types(plan));
        assertEquals("http://example.org/Food", plan.mutations().get(0).parent());
        assertEquals(java.util.Set.of("parent", "label"), plan.handledSubChangeIds());
    }

    @Test
    void genericPredicatesUseRawUpdatesForIriAndLiteralValues() {
        HistoryChange change = entry("addStatement",
                sub("iri", DISJOINT, "http://example.org/Other", null, false),
                sub("lit", "http://example.org/note", "some \"quoted\" text", null, false),
                sub("del", DISJOINT, null, "http://example.org/Third", true));
        Plan plan = planner.planUndo(change, RollbackMutationPlanner.active(change));
        assertEquals(3, plan.rawUpdates().size());
        assertTrue(plan.rawUpdates().get(0).startsWith("INSERT DATA"));
        assertEquals("INSERT DATA { <" + E + "> <http://example.org/note> \"some \\\"quoted\\\" text\" }",
                plan.rawUpdates().get(1));
        assertTrue(plan.rawUpdates().get(2).startsWith("DELETE {"));
        assertTrue(plan.unsupported().isEmpty());
        assertEquals(java.util.Set.of("iri", "lit", "del"), plan.handledSubChangeIds());
    }

    @Test
    void redoingACreatedClassRecreatesItWithItsParentAndReappliesTheRest() {
        HistoryChange change = entry("createClass",
                sub("parent", RollbackMutationPlanner.RDFS_SUBCLASSOF, null, "http://example.org/Food", true),
                sub("label", LABEL, null, "Pizzanew", true));
        Plan plan = planner.planRedo(change, change.getSubChanges());
        assertEquals(List.of("createClass", "addAnnotation"), types(plan));
        assertEquals("http://example.org/Food", plan.mutations().get(0).parent());
        assertEquals(java.util.Set.of("parent", "label"), plan.handledSubChangeIds());
    }

    @Test
    void singleLabelChangesSwapOldAndNewForUndoAndRedo() {
        HistoryChange change = new HistoryChange.Builder("p", "e", "u", "u").operationType("updateLabel")
                .entityIRI(E).oldValue("Old").newValue("New").build();
        MutationOp undo = planner.planUndo(change, List.of()).mutations().get(0);
        MutationOp redo = planner.planRedo(change, List.of()).mutations().get(0);
        assertEquals("updateAnnotation", undo.type());
        assertEquals("Old", undo.value());
        assertEquals("New", undo.oldValue());
        assertEquals("New", redo.value());
        assertEquals("Old", redo.oldValue());
    }

    @Test
    void singleSubClassOfEntriesBecomeRawUpdatesInsteadOfImmediateWrites() {
        HistoryChange change = new HistoryChange.Builder("p", "e", "u", "u").operationType("addSubClassOf")
                .entityIRI(E).newValue("http://example.org/Food").build();
        Plan plan = planner.planUndo(change, List.of());
        assertTrue(plan.mutations().isEmpty());
        assertEquals(1, plan.rawUpdates().size());
        assertTrue(plan.rawUpdates().get(0).contains("DELETE DATA"));
    }

    @Test
    void escapesQuotesInGenericDeletes() {
        HistoryChange change = entry("addStatement", sub("q", "http://example.org/note", null, "say \"hi\"", true));
        Plan plan = planner.planUndo(change, RollbackMutationPlanner.active(change));
        assertTrue(plan.rawUpdates().get(0).contains("\"say \\\"hi\\\"\""));
    }
}
