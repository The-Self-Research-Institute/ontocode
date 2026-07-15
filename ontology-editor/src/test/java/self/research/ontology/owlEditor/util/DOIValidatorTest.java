package self.research.ontology.owlEditor.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class DOIValidatorTest {

    @Test
    public void testNormalizeStripsUrlAndPunctuation() {
        String raw = "https://doi.org/10.1234/abc-DEF_1234).";
        String normalized = DOIValidator.normalize(raw);
        assertEquals("10.1234/abc-DEF_1234", normalized);
    }

    @Test
    public void testIsValidFormatAcceptsValidExamples() {
        assertTrue(DOIValidator.isValidFormat("10.1000/xyz123"));
        assertTrue(DOIValidator.isValidFormat("https://doi.org/10.5555/12345"));
        assertTrue(DOIValidator.isValidFormat("10.1000/182"));
    }

    @Test
    public void testIsValidFormatRejectsInvalid() {
        assertFalse(DOIValidator.isValidFormat("not-a-doi"));
        assertFalse(DOIValidator.isValidFormat("10.100/shortprefix"));
        assertFalse(DOIValidator.isValidFormat("10.abcdef/123"));
    }
}
