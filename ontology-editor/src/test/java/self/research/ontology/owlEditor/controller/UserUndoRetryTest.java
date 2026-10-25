package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.HistorySyncService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.UndoIntentService;
import self.research.ontology.owlEditor.service.UndoOperations;
import self.research.ontology.owlEditor.service.UndoPreconditions;
import self.research.ontology.owlEditor.service.UndoReconciler;
import self.research.ontology.owlEditor.service.UserUndoService;
import self.research.ontology.owlEditor.service.UserUndoService.Next;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserUndoRetryTest {

    private static final String P = "proj";
    private static final String EMAIL = "real@x.org";

    private final Map<String, UndoIntentDocument> intentsByOp = new ConcurrentHashMap<>();
    private final AtomicBoolean mergeUndone = new AtomicBoolean(false);
    private UserUndoService undoService;
    private ChangeRollbackService rollback;
    private UndoPreconditions preconditions;
    private UserUndoController controller;

    @BeforeEach
    void setUp() {
        undoService = mock(UserUndoService.class);
        rollback = mock(ChangeRollbackService.class);
        preconditions = mock(UndoPreconditions.class);
        HistorySyncService historySync = mock(HistorySyncService.class);
        UndoIntentService intents = mock(UndoIntentService.class);
        when(intents.existing(eq(P), any())).thenAnswer(inv -> Optional.ofNullable(
                inv.getArgument(1) == null ? null : intentsByOp.get((String) inv.getArgument(1))));

        when(undoService.nextUndo(eq(P), anyBoolean(), any())).thenAnswer(inv -> mergeUndone.get()
                ? next("c0", "Created class Pizza") : next("m1", "Ontology merge"));
        when(historySync.getHistoryChange(anyString())).thenAnswer(inv -> copyOf(inv.getArgument(0)));
        when(preconditions.violation(eq(P), anyList(), any())).thenAnswer(inv -> {
            HistoryChange entry = ((List<HistoryChange>) inv.getArgument(1)).get(0);
            if (!"m1".equals(entry.getId())) {
                return Optional.empty();
            }
            if (entry.isReverted()) {
                return Optional.of("Ontology merge was already undone");
            }
            return mergeUndone.get() ? Optional.of("Ontology merge was changed since this edit") : Optional.empty();
        });

        RollbackRequestSupport support = new RollbackRequestSupport(mock(WorkspaceOwnershipService.class));
        controller = new UserUndoController(undoService, rollback, historySync, support, preconditions,
                new ProjectWriteLockRegistry());
        controller.setOperations(new UndoOperations(intents, mock(UndoReconciler.class)));
    }

    private static Next next(String changeId, String label) {
        return new Next(true, ChangeRollbackService.UNDO, changeId, null, null, null, label, null, List.of());
    }

    private HistoryChange copyOf(String id) {
        HistoryChange entry = new HistoryChange.Builder(P, "edit-" + id, "mongo-1", EMAIL).operationType("ontologyMerge")
                .entityIRI("urn:ontocode:merge:" + id).build();
        entry.setId(id);
        entry.setDraft(true);
        entry.setReverted("m1".equals(id) && mergeUndone.get());
        return entry;
    }

    private static MockHttpServletRequest signedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + EMAIL + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    private void remember(String opId, String direction, Status status) {
        UndoIntentDocument intent = UndoIntentDocument.builder().projectId(P).opId(opId).direction(direction)
                .actor(EMAIL).status(status).auditId(status == Status.COMMITTED ? "audit-1" : null).build();
        intentsByOp.put(opId, intent);
    }

    private ResponseEntity<Map<String, Object>> undo(String opId) {
        return controller.undo(P, true, "m1", opId, null, signedIn());
    }

    @Test
    void aRetryOfAnUndoThatAlreadyFinishedGetsTheSameSuccessAndUndoesNothingMore() {
        remember("op-1", ChangeRollbackService.UNDO, Status.COMMITTED);
        mergeUndone.set(true);

        ResponseEntity<Map<String, Object>> response = undo("op-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertEquals("audit-1", response.getBody().get("auditId"));
        verify(preconditions, never()).violation(anyString(), anyList(), any());
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void aRetryWhileTheFirstUndoIsStillRunningSaysItIsStillRunning() {
        remember("op-1", ChangeRollbackService.UNDO, Status.PENDING);

        ResponseEntity<Map<String, Object>> response = undo("op-1");

        assertEquals(409, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("still running"));
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void aRetryThatWaitedBehindTheFirstUndoGetsItsOutcomeInsteadOfAFalseConflict() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(rollback.undoEntry(eq(P), eq("m1"), any(), eq(false))).thenAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            mergeUndone.set(true);
            ChangeRollbackService.Actor actor = inv.getArgument(2);
            remember(actor.opId(), ChangeRollbackService.UNDO, Status.COMMITTED);
            return new Result(200, true, false, ChangeRollbackService.UNDO, "m1", List.of(), List.of(), "audit-1", null);
        });
        AtomicReference<ResponseEntity<Map<String, Object>>> first = new AtomicReference<>();
        AtomicReference<ResponseEntity<Map<String, Object>>> retry = new AtomicReference<>();

        Thread original = new Thread(() -> first.set(undo("op-1")));
        original.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Thread repeated = new Thread(() -> retry.set(undo("op-1")));
        repeated.start();
        Thread.sleep(300);
        release.countDown();
        original.join(10_000);
        repeated.join(10_000);

        assertEquals(200, first.get().getStatusCode().value());
        assertEquals(200, retry.get().getStatusCode().value(), String.valueOf(retry.get().getBody()));
        assertEquals(true, retry.get().getBody().get("success"));
        verify(rollback, times(1)).undoEntry(eq(P), eq("m1"), any(), eq(false));
    }

    @Test
    void aRetryOfARedoThatAlreadyFinishedDoesNotRedoAgain() {
        remember("op-2", ChangeRollbackService.REDO, Status.COMMITTED);

        ResponseEntity<Map<String, Object>> response = controller.redo(P, true, "audit-0", "op-2", null, signedIn());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        verify(rollback, never()).redoUndo(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void anOperationIdUsedBySomeoneElseIsRefusedAndNothingIsUndone() {
        UndoIntentDocument intent = UndoIntentDocument.builder().projectId(P).opId("op-3")
                .direction(ChangeRollbackService.UNDO).actor("other@x.org").status(Status.COMMITTED).build();
        intentsByOp.put("op-3", intent);

        ResponseEntity<Map<String, Object>> response = undo("op-3");

        assertEquals(409, response.getStatusCode().value());
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
    }
}
