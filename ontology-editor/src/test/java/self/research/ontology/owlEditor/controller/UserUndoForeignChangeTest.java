package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Item;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.HistorySyncService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.UndoPreconditions;
import self.research.ontology.owlEditor.service.UserUndoService;
import self.research.ontology.owlEditor.service.UserUndoService.Next;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserUndoForeignChangeTest {

    private static final String P = "proj";
    private static final String EMAIL = "owner@x.org";

    private UserUndoService undoService;
    private ChangeRollbackService rollback;
    private HistorySyncService historySync;
    private RollbackRequestSupport support;
    private UserUndoController controller;

    @BeforeEach
    void setUp() {
        undoService = mock(UserUndoService.class);
        rollback = mock(ChangeRollbackService.class);
        historySync = mock(HistorySyncService.class);
        support = mock(RollbackRequestSupport.class);
        UndoPreconditions preconditions = mock(UndoPreconditions.class);
        when(preconditions.violation(eq(P), anyList(), any())).thenReturn(Optional.empty());
        when(support.denyIfNotAllowed(anyList(), eq(P), any())).thenReturn(null);
        when(rollback.undoEntry(eq(P), eq("t1"), any(), eq(false))).thenReturn(
                new Result(200, true, false, "UNDO", "t1", List.of(new Item("t1", null, null, null, null, null)), List.of(), "a1", null));
        controller = new UserUndoController(undoService, rollback, historySync, support, preconditions, new ProjectWriteLockRegistry());
    }

    private void newestChangeBy(String userId, String username) {
        when(undoService.nextUndo(eq(P), anyBoolean(), any())).thenReturn(
                new Next(true, ChangeRollbackService.UNDO, "t1", null, null, null, "Created class Pizza", null, List.of()));
        HistoryChange change = new HistoryChange.Builder(P, "edit-t1", userId, username).operationType("createClass")
                .entityIRI("http://x#Pizza").build();
        change.setId("t1");
        when(historySync.getHistoryChange("t1")).thenReturn(change);
    }

    private static MockHttpServletRequest signedIn(boolean confirmOther) {
        return signedIn(confirmOther, false);
    }

    private static MockHttpServletRequest signedIn(boolean confirmOther, boolean confirmTarget) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + EMAIL + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        if (confirmOther) {
            request.setParameter("confirmOther", "true");
        }
        if (confirmTarget) {
            request.setParameter("confirmTarget", "true");
        }
        return request;
    }

    @Test
    @SuppressWarnings("unchecked")
    void someoneElsesNewestChangeAsksForConfirmationAndChangesNothing() {
        newestChangeBy("sam@x.org", "Sam");

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op1", null, signedIn(false));

        assertEquals(409, response.getStatusCode().value());
        Map<String, Object> foreign = (Map<String, Object>) response.getBody().get("foreignTarget");
        assertNotNull(foreign);
        assertEquals("Sam", foreign.get("username"));
        assertTrue(String.valueOf(foreign.get("label")).contains("Pizza"));
        assertTrue(((List<?>) response.getBody().get("othersOnTop")).size() == 1);
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void afterConfirmationSomeoneElsesChangeIsUndone() {
        newestChangeBy("sam@x.org", "Sam");

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op2", null, signedIn(true));

        assertEquals(200, response.getStatusCode().value());
        verify(rollback).undoEntry(eq(P), eq("t1"), any(), eq(false));
    }

    @Test
    void confirmingOnlyTheTargetUndoesItAndDoesNotApproveTheChangesOnTop() {
        newestChangeBy("sam@x.org", "Sam");
        UndoPreconditions blocked = mock(UndoPreconditions.class);
        when(blocked.violation(eq(P), anyList(), any())).thenReturn(Optional.of("Other people's changes are on top of this one"));
        when(support.denyIfNotAllowed(anyList(), eq(P), any())).thenReturn(null);
        controller = new UserUndoController(undoService, rollback, historySync, support, blocked, new ProjectWriteLockRegistry());

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op5", null, signedIn(false, true));

        assertEquals(409, response.getStatusCode().value());
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void theTargetConfirmationUndoesTheTargetWhenNothingIsOnTop() {
        newestChangeBy("sam@x.org", "Sam");

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op6", null, signedIn(false, true));

        assertEquals(200, response.getStatusCode().value());
        verify(rollback).undoEntry(eq(P), eq("t1"), any(), eq(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConfirmationTellsHowManyChangesTheEditHolds() {
        undoService = mock(UserUndoService.class);
        when(undoService.nextUndo(eq(P), anyBoolean(), any())).thenReturn(
                new Next(true, ChangeRollbackService.UNDO, "t1", "set-1", null, null, "Created class Pizza", null, List.of()));
        HistoryChange change = new HistoryChange.Builder(P, "edit-t1", "sam@x.org", "Sam").operationType("createClass")
                .entityIRI("http://x#Pizza").build();
        change.setId("t1");
        change.setChangeSetId("set-1");
        when(historySync.getHistoryChange("t1")).thenReturn(change);
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(change, change, change));
        UndoPreconditions ok = mock(UndoPreconditions.class);
        when(ok.violation(eq(P), anyList(), any())).thenReturn(Optional.empty());
        controller = new UserUndoController(undoService, rollback, historySync, support, ok, new ProjectWriteLockRegistry());

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op7", null, signedIn(false));

        Map<String, Object> foreign = (Map<String, Object>) response.getBody().get("foreignTarget");
        assertEquals(3, foreign.get("changes"));
    }

    @Test
    void aViewerPressingUndoOnAMergedPrGetsNoPrBoxOnlyTheRefusal() {
        HistoryChange merged = new HistoryChange.Builder(P, "edit-pr", "owner@x.org", "Owner").operationType("prPublished")
                .entityIRI("urn:ontocode:pr:pr1").entityLabel("Merged PR: Add toppings").build();
        merged.setId("t1");
        when(undoService.nextUndo(eq(P), anyBoolean(), any())).thenReturn(
                new Next(true, ChangeRollbackService.UNDO, "t1", null, null, "urn:ontocode:pr:pr1", "Merged PR", null, List.of()));
        when(historySync.getHistoryChange("t1")).thenReturn(merged);
        ResponseEntity<Map<String, Object>> forbidden = ResponseEntity.status(403).body(Map.of("success", false));
        when(support.denyIfNotAllowed(anyList(), eq(P), any())).thenReturn(forbidden);

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op8", null, signedIn(false));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void theUsersOwnChangeNeedsNoConfirmation() {
        newestChangeBy(EMAIL, "Owner");

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op3", null, signedIn(false));

        assertEquals(200, response.getStatusCode().value());
        verify(rollback).undoEntry(eq(P), eq("t1"), any(), eq(false));
    }

    @Test
    void aViewerIsRefusedBeforeBeingAskedToConfirm() {
        newestChangeBy("sam@x.org", "Sam");
        ResponseEntity<Map<String, Object>> forbidden = ResponseEntity.status(403).body(Map.of("success", false));
        when(support.denyIfNotAllowed(anyList(), eq(P), any())).thenReturn(forbidden);

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op4", null, signedIn(false));

        assertEquals(403, response.getStatusCode().value());
    }
}
