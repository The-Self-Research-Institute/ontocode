package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.HistorySyncService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeSetControllerTest {

    private static final Actor ACTOR = new Actor("u@x.com", "u@x.com");

    private ChangeRollbackService rollback;
    private HistorySyncService history;
    private RollbackRequestSupport support;
    private HttpServletRequest request;
    private ChangeSetController controller;

    @BeforeEach
    void setUp() {
        rollback = mock(ChangeRollbackService.class);
        history = mock(HistorySyncService.class);
        support = mock(RollbackRequestSupport.class);
        request = mock(HttpServletRequest.class);
        controller = new ChangeSetController(rollback, history, support);
        when(support.actor(any(), any())).thenReturn(ACTOR);
    }

    private static ChangeRollbackService.Result ok(String scope) {
        return new ChangeRollbackService.Result(200, true, false, "UNDO", scope, List.of(), List.of(), "audit-1", null);
    }

    @Test
    void unknownChangeSetIsNotFoundAndNothingRuns() {
        when(history.getChangeSet("p", "missing")).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.undo("p", "missing", false, null, request);

        assertEquals(404, response.getStatusCode().value());
        verify(rollback, never()).undoChangeSet(any(), any(), any(), anyBoolean());
    }

    @Test
    void aDeniedCallerGetsTheSupportResponseAndNothingRuns() {
        List<HistoryChange> entries = List.of(new HistoryChange());
        ResponseEntity<Map<String, Object>> forbidden = ResponseEntity.status(403).body(Map.of("success", false));
        when(history.getChangeSet("p", "set-1")).thenReturn(entries);
        when(support.denyIfNotAllowed(entries, "p", request)).thenReturn(forbidden);

        ResponseEntity<Map<String, Object>> response = controller.undo("p", "set-1", false, null, request);

        assertSame(forbidden, response);
        verify(rollback, never()).undoChangeSet(any(), any(), any(), anyBoolean());
    }

    @Test
    void undoRunsForTheActorAndReportsTheChangeSet() {
        List<HistoryChange> entries = List.of(new HistoryChange());
        when(history.getChangeSet("p", "set-1")).thenReturn(entries);
        when(rollback.undoChangeSet("p", "set-1", ACTOR, false)).thenReturn(ok("set-1"));

        ResponseEntity<Map<String, Object>> response = controller.undo("p", "set-1", false, null, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("set-1", response.getBody().get("changeSetId"));
    }

    @Test
    void redoAndDryRunAreForwarded() {
        when(history.getChangeSet("p", "set-1")).thenReturn(List.of(new HistoryChange()));
        when(rollback.redoChangeSet("p", "set-1", ACTOR, true)).thenReturn(ok("set-1"));

        controller.redo("p", "set-1", true, null, request);

        verify(rollback).redoChangeSet("p", "set-1", ACTOR, true);
        verify(rollback, never()).undoChangeSet(any(), any(), any(), anyBoolean());
    }

    @Test
    void redoingAnUnknownUndoIsNotFound() {
        when(rollback.entriesForUndo("p", "nope")).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.redoUndo("p", "nope", false, null, request);

        assertEquals(404, response.getStatusCode().value());
        verify(rollback, never()).redoUndo(any(), any(), any(), anyBoolean());
    }

    @Test
    void redoingAnUndoReturnsItsAuditId() {
        List<HistoryChange> entries = List.of(new HistoryChange());
        when(rollback.entriesForUndo("p", "audit-1")).thenReturn(entries);
        when(rollback.redoUndo(eq("p"), eq("audit-1"), eq(ACTOR), eq(false))).thenReturn(ok("set-1"));

        ResponseEntity<Map<String, Object>> response = controller.redoUndo("p", "audit-1", false, null, request);

        assertEquals("audit-1", response.getBody().get("auditId"));
    }
}
