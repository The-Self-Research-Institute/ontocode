package self.research.ontology.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalTokenVerifier {

    public static final String HEADER = "X-Ontocode-Internal-Token";

    private final String expected;

    public InternalTokenVerifier(@Value("${ontocode.internal.token:ontocode-internal}") String expected) {
        this.expected = expected;
    }

    public boolean matches(String presented) {
        return expected != null && !expected.isBlank() && presented != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
