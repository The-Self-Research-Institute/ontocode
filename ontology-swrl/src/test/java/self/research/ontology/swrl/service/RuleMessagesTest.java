package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleMessagesTest {

    @Test
    void aTypeMismatchIsExplainedInPlainWords() {
        String raw = "built-in exception thrown by built-in swrlb:mod in rule S1: cannot convert value of type xsd:string to xsd:integer";
        String message = RuleMessages.explain(raw);
        assertTrue(message.startsWith("A value used by a function in the rule is text but the function needs a whole number."), message);
        assertTrue(message.contains("Technical detail: " + raw), message);
    }

    @Test
    void theOffendingValueIsNamedWhenTheEngineGivesOne() {
        String message = RuleMessages.plainSentence("cannot convert value 1.70 of type xsd:decimal to xsd:integer");
        assertTrue(message.contains("a decimal number (1.70)"), message);
        assertTrue(message.contains("needs a whole number"), message);
    }

    @Test
    void otherEngineWordingIsTranslatedToo() {
        assertTrue(RuleMessages.plainSentence("unsupported argument type: expecting boolean, numeric, string").contains("cannot use"));
        assertTrue(RuleMessages.plainSentence("these built-ins do not support argument binding").contains("no earlier condition"));
        assertTrue(RuleMessages.plainSentence("Prefix not registered for prefix name: _:").contains("Reload the project"));
    }

    @Test
    void anUnknownMessageIsLeftExactlyAsItWas() {
        assertEquals("something else", RuleMessages.explain("something else"));
        assertNull(RuleMessages.plainSentence("something else"));
        assertNull(RuleMessages.explain(null));
    }

    @Test
    void longTechnicalDetailIsShortened() {
        String raw = "cannot convert value of type xsd:string to xsd:integer " + "x".repeat(900);
        String message = RuleMessages.explain(raw);
        assertTrue(message.length() < 800, "length " + message.length());
        assertTrue(message.endsWith("...)"), message);
    }

    @Test
    void closestNamesSuggestsTypoFixesAndSimilarNames() {
        List<String> names = List.of("Underweight", "Overweight", "Patient", "hasAge", "hasWeight", "Child");
        assertEquals(List.of("Underweight", "Overweight"), RuleMessages.closestNames("Undrweight", names, 3).subList(0, 2));
        assertEquals(List.of("hasAge"), RuleMessages.closestNames("hasage", List.of("hasAge", "Child"), 3));
        assertTrue(RuleMessages.closestNames("Zebra", names, 3).isEmpty());
    }

    @Test
    void unknownNameIsReadFromTheParserMessage() {
        assertEquals("Underweight", RuleMessages.unknownName("Invalid SWRL atom predicate 'Underweight'"));
        assertNull(RuleMessages.unknownName("something else"));
    }

    @Test
    void bareTextValuesGetTheirType() {
        assertEquals("Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, \"ICU\"^^xsd:string) -> Critical(?p)",
                RuleMessages.normalizeTextLiterals("Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, \"ICU\") -> Critical(?p)"));
        String already = "swrlb:equal(?w, \"ICU\"^^xsd:string)";
        assertEquals(already, RuleMessages.normalizeTextLiterals(already));
        assertEquals("swrlb:contains(?w, \"Gen\"^^xsd:string) ^ swrlb:equal(?x, \"a b\"^^xsd:string)",
                RuleMessages.normalizeTextLiterals("swrlb:contains(?w, \"Gen\") ^ swrlb:equal(?x, \"a b\")"));
        assertEquals("no quotes here ^ swrlb:lessThan(?a, 18)", RuleMessages.normalizeTextLiterals("no quotes here ^ swrlb:lessThan(?a, 18)"));
        assertNull(RuleMessages.normalizeTextLiterals(null));
    }
}
