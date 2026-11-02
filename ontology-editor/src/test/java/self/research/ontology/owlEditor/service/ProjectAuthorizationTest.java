package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectAuthorizationTest {

    private ProjectAccessService access;
    private ProjectAuthorization authorization;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessService.class);
        authorization = new ProjectAuthorization(access, false);
    }

    private static MockHttpServletRequest signedIn(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String claims = userId == null
                ? "{\"email\":\"" + email + "\"}"
                : "{\"email\":\"" + email + "\",\"userId\":\"" + userId + "\"}";
        request.addHeader("Authorization", "Bearer h."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".s");
        return request;
    }

    @Test
    void aCallerWithoutATokenIsUnauthenticatedEverywhere() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();

        assertEquals(HttpStatus.UNAUTHORIZED, authorization.unauthenticated(anonymous).orElseThrow().status());
        assertEquals(HttpStatus.UNAUTHORIZED, authorization.noAccessTo(anonymous, "p1").orElseThrow().status());
        assertEquals(HttpStatus.UNAUTHORIZED, authorization.notOwnerOf(anonymous, "p1").orElseThrow().status());
    }

    @Test
    void aSignedInCallerPassesTheSignedInCheck() {
        assertTrue(authorization.unauthenticated(signedIn("a@x.org", "u-1")).isEmpty());
    }

    @Test
    void accessIsCheckedByUserIdWhenTheTokenHasOne() {
        when(access.hasProjectAccess("p1", "u-1", "a@x.org")).thenReturn(true);

        assertTrue(authorization.noAccessTo(signedIn("a@x.org", "u-1"), "p1").isEmpty());
        verify(access).hasProjectAccess("p1", "u-1", "a@x.org");
    }

    @Test
    void accessFallsBackToTheEmailWhenTheTokenHasNoUserId() {
        when(access.hasProjectAccess("p1", "a@x.org")).thenReturn(true);

        assertTrue(authorization.noAccessTo(signedIn("a@x.org", null), "p1").isEmpty());
    }

    @Test
    void aCallerWithoutAccessIsForbidden() {
        when(access.hasProjectAccess("p1", "u-9", "m@x.org")).thenReturn(false);

        assertEquals(HttpStatus.FORBIDDEN,
                authorization.noAccessTo(signedIn("m@x.org", "u-9"), "p1").orElseThrow().status());
    }

    @Test
    void onlyTheOwnerPassesTheOwnerCheck() {
        when(access.isOwner("p1", "u-1", "o@x.org")).thenReturn(true);

        assertTrue(authorization.notOwnerOf(signedIn("o@x.org", "u-1"), "p1").isEmpty());
        assertEquals(HttpStatus.FORBIDDEN,
                authorization.notOwnerOf(signedIn("m@x.org", "u-9"), "p1").orElseThrow().status());
    }

    @Test
    void theDesktopAppNeedsNoSignInOrOwnership() {
        ProjectAuthorization desktop = new ProjectAuthorization(access, true);
        MockHttpServletRequest anonymous = new MockHttpServletRequest();

        assertTrue(desktop.unauthenticated(anonymous).isEmpty());
        assertTrue(desktop.noAccessTo(anonymous, "p1").isEmpty());
        assertTrue(desktop.notOwnerOf(anonymous, "p1").isEmpty());
    }

    @Test
    void aDenialBecomesAJsonResponseWithTheStatus() {
        var response = authorization.notOwnerOf(signedIn("m@x.org", "u-9"), "p1").orElseThrow().toResponse();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
    }
}
