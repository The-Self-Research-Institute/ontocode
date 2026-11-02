package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtegeRuleFlagTest {

    private static final String NS = "http://example.org/triage#";
    private static final java.util.Map<String, String> HEADS = java.util.Map.of("Protege off", "Child", "Protege on", "Senior",
            "Plain", "Critical", "Both", "Child", "Own off", "Senior");
    private static final OWLDataFactory F = OWLManager.getOWLDataFactory();

    private static SWRLRule rule(String label, String comment, Object... flags) {
        SWRLVariable p = F.getSWRLVariable(IRI.create("urn:swrl#p"));
        Set<OWLAnnotation> annotations = new LinkedHashSet<>();
        annotations.add(F.getOWLAnnotation(F.getRDFSLabel(), F.getOWLLiteral(label)));
        if (comment != null) {
            annotations.add(F.getOWLAnnotation(F.getRDFSComment(), F.getOWLLiteral(comment)));
        }
        for (int i = 0; i < flags.length; i += 2) {
            annotations.add(F.getOWLAnnotation(F.getOWLAnnotationProperty((IRI) flags[i]), F.getOWLLiteral((Boolean) flags[i + 1])));
        }
        return F.getSWRLRule(Set.of(F.getSWRLClassAtom(F.getOWLClass(IRI.create(NS + "Patient")), p)),
                Set.of(F.getSWRLClassAtom(F.getOWLClass(IRI.create(NS + HEADS.get(label))), p)), annotations);
    }

    private static SwrlEngineService.EmbeddedRule listed(List<SwrlEngineService.EmbeddedRule> rules, String name) {
        return rules.stream().filter(r -> r.ruleName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aRuleSavedByProtegeAsDisabledIsListedAsDisabledAndNotRun() throws Exception {
        TriageHarness harness = new TriageHarness();
        harness.addAxiom(rule("Protege off", "Written elsewhere", OntologyRules.SWRLA_ENABLED, false));
        harness.addAxiom(rule("Protege on", null, OntologyRules.SWRLA_ENABLED, true));
        harness.addAxiom(rule("Plain", "No flag"));

        List<SwrlEngineService.EmbeddedRule> rules = harness.service.listEmbeddedRules(TriageHarness.PROJECT, null);

        assertEquals(3, rules.size());
        assertFalse(listed(rules, "Protege off").enabled());
        assertEquals("Written elsewhere", listed(rules, "Protege off").comment());
        assertTrue(listed(rules, "Protege on").enabled());
        assertTrue(listed(rules, "Plain").enabled());
        assertTrue(listed(rules, "Protege off").ruleText().contains("Child"));
    }

    @Test
    void theOwnEnabledFlagWinsOverTheProtegeFlag() throws Exception {
        TriageHarness harness = new TriageHarness();
        harness.addAxiom(rule("Both", null, OntologyRules.ENABLED, true, OntologyRules.SWRLA_ENABLED, false));
        harness.addAxiom(rule("Own off", null, OntologyRules.ENABLED, false, OntologyRules.SWRLA_ENABLED, true));

        List<SwrlEngineService.EmbeddedRule> rules = harness.service.listEmbeddedRules(TriageHarness.PROJECT, null);

        assertTrue(listed(rules, "Both").enabled());
        assertFalse(listed(rules, "Own off").enabled());
    }
}
