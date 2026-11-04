package self.research.ontology.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalTokenTest {

    @Test
    void theHeaderNameIsTheOneEveryServiceSends() {
        assertEquals("X-Ontocode-Internal-Token", InternalToken.HEADER);
    }

    @Test
    void onlyTheExactTokenMatches() {
        assertTrue(InternalToken.matches("s3cret", "s3cret"));
        assertFalse(InternalToken.matches("s3cret", "s3cre"));
        assertFalse(InternalToken.matches("s3cret", "S3CRET"));
        assertFalse(InternalToken.matches("s3cret", ""));
    }

    @Test
    void aMissingOrBlankTokenNeverMatches() {
        assertFalse(InternalToken.matches("s3cret", null));
        assertFalse(InternalToken.matches(null, null));
        assertFalse(InternalToken.matches("", ""));
        assertFalse(InternalToken.matches("  ", "  "));
    }
}
