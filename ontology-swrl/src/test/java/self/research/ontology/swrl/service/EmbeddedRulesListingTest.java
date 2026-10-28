package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;

import self.research.ontology.swrl.model.SwrlRule;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class EmbeddedRulesListingTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    @Test
    void rulesStoredInsideTheOntologyAreListedWithTheirNameAndText() throws Exception {
        TriageHarness harness = TriageHarness.withEmbeddedRules();

        List<SwrlEngineService.EmbeddedRule> rules = harness.service.listEmbeddedRules(TriageHarness.PROJECT, null);

        assertEquals(12, rules.size());
        assertTrue(rules.stream().allMatch(r -> r.ruleName() != null && !r.ruleName().isBlank()));
        assertTrue(rules.stream().allMatch(r -> r.ruleText().contains("->")), rules.toString());
        assertTrue(rules.stream().anyMatch(r -> r.ruleText().contains("Child")), rules.toString());
    }

    @Test
    void rulesWithoutAnnotationsGetAStableIdFromTheirAxiom() throws Exception {
        TriageHarness first = TriageHarness.withEmbeddedRules();
        TriageHarness second = TriageHarness.withEmbeddedRules();

        List<SwrlEngineService.EmbeddedRule> a = first.service.listEmbeddedRules(TriageHarness.PROJECT, null);
        List<SwrlEngineService.EmbeddedRule> b = second.service.listEmbeddedRules(TriageHarness.PROJECT, null);

        assertEquals(a.stream().map(SwrlEngineService.EmbeddedRule::id).sorted().toList(),
                b.stream().map(SwrlEngineService.EmbeddedRule::id).sorted().toList());
        assertEquals(12, a.stream().map(SwrlEngineService.EmbeddedRule::id).distinct().count());
        assertTrue(a.stream().allMatch(r -> r.enabled() && !r.readOnly() && r.source() == null));
    }

    @Test
    void annotationsCarryTheNameCommentCategoryIdAndEnabledFlag() throws Exception {
        SwrlRule on = rule("id-on", "Age under 18", CHILD);
        on.setComment("children are under eighteen");
        on.setCategory("Demographics");
        SwrlRule off = rule("id-off", "Senior age", "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 65) -> Senior(?p)");
        off.setEnabled(false);
        TriageHarness harness = new TriageHarness(on, off);

        List<SwrlEngineService.EmbeddedRule> rules = harness.service.listEmbeddedRules(TriageHarness.PROJECT, null);

        assertEquals(2, rules.size());
        SwrlEngineService.EmbeddedRule listedOn = rules.stream().filter(r -> r.id().equals("id-on")).findFirst().orElseThrow();
        assertEquals("Age under 18", listedOn.ruleName());
        assertEquals("children are under eighteen", listedOn.comment());
        assertEquals("Demographics", listedOn.category());
        assertTrue(listedOn.enabled());
        assertFalse(listedOn.readOnly());
        SwrlEngineService.EmbeddedRule listedOff = rules.stream().filter(r -> r.id().equals("id-off")).findFirst().orElseThrow();
        assertFalse(listedOff.enabled());
        assertTrue(listedOff.ruleText().contains("Senior"), listedOff.ruleText());
    }

    @Test
    void aRuleFoundOnlyInAnImportedOntologyIsReadOnlyAndNamesItsSource() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(
                new org.semanticweb.owlapi.io.StringDocumentSource(TriageHarness.triageText()));
        OWLOntology local = manager.createOntology(IRI.create("http://example.org/local"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(local,
                factory.getOWLImportsDeclaration(imported.getOntologyID().getOntologyIRI().get())));
        SWRLRule one = imported.getAxioms(AxiomType.SWRL_RULE).iterator().next();
        manager.removeAxioms(imported, new HashSet<>(imported.getAxioms(AxiomType.SWRL_RULE)));
        Set<OWLAnnotation> label = Set.of(factory.getOWLAnnotation(factory.getRDFSLabel(), factory.getOWLLiteral("From import")));
        manager.addAxiom(imported, one.getAxiomWithoutAnnotations().getAnnotatedAxiom(label));
        manager.addAxioms(local, imported.getAxioms(org.semanticweb.owlapi.model.AxiomType.DECLARATION));

        List<OntologyRules.Entry> entries = OntologyRules.collect(local);

        assertEquals(1, entries.size());
        assertEquals("From import", entries.get(0).name());
        assertTrue(entries.get(0).readOnly());
        assertEquals(imported.getOntologyID().getOntologyIRI().get().toString(), entries.get(0).source());
        assertNotEquals(null, entries.get(0).id());
        assertNull(entries.get(0).comment());
    }
}
