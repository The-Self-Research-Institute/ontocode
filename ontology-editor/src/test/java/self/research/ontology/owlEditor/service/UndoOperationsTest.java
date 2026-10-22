package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Request;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UndoOperationsTest {

    private static final String P = "proj";

    private FakeUndoIntents fake;
    private UndoReconciler reconciler;
    private UndoOperations operations;

    @BeforeEach
    void setUp() {
        fake = new FakeUndoIntents();
        reconciler = mock(UndoReconciler.class);
        operations = new UndoOperations(fake.service, reconciler);
    }

    private static Request request(String opId, String changeSetId, boolean dryRun) {
        return new Request(P, ChangeRollbackService.UNDO, ChangeRollbackService.CHANGESET, changeSetId, null, null,
                new Actor("u@x.com", "u@x.com", opId), dryRun, null, null);
    }

    private static Result ok(String auditId) {
        return new Result(200, true, false, ChangeRollbackService.UNDO, "cs", List.of(), List.of(), auditId, null);
    }

    private static HistoryChange entry(String id) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        return change;
    }

    @Test
    void aSuccessfulRequestIsRecordedWithItsEntriesAndAudit() {
        Result result = operations.around(request("op-1", "cs-1", false), () -> {
            UndoOperationContext.entries(List.of(entry("e1"), entry("e2")), null);
            UndoOperationContext.audit("audit-1");
            UndoOperationContext.stage(Stage.GRAPH_APPLIED);
            UndoOperationContext.stage(Stage.HISTORY_MARKED);
            return ok("audit-1");
        });

        assertTrue(result.ok());
        UndoIntentDocument intent = fake.only().orElseThrow();
        assertEquals(Status.COMMITTED, intent.getStatus());
        assertEquals(Stage.HISTORY_MARKED, intent.getStage());
        assertEquals(List.of("e1", "e2"), intent.getChangeIds());
        assertEquals("audit-1", intent.getAuditId());
        assertNotNull(intent.getScopeKey());
    }

    @Test
    void everyRequestIsRecordedEvenWithoutAnOperationId() {
        operations.around(request(null, "cs-1", false), () -> ok(null));

        assertNotNull(fake.only().orElseThrow().getOpId());
    }

    @Test
    void aDryRunLeavesNoRecord() {
        operations.around(request("op-1", "cs-1", true), () -> ok(null));

        assertTrue(fake.stored.isEmpty());
    }

    @Test
    void aRefusalBeforeAnythingChangedIsRecordedAsFailedAndSafeToRetry() {
        operations.around(request("op-1", "cs-1", false), () ->
                new Result(409, false, false, ChangeRollbackService.UNDO, "cs", List.of(), List.of(), null, "It was changed since this edit"));

        UndoIntentDocument intent = fake.only().orElseThrow();
        assertEquals(Status.FAILED, intent.getStatus());
        assertEquals("It was changed since this edit", intent.getFailureReason());
    }

    @Test
    void aFailureAfterTheGraphChangedNeedsAttention() {
        operations.around(request("op-1", "cs-1", false), () -> {
            UndoOperationContext.stage(Stage.GRAPH_APPLIED);
            return new Result(500, false, false, ChangeRollbackService.UNDO, "cs", List.of(), List.of(), null, "Stopped part-way");
        });

        assertEquals(Status.NEEDS_ATTENTION, fake.only().orElseThrow().getStatus());
    }

    @Test
    void anExceptionNeedsAttentionAndStillReachesTheCaller() {
        assertThrows(IllegalStateException.class, () -> operations.around(request("op-1", "cs-1", false), () -> {
            throw new IllegalStateException("store offline");
        }));

        UndoIntentDocument intent = fake.only().orElseThrow();
        assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
        assertTrue(intent.getFailureReason().contains("store offline"));
    }

    @Test
    void theContextIsClearedAfterEveryRequest() {
        assertThrows(IllegalStateException.class, () -> operations.around(request("op-1", "cs-1", false), () -> {
            throw new IllegalStateException("x");
        }));

        UndoOperationContext.stage(Stage.HISTORY_MARKED);
        assertFalse(UndoOperationContext.blocked("e1"));
        assertEquals(Stage.STARTED, fake.only().orElseThrow().getStage());
    }

    @Test
    void aRepeatedIdWithTheSameRequestReturnsTheEarlierOutcome() {
        operations.around(request("op-1", "cs-1", false), () -> ok("audit-1"));
        boolean[] ran = new boolean[1];

        Result again = operations.around(request("op-1", "cs-1", false), () -> {
            ran[0] = true;
            return ok("audit-2");
        });

        assertFalse(ran[0]);
        assertTrue(again.ok());
        assertEquals("audit-1", again.auditId());
    }

    @Test
    void aRepeatedIdForADifferentRequestIsRefused() {
        operations.around(request("op-1", "cs-1", false), () -> ok("audit-1"));

        Result other = operations.around(request("op-1", "cs-2", false), () -> ok("audit-2"));

        assertEquals(409, other.status());
        assertTrue(other.message().contains("different request"));
    }

    @Test
    void aRepeatedIdByAnotherUserIsRefused() {
        operations.around(request("op-1", "cs-1", false), () -> ok("audit-1"));
        Request stranger = new Request(P, ChangeRollbackService.UNDO, ChangeRollbackService.CHANGESET, "cs-1", null, null,
                new Actor("other@x.com", "other@x.com", "op-1"), false, null, null);

        assertEquals(409, operations.around(stranger, () -> ok("audit-2")).status());
    }

    @Test
    void aRepeatedIdWhileTheFirstIsStillRunningIsToldSo() {
        fake.seed(P, "op-1", ChangeRollbackService.UNDO, Status.PENDING, Stage.STARTED, List.of(), null, null);

        Result again = operations.around(request("op-1", "cs-1", false), () -> ok("audit-2"));

        assertEquals(409, again.status());
        assertTrue(again.message().contains("still running"));
    }

    @Test
    void aRepeatedIdThatNeedsAttentionExplainsWhy() {
        UndoIntentDocument earlier = fake.seed(P, "op-1", ChangeRollbackService.UNDO, Status.NEEDS_ATTENTION, Stage.GRAPH_APPLIED,
                List.of("e1"), null, null);
        earlier.setFailureReason("It stopped part-way");
        earlier.setScopeKey(null);

        Result again = operations.around(request("op-1", "cs-1", false), () -> ok("audit-2"));

        assertEquals(409, again.status());
        assertEquals("It stopped part-way", again.message());
    }

    @Test
    void anEarlierUnfinishedRequestIsCheckedBeforeTheNewOneRuns() {
        operations.around(request("op-1", "cs-1", false), () -> ok("audit-1"));

        verify(reconciler).resolveLocked(P);
    }

    @Test
    void anUnreadableEarlierRecordNeverBlocksANewUndo() {
        when(reconciler.resolveLocked(P)).thenThrow(new IllegalStateException("no converter for this record"));

        Result result = operations.around(request("op-1", "cs-1", false), () -> ok("audit-1"));

        assertTrue(result.ok());
        assertEquals(Status.COMMITTED, fake.only().orElseThrow().getStatus());
    }

    @Test
    void entriesOfAnInterruptedRequestAreBlockedForTheNextOne() {
        fake.seed(P, "op-0", ChangeRollbackService.UNDO, Status.NEEDS_ATTENTION, Stage.GRAPH_APPLIED, List.of("e1"), null, null);
        when(reconciler.resolveLocked(P)).thenReturn(0);
        boolean[] blocked = new boolean[2];

        operations.around(request("op-1", "cs-1", false), () -> {
            blocked[0] = UndoOperationContext.blocked("e1");
            blocked[1] = UndoOperationContext.blocked("e2");
            return ok("audit-1");
        });

        assertTrue(blocked[0]);
        assertFalse(blocked[1]);
    }
}
