package self.research.ontology.reasoner.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictSecurityGuardTest {

    @Test
    void strictModeRefusesToStartWithTheseSettings1() {
        assertThrows(IllegalStateException.class, () -> new StrictSecurityGuard(true, "ontocode-internal"));
    }

    @Test
    void strictModeRefusesToStartWithTheseSettings2() {
        assertThrows(IllegalStateException.class, () -> new StrictSecurityGuard(true, ""));
    }

    @Test
    void strictModeStartsWhenEverythingIsConfigured() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(true, "custom-token"));
    }

    @Test
    void theDefaultsStillStartAsBefore() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(false, "ontocode-internal"));
    }
}
