package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.swrl.model.SwrlRule;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class RulesOwlExportTest {

    private static final String NS = "http://example.org/triage#";
    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";
    private static final String SENIOR = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 65) -> Senior(?p)";
    private static final String POWER = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:pow(?x, ?a, 2) ^ swrlb:lessThan(?x, 400) -> Child(?p)";
    private static final String TEXT = "Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, \"ICU\") -> Critical(?p)";

    private static String label(SWRLRule rule) {
        return rule.getAnnotations().iterator().next().getValue().asLiteral().get().getLiteral();
    }

    @Test
    void onlyEnabledRulesAreExportedWithLabelsAndDeclarations() throws Exception {
        SwrlRule disabled = rule("r5", "Off rule", SENIOR.replace("Senior", "Obese"));
        disabled.setEnabled(false);
        TriageHarness harness = new TriageHarness(
                rule("r1", "Age under 18", CHILD),
                rule("r2", "Senior age", SENIOR),
                rule("r3", "Squared age", POWER),
                rule("r4", "ICU ward", TEXT),
                disabled);

        SwrlEngineService.RulesExport export = harness.service.exportEnabledRulesAsOwl(TriageHarness.PROJECT, null);

        OWLOntology parsed = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(export.text()));
        Set<SWRLRule> rules = parsed.getAxioms(AxiomType.SWRL_RULE);
        assertEquals(4, rules.size(), export.text());
        assertEquals(Set.of("Age under 18", "Senior age", "Squared age", "ICU ward"),
                rules.stream().map(RulesOwlExportTest::label).collect(Collectors.toSet()));
        assertEquals(parsed.getAxiomCount() - 4, parsed.getAxioms(AxiomType.DECLARATION).size());
        assertTrue(parsed.containsClassInSignature(org.semanticweb.owlapi.model.IRI.create(NS + "Patient")));
        assertTrue(parsed.containsDataPropertyInSignature(org.semanticweb.owlapi.model.IRI.create(NS + "hasAge")));
        assertFalse(export.text().contains("Off rule"));
        long builtIns = rules.stream().flatMap(r -> r.getBody().stream()).filter(a -> a instanceof SWRLBuiltInAtom).count();
        assertEquals(5, builtIns);
        assertTrue(export.text().contains("swrlb#lessThan"), export.text());
        assertTrue(export.skipped().isEmpty());
    }

    @Test
    void theFingerprintMatchesTheOneExecutionStampsAndDisabledRulesAreAbsent() throws Exception {
        SwrlRule disabled = rule("r2", "Senior age", SENIOR);
        disabled.setEnabled(false);
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD), disabled);

        SwrlEngineService.RulesExport export = harness.service.exportEnabledRulesAsOwl(TriageHarness.PROJECT, null);

        assertEquals(harness.service.executeRules(TriageHarness.PROJECT).getStamp().rulesFingerprint(), export.fingerprint());
        assertFalse(export.text().contains("Senior age"));
        assertEquals(1, OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(export.text()))
                .getAxioms(AxiomType.SWRL_RULE).size());
    }

    @Test
    void duplicateRuleNamesAreLabelledWithTheRuleIdAndNoRuleIsLost() throws Exception {
        TriageHarness harness = new TriageHarness(rule("a1", "Same", CHILD), rule("a2", "Same", SENIOR));

        SwrlEngineService.RulesExport export = harness.service.exportEnabledRulesAsOwl(TriageHarness.PROJECT, null);

        Set<SWRLRule> rules = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(export.text()))
                .getAxioms(AxiomType.SWRL_RULE);
        assertEquals(Set.of("Same (a1)", "Same (a2)"), rules.stream().map(RulesOwlExportTest::label).collect(Collectors.toSet()));
    }

    @Test
    void rulesAlreadyInTheOntologyAreExportedWithoutAnyStoredRule() throws Exception {
        SwrlEngineService.RulesExport export = TriageHarness.withEmbeddedRules().service.exportEnabledRulesAsOwl(TriageHarness.PROJECT, null);

        assertEquals(12, OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(export.text()))
                .getAxioms(AxiomType.SWRL_RULE).size());
    }
}
