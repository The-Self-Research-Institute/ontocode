package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackRequestSupportTest {

    private final WorkspaceOwnershipService ownership = mock(WorkspaceOwnershipService.class);
    private final RollbackRequestSupport support = new RollbackRequestSupport(ownership);

    private static MockHttpServletRequest withToken(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String token = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static ChangeRollbackService.Item item(String reason) {
        return new ChangeRollbackService.Item("c1", null, "http://example.org/A", "A", null, reason);
    }

    @Test
    void identityComesFromTheTokenNotTheRequestBody() {
        ChangeRollbackService.Actor actor = support.actor(withToken("{\"email\":\"real@x.com\",\"userId\":\"u1\"}"),
                Map.of("userId", "spoofed@x.com", "username", "Spoofed"));
        assertEquals("real@x.com", actor.userId());
        assertEquals("real@x.com", actor.username());
    }

    @Test
    void withoutATokenTheBodyIsUsedForDesktop() {
        ChangeRollbackService.Actor actor = support.actor(new MockHttpServletRequest(),
                Map.of("userId", "desktop-user-local", "username", "Local"));
        assertEquals("desktop-user-local", actor.userId());
        assertEquals("Local", actor.username());
    }

    @Test
    void draftEditorsMayOnlyRollBackTheirOwnDraftChanges() {
        when(ownership.isDraftEditorInProject("u1", "p")).thenReturn(true);
        when(ownership.isUserOwnerOfProject("u1", "p")).thenReturn(false);
        MockHttpServletRequest request = withToken("{\"email\":\"d@x.com\",\"userId\":\"u1\"}");
        HistoryChange own = new HistoryChange.Builder("p", "e1", "d@x.com", "d@x.com").draft(true).build();
        HistoryChange publicChange = new HistoryChange.Builder("p", "e2", "d@x.com", "d@x.com").draft(false).build();

        assertNull(support.denyIfNotAllowed(List.of(own), "p", request));
        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(List.of(own, publicChange), "p", request);
        assertNotNull(denied);
        assertEquals(403, denied.getStatusCode().value());
    }

    @Test
    void viewersMayNotRollBackAnything() {
        when(ownership.isViewerInProject("u1", "p")).thenReturn(true);
        MockHttpServletRequest request = withToken("{\"email\":\"v@x.com\",\"userId\":\"u1\"}");
        HistoryChange change = new HistoryChange.Builder("p", "e1", "other@x.com", "Other").draft(false).build();

        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(List.of(change), "p", request);

        assertNotNull(denied);
        assertEquals(403, denied.getStatusCode().value());
    }

    @Test
    void onlyTheAuthorMayRollBackAStillDraftChangeEvenForEditors() {
        MockHttpServletRequest request = withToken("{\"email\":\"editor@x.com\",\"userId\":\"u2\"}");
        HistoryChange someoneElsesDraft = new HistoryChange.Builder("p", "e1", "author@x.com", "Author").draft(true).build();
        HistoryChange ownDraft = new HistoryChange.Builder("p", "e2", "editor@x.com", "Editor").draft(true).build();
        HistoryChange merged = new HistoryChange.Builder("p", "e3", "author@x.com", "Author").draft(false).build();

        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(List.of(someoneElsesDraft), "p", request);
        assertNotNull(denied);
        assertEquals(403, denied.getStatusCode().value());
        assertNull(support.denyIfNotAllowed(List.of(ownDraft, merged), "p", request));
    }

    @Test
    void withoutATokenNothingIsBlocked() {
        HistoryChange draft = new HistoryChange.Builder("p", "e1", "author@x.com", "Author").draft(true).build();
        assertNull(support.denyIfNotAllowed(List.of(draft), "p", new MockHttpServletRequest()));
    }

    @Test
    void alreadyUndoneMapsToASuccessfulNoOp() {
        ChangeRollbackService.Result result = new ChangeRollbackService.Result(200, false, false, "UNDO", "c1",
                List.of(), List.of(item("Already undone")), null, "Nothing to undo");
        ResponseEntity<Map<String, Object>> response = RollbackRequestSupport.toBody(result, "c1", null);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(true, response.getBody().get("alreadyReverted"));
    }

    @Test
    void nothingDoneForAnotherReasonIsAConflictWithTheReason() {
        ChangeRollbackService.Result result = new ChangeRollbackService.Result(200, false, false, "UNDO", "c1",
                List.of(), List.of(item("It was changed since this edit")), null, null);
        ResponseEntity<Map<String, Object>> response = RollbackRequestSupport.toBody(result, "c1", null);
        assertEquals(409, response.getStatusCode().value());
        assertEquals("It was changed since this edit", response.getBody().get("error"));
    }

    @Test
    void aSuccessfulRollbackReportsWhatWasAppliedAndSkipped() {
        ChangeRollbackService.Result result = new ChangeRollbackService.Result(200, true, false, "UNDO", "set-1",
                List.of(item(null)), List.of(item("It was changed since this edit")), "audit-1", null);
        ResponseEntity<Map<String, Object>> response = RollbackRequestSupport.toBody(result, null, null);
        Map<String, Object> body = response.getBody();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, body.get("mutationApplied"));
        assertEquals("audit-1", body.get("auditId"));
        assertEquals(1, ((List<?>) body.get("applied")).size());
        assertTrue(((List<?>) body.get("skipped")).size() == 1);
    }
}
