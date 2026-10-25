package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestActorDesktopTest {

    private static final String DESKTOP_USER = "desktop-user-local";

    @AfterEach
    void reset() {
        RequestActor.useDesktopIdentity(false);
    }

    @Test
    void outsideDesktopTheGivenIdIsKept() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals("u1", RequestActor.userIdOr(request, "u1"));
        assertEquals("u1", RequestActor.of(request, "u1", "User").userId());
        assertEquals("u1", RequestActor.recordedId(request, "u1", false));
    }

    @Test
    void onDesktopEveryEditIsRecordedForTheLocalUserWhateverThePageSent() {
        RequestActor.useDesktopIdentity(true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals(DESKTOP_USER, RequestActor.userIdOr(request, "someone@x.org"));
        assertEquals(DESKTOP_USER, RequestActor.userIdOr(request, null));
        assertEquals(DESKTOP_USER, RequestActor.of(request, "anonymous", null).userId());
        assertEquals(DESKTOP_USER, RequestActor.recordedId(request, "someone@x.org", false));
        assertEquals(DESKTOP_USER, RequestActor.forScope(request, "someone@x.org", false));
    }

    @Test
    void aDraftScopeIsLeftAloneEvenOnDesktop() {
        RequestActor.useDesktopIdentity(true);

        assertEquals("u1", RequestActor.recordedId(new MockHttpServletRequest(), "u1", true));
    }
}
