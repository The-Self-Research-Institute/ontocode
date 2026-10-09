package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.HistorySyncService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeTrackingControllerTest {

    @Mock
    private HistorySyncService historySyncService;
    @Mock
    private WorkspaceOwnershipService workspaceOwnershipService;
    @Mock
    private RollbackAuditRepository rollbackAuditRepository;

    private ChangeTrackingController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ChangeTrackingController();
        ReflectionTestUtils.setField(controller, "historySyncService", historySyncService);
        ReflectionTestUtils.setField(controller, "rollbackSupport", new RollbackRequestSupport(workspaceOwnershipService));
        ReflectionTestUtils.setField(controller, "rollbackAuditRepository", rollbackAuditRepository);
        when(historySyncService.addComment(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private MockHttpServletRequest requestWithEmail(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"email\":\"" + email + "\"}").getBytes());
        request.addHeader("Authorization", "Bearer header." + payload + ".sig");
        return request;
    }

    @Test
    void addCommentUsesTheAuthenticatedUserNotTheRequestBody() {
        MockHttpServletRequest httpRequest = requestWithEmail("real.user@example.com");

        controller.addComment("proj-1", "c1", Map.of("text", "Looks good"), httpRequest);

        verify(historySyncService).addComment("c1", "real.user@example.com", "real.user@example.com", "Looks good");
    }

    @Test
    void addCommentFallsBackToSystemWithNoAuthHeader() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        controller.addComment("proj-1", "c1", Map.of("text", "Looks good"), httpRequest);

        verify(historySyncService).addComment("c1", "system", "System", "Looks good");
    }

    @SuppressWarnings("unchecked")
    private String detailsStatus(HistoryChange change) {
        when(historySyncService.getHistoryChange("c1")).thenReturn(change);
        when(historySyncService.getHistoryChanges("proj-1")).thenReturn(List.of(change));
        when(historySyncService.computeConflicts(List.of(change))).thenReturn(Map.of());
        Map<String, Object> body = controller.getChangeDetails("proj-1", "c1").getBody();
        return (String) ((Map<String, Object>) body.get("change")).get("status");
    }

    @Test
    void detailsShowSavedForAnOrdinaryChangeEvenIfStoredAsPending() {
        HistoryChange change = new HistoryChange("proj-1", "edit-1", "u1", "User");
        change.setStatus("PENDING");

        assertEquals("SAVED", detailsStatus(change));
    }

    @Test
    void detailsShowDraftForADraftChange() {
        HistoryChange change = new HistoryChange("proj-1", "edit-1", "u1", "User");
        change.setDraft(true);

        assertEquals("DRAFT", detailsStatus(change));
    }

    @Test
    void detailsShowRevertedForARolledBackChange() {
        HistoryChange change = new HistoryChange("proj-1", "edit-1", "u1", "User");
        change.setDraft(true);
        change.setReverted(true);

        assertEquals("REVERTED", detailsStatus(change));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailsShowConflictedForAConflictingChange() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HistoryChange change = new HistoryChange("proj-1", "edit-1", "u1", "User");
        change.setId("c1");
        change.setEntityIRI("http://example.org#E");
        change.setOldValue("A");
        change.setNewValue("B");
        change.setTimestamp(now);

        HistoryChange partner = new HistoryChange("proj-1", "edit-2", "u2", "Other");
        partner.setId("c2");
        partner.setEntityIRI("http://example.org#E");
        partner.setOldValue("A");
        partner.setNewValue("C");
        partner.setTimestamp(now.minusMinutes(5));

        List<HistoryChange> all = List.of(partner, change);
        when(historySyncService.getHistoryChange("c1")).thenReturn(change);
        when(historySyncService.getHistoryChanges("proj-1")).thenReturn(all);
        when(historySyncService.computeConflicts(all)).thenReturn(Map.of("c1",
                new HistorySyncService.ConflictMatch("c2", "u2", "Other", "C", partner.getTimestamp())));

        Map<String, Object> body = controller.getChangeDetails("proj-1", "c1").getBody();
        assertEquals("CONFLICTED", ((Map<String, Object>) body.get("change")).get("status"));
    }

    @Test
    void newChangesDefaultToSaved() {
        assertEquals("SAVED", new HistoryChange().getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailsTimestampIsSerializedAsUtcSoTheClientCanRenderItsOwnTimezone() {
        HistoryChange change = new HistoryChange("proj-1", "edit-1", "u1", "User");
        change.setTimestamp(LocalDateTime.of(2026, 10, 8, 15, 20, 32));
        when(historySyncService.getHistoryChange("c1")).thenReturn(change);

        Map<String, Object> body = controller.getChangeDetails("proj-1", "c1").getBody();
        String timestamp = (String) ((Map<String, Object>) body.get("change")).get("timestamp");

        assertEquals("2026-10-08T15:20:32Z", timestamp);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentChangesListShowsTheRealStatusOfEachChange() {
        HistoryChange saved = new HistoryChange("proj-1", "edit-1", "u1", "User");
        saved.setStatus("PENDING");
        HistoryChange draft = new HistoryChange("proj-1", "edit-2", "u1", "User");
        draft.setDraft(true);
        HistoryChange reverted = new HistoryChange("proj-1", "edit-3", "u1", "User");
        reverted.setStatus("PENDING");
        reverted.setReverted(true);
        reverted.setRevertedAuditId("audit-1");
        when(historySyncService.getHistoryChanges("proj-1")).thenReturn(List.of(saved, draft, reverted));

        Map<String, Object> body = controller.getRecentChanges("proj-1", 100).getBody();

        List<Map<String, Object>> changes = (List<Map<String, Object>>) body.get("changes");
        assertEquals(List.of("SAVED", "DRAFT", "REVERTED"), changes.stream().map(c -> c.get("status")).toList());
    }
}
