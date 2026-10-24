package self.research.ontology.owlEditor.controller;

import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VerifiedUserTest {

    @BeforeEach
    @AfterEach
    void noSignatureKey() {
        JwtIdentityExtractor.requireSignature(null);
    }

    private static MockHttpServletRequest withToken(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"userId\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void theTokenWinsOverWhoTheBodyClaimsToBe() {
        var result = VerifiedUser.resolve(withToken("acct1"), "owner-id");
        assertEquals("acct1", result.userId());
        assertNull(result.denial());
    }

    @Test
    void withoutATokenTheBodyIdIsUsedOnlyWhenSignaturesAreNotRequired() {
        assertEquals("claimed", VerifiedUser.resolve(new MockHttpServletRequest(), "claimed").userId());

        JwtIdentityExtractor.requireSignature(Keys.hmacShaKeyFor(new byte[32]));
        var refused = VerifiedUser.resolve(new MockHttpServletRequest(), "claimed");
        assertNull(refused.userId());
        assertNotNull(refused.denial());
        assertEquals(401, refused.denial().getStatusCode().value());
    }

    @Test
    void internalCallsWithoutARequestKeepTheGivenId() {
        assertEquals("claimed", VerifiedUser.resolve(null, "claimed").userId());
    }
}
