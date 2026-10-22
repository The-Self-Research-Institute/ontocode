package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UndoIdentityTest {

    private static String token(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static MockHttpServletRequest requestWith(String payloadJson) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", token(payloadJson));
        return request;
    }

    @Test
    void identityClaimsListTheDatabaseIdAndSubject() {
        String header = token("{\"sub\":\"me@x.com\",\"email\":\"me@x.com\",\"userId\":\"66f0c0ffee\"}");

        assertArrayEquals(new String[]{"66f0c0ffee", "me@x.com"}, JwtClaimUtils.extractIdentityClaims(header));
        assertArrayEquals(new String[0], JwtClaimUtils.extractIdentityClaims(null));
    }

    @Test
    void anIdentityBuiltFromTheClaimsOwnsRowsStoredUnderTheDatabaseId() {
        Identity who = Identity.of("me@x.com", JwtClaimUtils.extractIdentityClaims(
                token("{\"email\":\"me@x.com\",\"id\":\"66f0c0ffee\"}")));

        assertEquals(true, who.queryValues().contains("66f0c0ffee"));
        assertEquals(true, who.owns("66F0C0FFEE"));
    }

    @Test
    void liveEditsAreRecordedUnderTheVerifiedEmailButDraftsKeepTheirOwner() {
        MockHttpServletRequest request = requestWith("{\"email\":\"me@x.com\",\"userId\":\"66f0c0ffee\"}");

        assertEquals("me@x.com", RequestActor.recordedId(request, "66f0c0ffee", false));
        assertEquals("66f0c0ffee", RequestActor.recordedId(request, "66f0c0ffee", true));
        assertEquals("66f0c0ffee", RequestActor.recordedId(new MockHttpServletRequest(), "66f0c0ffee", false));
        assertEquals(List.of("a"), List.of(RequestActor.recordedId(null, "a", false)));
    }
}
