package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.HistorySyncService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.UndoPreconditions;
import self.research.ontology.owlEditor.service.UserUndoService;
import self.research.ontology.owlEditor.service.UserUndoService.Next;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserUndoMergedPrTest {

    private static final String P = "proj";

    private ChangeRollbackService rollback;
    private UserUndoController controller;

    @BeforeEach
    void setUp() {
        UserUndoService undoService = mock(UserUndoService.class);
        rollback = mock(ChangeRollbackService.class);
        HistorySyncService historySync = mock(HistorySyncService.class);
        UndoPreconditions preconditions = mock(UndoPreconditions.class);
        when(undoService.nextUndo(eq(P), anyBoolean(), any())).thenReturn(
                new Next(true, ChangeRollbackService.UNDO, "pr-entry", null, null, "urn:ontocode:pr:pr1", "Merged PR: Add toppings", null, List.of()));
        HistoryChange entry = new HistoryChange.Builder(P, "edit-1", "owner-id", "owner").operationType("prPublished")
                .entityIRI("urn:ontocode:pr:pr1").entityLabel("Merged PR: Add toppings").build();
        entry.setId("pr-entry");
        when(historySync.getHistoryChange("pr-entry")).thenReturn(entry);
        controller = new UserUndoController(undoService, rollback, historySync, mock(RollbackRequestSupport.class),
                preconditions, new ProjectWriteLockRegistry());
    }

    private static MockHttpServletRequest signedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"email\":\"owner@x.org\"}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    @SuppressWarnings("unchecked")
    void undoingAMergedPrEntryPointsToTheRevertAndChangesNothing() {
        ResponseEntity<Map<String, Object>> response = controller.undo(P, false, "pr-entry", "op1", null, signedIn());

        assertEquals(409, response.getStatusCode().value());
        Map<String, Object> pr = (Map<String, Object>) response.getBody().get("revertPr");
        assertEquals("pr1", pr.get("prId"));
        assertEquals("Add toppings", pr.get("title"));
        verify(rollback, never()).undoEntry(anyString(), anyString(), any(), anyBoolean());
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(), anyBoolean());
    }
}
