package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleWriteAccessTest {

    private final WorkspaceOwnershipService ownership = mock(WorkspaceOwnershipService.class);
    private final RuleWriteAccess access = new RuleWriteAccess(ownership);

    private static String token(String userId) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = "{\"plan\":\"PRO\",\"userId\":\"" + userId + "\"}";
        return "Bearer " + encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void aViewerCannotChangeRules() {
        when(ownership.isViewerInProject("viewer", "p1")).thenReturn(true);

        RuleWriteAccess.Decision decision = access.decide(token("viewer"), "10.0.0.5", "p1");

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("view-only"), decision.reason());
    }

    @Test
    void aDraftEditorCannotChangeSharedRules() {
        when(ownership.isDraftEditorInProject("drafter", "p1")).thenReturn(true);

        RuleWriteAccess.Decision decision = access.decide(token("drafter"), "10.0.0.5", "p1");

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("draft editors"), decision.reason());
    }

    @Test
    void anEditorOrOwnerMayChangeRules() {
        assertTrue(access.decide(token("editor"), "10.0.0.5", "p1").allowed());
    }

    @Test
    void theRoleOfAnotherProjectDoesNotApply() {
        when(ownership.isViewerInProject("user", "other")).thenReturn(true);

        assertTrue(access.decide(token("user"), "10.0.0.5", "p1").allowed());
        assertFalse(access.decide(token("user"), "10.0.0.5", "other").allowed());
    }

    @Test
    void withoutATokenTheEditorsOwnWriteRulesApplyAndItIsAllowed() {
        assertTrue(access.decide(null, "10.0.0.5", "p1").allowed());
        assertTrue(access.decide("not a token", "10.0.0.5", "p1").allowed());
    }

    @Test
    void onDesktopALocalCallerIsAlwaysAllowed() {
        ReflectionTestUtils.setField(access, "desktopMode", true);
        when(ownership.isViewerInProject("viewer", "p1")).thenReturn(true);

        assertTrue(access.decide(token("viewer"), "127.0.0.1", "p1").allowed());
        assertFalse(access.decide(token("viewer"), "10.0.0.5", "p1").allowed());
    }
}
