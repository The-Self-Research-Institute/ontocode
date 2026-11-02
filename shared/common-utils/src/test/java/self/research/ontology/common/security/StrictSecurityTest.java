package self.research.ontology.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictSecurityTest {

    @Test
    void anUnmetRequirementStopsStartupOnlyInStrictMode() {
        assertThrows(IllegalStateException.class, () -> StrictSecurity.require(true, false, "x"));
        assertDoesNotThrow(() -> StrictSecurity.require(false, false, "x"));
        assertDoesNotThrow(() -> StrictSecurity.require(true, true, "x"));
    }

    @Test
    void thePublicDefaultAndBlankTokensAreNotCustom() {
        assertFalse(StrictSecurity.isCustomInternalToken("ontocode-internal"));
        assertFalse(StrictSecurity.isCustomInternalToken(" "));
        assertFalse(StrictSecurity.isCustomInternalToken(null));
        assertTrue(StrictSecurity.isCustomInternalToken("a-long-random-secret"));
    }

    @Test
    void strictModeWithThePublicTokenStopsStartupWithAPlainMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> StrictSecurity.requireCustomInternalToken(true, "ontocode-internal"));
        assertTrue(e.getMessage().contains("ONTOCODE_INTERNAL_TOKEN"));
        assertDoesNotThrow(() -> StrictSecurity.requireCustomInternalToken(true, "a-long-random-secret"));
        assertDoesNotThrow(() -> StrictSecurity.requireCustomInternalToken(false, "ontocode-internal"));
    }
}
