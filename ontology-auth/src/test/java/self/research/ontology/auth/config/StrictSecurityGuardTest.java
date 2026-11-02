package self.research.ontology.auth.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictSecurityGuardTest {

    @Test
    void strictModeRefusesToStartWithTheseSettings1() {
        assertThrows(IllegalStateException.class, () -> new StrictSecurityGuard(true, false, "ontocode-internal"));
    }

    @Test
    void strictModeStartsWhenEverythingIsConfigured() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(true, false, "custom-token"));
    }

    @Test
    void theDefaultsStillStartAsBefore() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(false, false, "ontocode-internal"));
    }

    @Test
    void theDesktopAppIsNeverBlocked() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(true, true, "ontocode-internal"));
    }
}
