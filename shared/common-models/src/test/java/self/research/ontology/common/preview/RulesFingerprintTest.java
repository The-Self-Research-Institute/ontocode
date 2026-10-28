package self.research.ontology.common.preview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesFingerprintTest {

    private static final String[] CHILD = {"Child age", "Patient(?p) -> Child(?p)"};
    private static final String[] WEEKLY = {"Weekly", "Patient(?p) -> Weekly(?p)"};

    private static List<String[]> rules(String[]... rules) {
        return java.util.Arrays.asList(rules);
    }

    @Test
    void theAlgorithmNeverChanges() {
        assertEquals("270ccd1343dd5ff7367b5cc845f93fd024074b9ca18a067cd6f0558015575125", RulesFingerprint.of(rules(CHILD, WEEKLY)));
    }

    @Test
    void orderOfTheRulesDoesNotMatter() {
        assertEquals(RulesFingerprint.of(rules(CHILD, WEEKLY)), RulesFingerprint.of(rules(WEEKLY, CHILD)));
    }

    @Test
    void renamingOrEditingARuleChangesTheFingerprint() {
        String base = RulesFingerprint.of(rules(CHILD, WEEKLY));
        assertNotEquals(base, RulesFingerprint.of(rules(new String[]{"Child", CHILD[1]}, WEEKLY)));
        assertNotEquals(base, RulesFingerprint.of(rules(new String[]{CHILD[0], "Patient(?p) -> Kid(?p)"}, WEEKLY)));
        assertNotEquals(base, RulesFingerprint.of(rules(CHILD)));
    }

    @Test
    void noRulesHasAFixedValue() {
        assertEquals(RulesFingerprint.NONE, RulesFingerprint.of(List.of()));
        assertEquals(RulesFingerprint.NONE, RulesFingerprint.of(null));
    }

    @Test
    void anEmptyStampIsRecognised() {
        assertTrue(new PreviewStamp(null, null, null).isEmpty());
        assertTrue(!new PreviewStamp(1L, null, null).isEmpty());
    }

    @Test
    void aStampSurvivesBeingSentBackAsReceived() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(new PreviewStamp(5L, null, "abc"));
        assertTrue(!json.contains("empty"), json);
        PreviewStamp back = mapper.readValue(json.replace("}", ",\"somethingNew\":1}"), PreviewStamp.class);
        assertEquals(new PreviewStamp(5L, null, "abc"), back);
    }
}
