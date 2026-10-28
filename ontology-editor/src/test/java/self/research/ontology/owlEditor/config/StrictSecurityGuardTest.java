package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictSecurityGuardTest {

    private static final String SECRET = java.util.Base64.getEncoder().encodeToString(new byte[48]);

    @Test
    void strictModeRefusesToStartWithoutAJwtSecret() {
        assertThrows(IllegalStateException.class,
                () -> new StrictSecurityGuard(true, false, true, "custom-token", new JwtVerifier("")));
    }

    @Test
    void strictModeRefusesToStartWhenTokensAreNotRequired() {
        assertThrows(IllegalStateException.class,
                () -> new StrictSecurityGuard(true, false, false, "custom-token", new JwtVerifier(SECRET)));
    }

    @Test
    void strictModeRefusesToStartWithThePublicInternalToken() {
        assertThrows(IllegalStateException.class,
                () -> new StrictSecurityGuard(true, false, true, "ontocode-internal", new JwtVerifier(SECRET)));
    }

    @Test
    void strictModeStartsWhenEverythingIsConfigured() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(true, false, true, "custom-token", new JwtVerifier(SECRET)));
    }

    @Test
    void theDefaultsStillStartAsBefore() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(false, false, false, "ontocode-internal", new JwtVerifier("")));
    }

    @Test
    void theDesktopAppIsNeverBlocked() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(true, true, false, "ontocode-internal", new JwtVerifier("")));
    }
}
