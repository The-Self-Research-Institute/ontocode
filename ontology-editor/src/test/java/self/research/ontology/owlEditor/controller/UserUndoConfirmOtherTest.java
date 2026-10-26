package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Item;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.HistorySyncService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.UndoBlockers;
import self.research.ontology.owlEditor.service.UndoPreconditions;
import self.research.ontology.owlEditor.service.UserUndoService;
import self.research.ontology.owlEditor.service.UserUndoService.Next;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserUndoConfirmOtherTest {

    private static final String P = "proj";
    private static final String EMAIL = "owner@x.org";

    private final AtomicBoolean othersGone = new AtomicBoolean(false);
    private UserUndoService undoService;
    private ChangeRollbackService rollback;
    private UndoBlockers blockers;
    private RollbackRequestSupport support;
    private UserUndoController controller;

    @BeforeEach
    void setUp() {
        undoService = mock(UserUndoService.class);
        rollback = mock(ChangeRollbackService.class);
        blockers = mock(UndoBlockers.class);
        support = mock(RollbackRequestSupport.class);
        HistorySyncService historySync = mock(HistorySyncService.class);
        UndoPreconditions preconditions = mock(UndoPreconditions.class);

        when(undoService.nextUndo(eq(P), anyBoolean(), any())).thenReturn(
                new Next(true, ChangeRollbackService.UNDO, "t1", null, null, null, "Ontology merge", null, List.of()));
        HistoryChange target = row("t1", null, "mongo-1", "owner");
        when(historySync.getHistoryChange("t1")).thenReturn(target);
        HistoryChange other = row("o1", "csO", "mongo-2", "sam");
        when(historySync.getChangeSet(P, "csO")).thenReturn(List.of(other));
        when(blockers.othersOnTop(eq(P), any(), any())).thenReturn(List.of(other));
        when(preconditions.violation(eq(P), anyList(), any())).thenAnswer(inv -> othersGone.get() ? Optional.empty()
                : Optional.of("Other people's changes are on top of this one: Ontology merge (sam). Undo this edit by hand instead"));
        when(support.denyIfNotAllowed(anyList(), eq(P), any())).thenReturn(null);
        when(rollback.undoChangeSet(eq(P), eq("csO"), any(), eq(false))).thenAnswer(inv -> {
            othersGone.set(true);
            return new Result(200, true, false, "UNDO", "csO", List.of(new Item("o1", null, null, null, null, null)), List.of(), "a1", null);
        });
        when(rollback.undoEntry(eq(P), eq("t1"), any(), eq(false))).thenReturn(
                new Result(200, true, false, "UNDO", "t1", List.of(new Item("t1", null, null, null, null, null)), List.of(), "a2", null));

        controller = new UserUndoController(undoService, rollback, historySync, support, preconditions, new ProjectWriteLockRegistry());
        controller.setBlockers(blockers);
    }

    private static HistoryChange row(String id, String changeSetId, String userId, String username) {
        HistoryChange change = new HistoryChange.Builder(P, "edit-" + id, userId, username).operationType("ontologyMerge")
                .entityIRI("urn:ontocode:merge:" + id).build();
        change.setId(id);
        change.setChangeSetId(changeSetId);
        return change;
    }

    private static MockHttpServletRequest signedIn(boolean confirmOther) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + EMAIL + "\",\"userId\":\"mongo-1\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        if (confirmOther) {
            request.setParameter("confirmOther", "true");
        }
        return request;
    }

    @Test
    void nothingIsUndoneWhenALaterItemOfTheChainWouldBeRefused() {
        HistoryChange first = row("o1", "csO1", "mongo-2", "sam");
        HistoryChange second = row("o2", "csO2", "mongo-3", "kim");
        second.setPublishedPrId("pr9");
        HistorySyncService historySync = mock(HistorySyncService.class);
        when(historySync.getHistoryChange("t1")).thenReturn(row("t1", null, "mongo-1", "owner"));
        when(historySync.getChangeSet(P, "csO1")).thenReturn(List.of(first));
        when(historySync.getChangeSet(P, "csO2")).thenReturn(List.of(second));
        when(blockers.othersOnTop(eq(P), any(), any())).thenReturn(List.of(first, second));
        UndoPreconditions preconditions = mock(UndoPreconditions.class);
        when(preconditions.violation(eq(P), anyList(), any())).thenReturn(Optional.of("Other people's changes are on top"));
        controller = new UserUndoController(undoService, rollback, historySync, support, preconditions, new ProjectWriteLockRegistry());
        controller.setBlockers(blockers);

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op1", null, signedIn(true));

        assertEquals(409, response.getStatusCode().value());
        verify(rollback, never()).undoChangeSet(eq(P), eq("csO1"), any(), anyBoolean());
        verify(rollback, never()).undoChangeSet(eq(P), eq("csO2"), any(), anyBoolean());
        verify(rollback, never()).undoEntry(eq(P), eq("t1"), any(), anyBoolean());
    }

    @Test
    void theChainStopsWhenAnOtherChangeBelongsToAMergedPullRequest() {
        HistoryChange other = row("o1", "csO", "mongo-2", "sam");
        other.setPublishedPrId("pr9");
        HistorySyncService historySync = mock(HistorySyncService.class);
        HistoryChange target = row("t1", null, "mongo-1", "owner");
        when(historySync.getHistoryChange("t1")).thenReturn(target);
        when(historySync.getChangeSet(P, "csO")).thenReturn(List.of(other));
        UndoPreconditions preconditions = mock(UndoPreconditions.class);
        when(preconditions.violation(eq(P), anyList(), any())).thenReturn(Optional.of("Other people's changes are on top"));
        controller = new UserUndoController(undoService, rollback, historySync, support, preconditions, new ProjectWriteLockRegistry());
        controller.setBlockers(blockers);

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op1", null, signedIn(true));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(self.research.ontology.owlEditor.service.PrEntries.MERGED_PR_PART, response.getBody().get("error"));
        verify(rollback, never()).undoChangeSet(eq(P), eq("csO"), any(), anyBoolean());
        verify(rollback, never()).undoEntry(eq(P), eq("t1"), any(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutConfirmationTheRefusalListsWhoIsOnTopAndNothingIsUndone() {
        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op1", null, signedIn(false));

        assertEquals(409, response.getStatusCode().value());
        List<Map<String, Object>> others = (List<Map<String, Object>>) response.getBody().get("othersOnTop");
        assertNotNull(others);
        assertEquals("sam", others.get(0).get("username"));
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(), anyBoolean());
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void withConfirmationTheirChangesAreUndoneFirstThenTheOwners() {
        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op1", null, signedIn(true));

        assertEquals(200, response.getStatusCode().value());
        assertTrue((Boolean) response.getBody().get("success"));
        InOrder order = inOrder(rollback);
        order.verify(rollback).undoChangeSet(eq(P), eq("csO"), any(), eq(false));
        order.verify(rollback).undoEntry(eq(P), eq("t1"), any(), eq(false));
    }

    @Test
    void ifThePersonMayNotUndoTheirChangesNothingIsUndone() {
        when(support.denyIfNotAllowed(anyList(), eq(P), any())).thenReturn(
                ResponseEntity.status(403).body(Map.of("success", false, "error", "You do not have permission to roll back changes in this project.")));

        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "t1", "op1", null, signedIn(true));

        assertEquals(403, response.getStatusCode().value());
        assertFalse(othersGone.get());
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
    }
}
