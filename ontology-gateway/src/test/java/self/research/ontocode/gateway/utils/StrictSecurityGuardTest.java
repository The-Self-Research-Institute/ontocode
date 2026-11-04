package self.research.ontocode.gateway.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictSecurityGuardTest {

    @Test
    void strictModeRefusesToStartWithTheseSettings1() {
        assertThrows(IllegalStateException.class, () -> new StrictSecurityGuard(true, "", true));
    }

    @Test
    void strictModeRefusesToStartWithTheseSettings2() {
        assertThrows(IllegalStateException.class, () -> new StrictSecurityGuard(true, "secret", false));
    }

    @Test
    void strictModeStartsWhenEverythingIsConfigured() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(true, "secret", true));
    }

    @Test
    void theDefaultsStillStartAsBefore() {
        assertDoesNotThrow(() -> new StrictSecurityGuard(false, "", false));
    }
}
