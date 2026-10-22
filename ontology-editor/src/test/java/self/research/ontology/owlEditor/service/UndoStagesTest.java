package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UndoStagesTest {

    private static final String P = "proj";
    private static final String BEFORE = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"proj\",\"name\":\"old\"}";
    private static final String AFTER = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"proj\",\"name\":\"new\"}";

    private FakeUndoIntents fake;
    private HistorySyncService historySync;
    private RollbackActionRunner runner;
    private ChangeRollbackService service;

    @BeforeEach
    void setUp() {
        fake = new FakeUndoIntents();
        historySync = mock(HistorySyncService.class);
        runner = mock(RollbackActionRunner.class);
        when(runner.holds(anyString(), any(), anyBoolean(), any())).thenReturn(true);
        RollbackAuditRepository audits = mock(RollbackAuditRepository.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            audit.setId("audit-1");
            return audit;
        });
        service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), mock(RollbackGraphFacts.class),
                mock(OntologyMutationService.class), audits, mock(OntologyHistoryService.class),
                new ProjectWriteLockRegistry(), mock(DraftTrackingService.class), null);
        ReflectionTestUtils.setField(service, "actionRunner", runner);
        ReflectionTestUtils.setField(service, "operations", new UndoOperations(fake.service, mock(UndoReconciler.class)));
    }

    private HistoryChange rule(String id) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setProjectId(P);
        change.setUserId("u@x.com");
        change.setOperationType("updateFuzzyRule");
        change.setEntityIRI("urn:fuzzyrule:" + id);
        change.setEntityLabel("Rule");
        change.setDocumentStore("fuzzyRule");
        change.setDocumentId("6acf64ae94e5320b04bb880a");
        change.setDocumentBefore(BEFORE);
        change.setDocumentAfter(AFTER);
        when(historySync.getHistoryChange(id)).thenReturn(change);
        return change;
    }

    @Test
    void aChangeAssistantUndoIsATrackedOperationThatReachesTheLastStage() {
        rule("c1");

        Result result = service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com", "op-9"), false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        UndoIntentDocument intent = fake.only().orElseThrow();
        assertEquals("op-9", intent.getOpId());
        assertEquals(Status.COMMITTED, intent.getStatus());
        assertEquals(Stage.HISTORY_MARKED, intent.getStage());
        assertEquals(List.of("c1"), intent.getChangeIds());
        assertEquals("audit-1", intent.getAuditId());
    }

    @Test
    void aStepThatFailsLeavesTheOperationMarkedForRepairInsteadOfLost() {
        rule("c1");
        doThrow(new IllegalStateException("document store offline")).when(runner).run(anyString(), any(), anyBoolean(), any());

        Result result = service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com"), false);

        assertEquals(500, result.status());
        UndoIntentDocument intent = fake.only().orElseThrow();
        assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
        assertEquals(Stage.GRAPH_APPLIED, intent.getStage());
        assertEquals(List.of("c1"), intent.getChangeIds());
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void anEntryAnInterruptedOperationLeftHalfDoneIsNotUndoneAgainBlindly() {
        rule("c1");
        fake.seed(P, "op-0", ChangeRollbackService.UNDO, Status.NEEDS_ATTENTION, Stage.GRAPH_APPLIED, List.of("c1"), null, null);

        Result result = service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com"), false);

        assertTrue(result.skipped().stream().anyMatch(item -> item.reason().contains("needs repair")));
        verify(runner, never()).run(anyString(), any(), anyBoolean(), any());
    }

    @Test
    void aDryRunLeavesNoOperationRecord() {
        rule("c1");

        service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com"), true);

        assertTrue(fake.stored.isEmpty());
    }
}
