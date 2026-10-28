package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestActorScopeTest {

    private static MockHttpServletRequest withClaims(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ontology/p1/classes");
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void aDraftScopeUsesTheTokensUserId() {
        assertEquals("u-1", RequestActor.forScope(withClaims("{\"email\":\"me@x.org\",\"userId\":\"u-1\"}"), "victim", true));
    }

    @Test
    void aTokenWithoutAUserIdClaimStillScopesTheDraftToTheTokensOwnEmail() {
        assertEquals("me@x.org", RequestActor.forScope(withClaims("{\"email\":\"me@x.org\"}"), "victim", true));
    }

    @Test
    void withoutAnyTokenTheGivenIdIsKept() {
        assertEquals("given", RequestActor.forScope(new MockHttpServletRequest("GET", "/x"), "given", true));
    }
}
