package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UndoReconcilerTest {

    private static final String P = "proj";
    private static final String SUB = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String BEFORE_DOC = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"proj\",\"expression\":\"old\"}";
    private static final String AFTER_DOC = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"proj\",\"expression\":\"new\"}";

    private FakeUndoIntents fake;
    private HistorySyncService historySync;
    private RollbackGraphFacts facts;
    private OntologyMutationService mutations;
    private RollbackActionRunner actions;
    private UndoReconciler reconciler;

    @BeforeEach
    void setUp() {
        fake = new FakeUndoIntents();
        historySync = mock(HistorySyncService.class);
        facts = mock(RollbackGraphFacts.class);
        mutations = mock(OntologyMutationService.class);
        actions = mock(RollbackActionRunner.class);
        reconciler = new UndoReconciler(fake.service, historySync, new RollbackMutationPlanner(), facts, mutations, actions,
                new ProjectWriteLockRegistry());
    }

    private HistoryChange edit(String id, boolean withDocument) {
        HistoryChange change = new HistoryChange.Builder(P, "edit-" + id, "u@x.com", "u@x.com")
                .operationType("addSubClassOf").entityIRI("http://ex/A").entityLabel("A").build();
        change.setId(id);
        change.setExactInserted("<http://ex/A> <" + SUB + "> <http://ex/B> .\n");
        if (withDocument) {
            change.setDocumentStore("datatypeDefinition");
            change.setDocumentId("6acf64ae94e5320b04bb880a");
            change.setDocumentBefore(BEFORE_DOC);
            change.setDocumentAfter(AFTER_DOC);
        }
        when(historySync.getHistoryChange(id)).thenReturn(change);
        return change;
    }

    private UndoIntentDocument undoIntent(Stage stage, String... ids) {
        return fake.seed(P, "op-1", ChangeRollbackService.UNDO, Status.PENDING, stage, List.of(ids), "audit-1", null);
    }

    private void graphIs(boolean beforeEdit) {
        when(facts.deltaHolds(eq(P), any(HistoryChange.class), eq(true))).thenReturn(!beforeEdit);
        when(facts.deltaHolds(eq(P), any(HistoryChange.class), eq(false))).thenReturn(beforeEdit);
    }

    @Test
    void anUndoThatChangedTheGraphButNotTheHistoryIsFinished() {
        HistoryChange entry = edit("e1", false);
        UndoIntentDocument intent = undoIntent(Stage.GRAPH_APPLIED, "e1");
        graphIs(true);

        reconciler.resolveLocked(P);

        assertEquals(Status.COMMITTED, intent.getStatus());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(historySync).setEntryReverted("e1", true, "audit-1");
    }

    @Test
    void anUndoThatNeverChangedAnythingIsAbandonedWithoutBeingReplayed() {
        edit("e1", false);
        UndoIntentDocument intent = undoIntent(Stage.STARTED, "e1");
        graphIs(false);

        reconciler.resolveLocked(P);

        assertEquals(Status.FAILED, intent.getStatus());
        assertTrue(intent.getFailureReason().contains("safe to try again"));
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void aMissedDocumentStepIsRolledForward() {
        HistoryChange entry = edit("e1", true);
        UndoIntentDocument intent = undoIntent(Stage.GRAPH_APPLIED, "e1");
        graphIs(true);
        RollbackAction undoDocument = RollbackAction.restoreDocument("datatypeDefinition", "6acf64ae94e5320b04bb880a", BEFORE_DOC, AFTER_DOC);
        RollbackAction targetCheck = RollbackAction.restoreDocument("datatypeDefinition", "6acf64ae94e5320b04bb880a", BEFORE_DOC, BEFORE_DOC);
        when(actions.holds(eq(P), eq(targetCheck), anyBoolean(), any())).thenReturn(false);
        when(actions.holds(eq(P), eq(undoDocument), anyBoolean(), any())).thenReturn(true);

        reconciler.resolveLocked(P);

        verify(actions).run(eq(P), eq(undoDocument), anyBoolean(), any());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(historySync).setEntryReverted("e1", true, "audit-1");
        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    @Test
    void aMissedGraphStepIsRolledForwardWhenTheDocumentAlreadyChanged() {
        edit("e1", true);
        UndoIntentDocument intent = undoIntent(Stage.DOCUMENTS_APPLIED, "e1");
        graphIs(false);
        RollbackAction undoDocument = RollbackAction.restoreDocument("datatypeDefinition", "6acf64ae94e5320b04bb880a", BEFORE_DOC, AFTER_DOC);
        RollbackAction targetCheck = RollbackAction.restoreDocument("datatypeDefinition", "6acf64ae94e5320b04bb880a", BEFORE_DOC, BEFORE_DOC);
        when(actions.holds(eq(P), eq(targetCheck), anyBoolean(), any())).thenReturn(true);
        when(facts.deltaHolds(eq(P), any(HistoryChange.class), eq(true))).thenReturn(true);
        when(facts.deltaHolds(eq(P), any(HistoryChange.class), eq(false))).thenReturn(false);

        reconciler.resolveLocked(P);

        verify(mutations).applyRawUpdate(eq(P), anyString(), anyBoolean(), any());
        verify(actions, never()).run(eq(P), eq(undoDocument), anyBoolean(), any());
        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    @Test
    void anOntologyThatMatchesNeitherStateNeedsAttentionAndIsLeftAlone() {
        edit("e1", false);
        UndoIntentDocument intent = undoIntent(Stage.GRAPH_APPLIED, "e1");
        when(facts.deltaHolds(eq(P), any(HistoryChange.class), anyBoolean())).thenReturn(false);

        reconciler.resolveLocked(P);

        assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void aKindOfChangeThatCannotBeCheckedNeedsAttentionOnceItStartedAndFailsCleanlyBefore() {
        HistoryChange legacy = new HistoryChange.Builder(P, "edit-l", "u@x.com", "u@x.com")
                .operationType("createClass").entityIRI("http://ex/N").entityLabel("N").build();
        legacy.setId("l1");
        when(historySync.getHistoryChange("l1")).thenReturn(legacy);
        UndoIntentDocument started = undoIntent(Stage.GRAPH_APPLIED, "l1");
        UndoIntentDocument notStarted = fake.seed(P, "op-2", ChangeRollbackService.UNDO, Status.PENDING, Stage.STARTED,
                List.of("l1"), null, null);

        reconciler.resolveLocked(P);

        assertEquals(Status.NEEDS_ATTENTION, started.getStatus());
        assertEquals(Status.FAILED, notStarted.getStatus());
    }

    @Test
    void anOperationWhoseEntriesAreGoneIsClosed() {
        UndoIntentDocument intent = undoIntent(Stage.STARTED, "missing");

        reconciler.resolveLocked(P);

        assertEquals(Status.FAILED, intent.getStatus());
    }

    @Test
    void resolvedOperationsAreNotLookedAtAgain() {
        edit("e1", false);
        UndoIntentDocument intent = undoIntent(Stage.GRAPH_APPLIED, "e1");
        graphIs(true);
        reconciler.resolveLocked(P);

        assertEquals(0, reconciler.resolveLocked(P));
        assertEquals(Set.of(), fake.service.entriesNeedingAttention(P));
        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    @Test
    void anInterruptedRequestIsReconciledWhenTheServerStarts() {
        edit("e1", false);
        UndoIntentDocument intent = undoIntent(Stage.GRAPH_APPLIED, "e1");
        graphIs(true);

        reconciler.reconcileOnStartup();

        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    @Test
    void aRedoRollsTheEditBackInWhenOnlyPartOfItWasApplied() {
        edit("e1", true);
        UndoIntentDocument redo = fake.seed(P, "op-3", ChangeRollbackService.REDO, Status.PENDING, Stage.GRAPH_APPLIED,
                List.of("e1"), null, "audit-0");
        graphIs(true);
        RollbackAction redoDocument = RollbackAction.restoreDocument("datatypeDefinition", "6acf64ae94e5320b04bb880a", AFTER_DOC, BEFORE_DOC);
        RollbackAction targetCheck = RollbackAction.restoreDocument("datatypeDefinition", "6acf64ae94e5320b04bb880a", AFTER_DOC, AFTER_DOC);
        when(actions.holds(eq(P), eq(targetCheck), anyBoolean(), any())).thenReturn(true);

        reconciler.resolveLocked(P);

        verify(mutations).applyRawUpdate(eq(P), anyString(), anyBoolean(), any());
        verify(actions, never()).run(eq(P), eq(redoDocument), anyBoolean(), any());
        verify(historySync).setEntryReverted("e1", false, null);
        assertEquals(Status.COMMITTED, redo.getStatus());
    }
}
