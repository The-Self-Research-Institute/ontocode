package self.research.ontology.owlEditor.util;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtIdentityExtractorTest {

    private final SecretKey key = Jwts.SIG.HS256.key().build();

    @BeforeEach
    @AfterEach
    void resetSignatureKey() {
        JwtIdentityExtractor.requireSignature(null);
    }

    private static MockHttpServletRequest withToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static String unsigned(String json) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(json.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    @Test
    void signedTokenGivesItsEmailWhenSignaturesAreRequired() {
        JwtIdentityExtractor.requireSignature(key);
        String token = Jwts.builder().subject("u@x.com").claim("email", "u@x.com").signWith(key).compact();

        assertEquals(Optional.of("u@x.com"), JwtIdentityExtractor.extractEmail(withToken(token)));
    }

    @Test
    void forgedOrUnsignedTokensAreRejectedWhenSignaturesAreRequired() {
        JwtIdentityExtractor.requireSignature(key);
        String forged = Jwts.builder().claim("email", "victim@x.com").signWith(Jwts.SIG.HS256.key().build()).compact();

        assertEquals(Optional.empty(), JwtIdentityExtractor.extractEmail(withToken(forged)));
        assertEquals(Optional.empty(), JwtIdentityExtractor.extractEmail(withToken(unsigned("{\"email\":\"victim@x.com\"}"))));
    }

    @Test
    void withoutASecretTheEmailIsReadWithoutVerification() {
        assertEquals(Optional.of("dev@x.com"),
                JwtIdentityExtractor.extractEmail(withToken(unsigned("{\"email\":\"dev@x.com\"}"))));
    }

    @Test
    void missingHeaderGivesNoIdentity() {
        assertEquals(Optional.empty(), JwtIdentityExtractor.extractEmail(new MockHttpServletRequest()));
    }
}
