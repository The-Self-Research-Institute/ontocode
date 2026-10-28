package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.common.preview.RulesFingerprint;
import self.research.ontology.swrl.model.ExecutionResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class ExecuteStampTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    @Test
    void aPublicRunCarriesTheOntologyRevisionAndTheRulesFingerprintOfTheRulesInTheOntology() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));
        Mockito.when(harness.client.fetchOntologyWithStamp("p1", null))
                .thenAnswer(i -> new OntologyClientService.FetchedOntology(harness.fresh(), 42L, null));

        ExecutionResult result = harness.service.executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        PreviewStamp stamp = result.getStamp();
        assertEquals(42L, stamp.ontologyRevision());
        assertNull(stamp.draftVersion());
        assertEquals(harness.service.exportEnabledRulesAsOwl("p1", null).fingerprint(), stamp.rulesFingerprint());
        assertNotEquals(RulesFingerprint.NONE, stamp.rulesFingerprint());
    }

    @Test
    void anOntologyWithoutRulesStampsNone() throws Exception {
        TriageHarness harness = new TriageHarness();

        assertEquals(RulesFingerprint.NONE, harness.service.executeRules("p1").getStamp().rulesFingerprint());
    }

    @Test
    void aDraftRunReadsTheDraftAndCarriesTheDraftVersion() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));
        Mockito.when(harness.client.fetchOntologyWithStamp("p1", "u1"))
                .thenAnswer(i -> new OntologyClientService.FetchedOntology(harness.fresh(), null, "7.3"));

        ExecutionResult result = harness.service.executeRules("p1", "u1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertNull(result.getStamp().ontologyRevision());
        assertEquals("7.3", result.getStamp().draftVersion());
        Mockito.verify(harness.client).fetchOntologyWithStamp("p1", "u1");
    }

    @Test
    void changingARuleChangesTheStamp() throws Exception {
        String before = new TriageHarness(rule("r1", "Age under 18", CHILD)).service.executeRules("p1").getStamp().rulesFingerprint();
        String after = new TriageHarness(rule("r1", "Age under 18", CHILD.replace("18", "21"))).service.executeRules("p1").getStamp().rulesFingerprint();

        assertNotEquals(before, after);
    }

    @Test
    void aDisabledRuleDoesNotChangeTheStamp() throws Exception {
        var disabled = rule("r2", "Senior age", "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 65) -> Senior(?p)");
        disabled.setEnabled(false);
        String plain = new TriageHarness(rule("r1", "Age under 18", CHILD)).service.executeRules("p1").getStamp().rulesFingerprint();
        String withDisabled = new TriageHarness(rule("r1", "Age under 18", CHILD), disabled).service.executeRules("p1").getStamp().rulesFingerprint();

        assertEquals(plain, withDisabled);
    }
}
