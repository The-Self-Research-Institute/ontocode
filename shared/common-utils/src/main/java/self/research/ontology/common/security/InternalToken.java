package self.research.ontology.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class InternalToken {

    public static final String HEADER = "X-Ontocode-Internal-Token";

    private InternalToken() {
    }

    public static boolean matches(String expected, String presented) {
        return expected != null && !expected.isBlank() && presented != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
