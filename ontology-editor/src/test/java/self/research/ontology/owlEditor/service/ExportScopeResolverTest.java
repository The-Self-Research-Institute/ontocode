package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportScopeResolverTest {

    private final ExportScopeResolver resolver = new ExportScopeResolver();

    @BeforeEach
    @AfterEach
    void noSignatureRequirementLeaksIn() {
        JwtIdentityExtractor.requireSignature(null);
        JwtIdentityExtractor.trustDesktopLaunchKey(null);
    }

    private static String token(String email, String userId) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"email\":\"" + email + "\",\"userId\":\"" + userId + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private static MockHttpServletRequest requestFor(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token(email, userId));
        return request;
    }

    @Test
    void aPublicExportNeedsNoIdentity() {
        ExportScopeResolver.Scope scope = resolver.resolve(new MockHttpServletRequest(), false, null);
        assertTrue(scope.allowed());
        assertFalse(scope.draft());
        assertFalse(scope.contentScope().draft());
    }

    @Test
    void aDraftExportNeedsAUserId() {
        ExportScopeResolver.Scope scope = resolver.resolve(requestFor("me@x.com", "u1"), true, " ");
        assertEquals(400, scope.status());
    }

    @Test
    void aDraftExportIsAllowedForTheSignedInUserByEmailOrId() {
        assertTrue(resolver.resolve(requestFor("Me@x.com", "u1"), true, "me@x.com").allowed());
        assertTrue(resolver.resolve(requestFor("me@x.com", "u1"), true, "U1").allowed());
        ExportScopeResolver.Scope scope = resolver.resolve(requestFor("me@x.com", "u1"), true, "u1");
        assertTrue(scope.draft());
        assertEquals("u1", scope.contentScope().userId());
    }

    @Test
    void aDraftExportOfSomeoneElsesDraftIsRefused() {
        ExportScopeResolver.Scope scope = resolver.resolve(requestFor("me@x.com", "u1"), true, "other@x.com");
        assertEquals(403, scope.status());
        assertFalse(scope.allowed());
    }

    @Test
    void withoutATokenADraftExportFollowsTheRequestedUserWhenNoSignatureIsRequired() {
        ExportScopeResolver.Scope scope = resolver.resolve(new MockHttpServletRequest(), true, "desktop-user-local");
        assertTrue(scope.allowed());
        assertEquals("desktop-user-local", scope.userId());
    }
}
