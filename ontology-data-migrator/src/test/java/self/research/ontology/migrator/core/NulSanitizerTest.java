package self.research.ontology.migrator.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class NulSanitizerTest {

    @Test
    void textWithoutNulIsReturnedUnchanged() {
        String value = "plain text";
        assertSame(value, NulSanitizer.text(value));
        assertNull(NulSanitizer.text(null));
    }

    @Test
    void textLosesEveryNulCharacter() {
        assertEquals("markerror", NulSanitizer.text("mark\u0000er\u0000ror"));
    }

    @Test
    void jsonLosesTheNulEscape() {
        assertEquals("{\"a\":\"xy\"}", NulSanitizer.json("{\"a\":\"x\\u0000y\"}"));
        assertEquals("{\"a\":\"\"}", NulSanitizer.json("{\"a\":\"\\u0000\\u0000\"}"));
    }

    @Test
    void jsonKeepsAnEscapedBackslashFollowedByTheDigits() {
        String escapedBackslash = "{\"a\":\"x\\\\u0000y\"}";
        assertEquals(escapedBackslash, NulSanitizer.json(escapedBackslash));
    }

    @Test
    void jsonKeepsOtherUnicodeEscapes() {
        String other = "{\"a\":\"\\u00e9\\u0001\"}";
        assertEquals(other, NulSanitizer.json(other));
        assertNull(NulSanitizer.json(null));
    }

    @Test
    void jsonHandlesAnEscapedBackslashBeforeARealNulEscape() {
        assertEquals("{\"a\":\"\\\\\"}", NulSanitizer.json("{\"a\":\"\\\\\\u0000\"}"));
    }
}
