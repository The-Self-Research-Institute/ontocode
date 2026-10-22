package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DesktopUndoIdentityTest {

    private static RollbackRequestSupport support(boolean desktop) {
        RollbackRequestSupport support = new RollbackRequestSupport(mock(WorkspaceOwnershipService.class));
        ReflectionTestUtils.setField(support, "desktopMode", desktop);
        return support;
    }

    private static MockHttpServletRequest signedIn(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void onDesktopTheUndoIsForTheLocalUserWhoMadeTheEdits() {
        Identity who = UndoIdentityResolver.resolve(support(true), new MockHttpServletRequest(), null);
        assertEquals("desktop-user-local", who.primary());
        assertTrue(who.aliases().contains("desktop-user-local"));
    }

    @Test
    void onDesktopARollbackActorIsTheLocalUserToo() {
        ChangeRollbackService.Actor actor = support(true).actor(new MockHttpServletRequest(), Map.of());
        assertEquals("desktop-user-local", actor.userId());
    }

    @Test
    void aSignedInUserStillWinsOnDesktop() {
        Identity who = UndoIdentityResolver.resolve(support(true), signedIn("real@x.org"), null);
        assertEquals("real@x.org", who.primary());
    }

    @Test
    void outsideDesktopAnUnidentifiedRequestStillGetsNoOneElsesHistory() {
        Identity who = UndoIdentityResolver.resolve(support(false), new MockHttpServletRequest(), null);
        assertEquals("system", who.primary());
    }
}
