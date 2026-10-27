package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.rules.OntologyRulesFingerprint;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedRulesServiceTest {

    private static OWLOntology baseOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/sr"));
        OWLClass person = df.getOWLClass(IRI.create(SavedRulesFixtures.NS + "Person"));
        OWLClass adult = df.getOWLClass(IRI.create(SavedRulesFixtures.NS + "Adult"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(adult, person));
        return ontology;
    }

    @Test
    void parsesRulesFingerprintAndSkippedFromTheResponse() {
        String skipped = "[{\"ruleName\":\"broken\",\"reason\":\"Unknown class\"}]";
        SavedRulesService service = new SavedRulesService(
                (url, token) -> SavedRulesFixtures.ok(SavedRulesFixtures.rulesText("adult"), "abc", skipped), "http://swrl/", "tok");

        Optional<SavedRulesService.SavedRules> loaded = service.load("p1", null);

        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().rules().size());
        assertEquals("abc", loaded.get().fingerprint());
        assertEquals(List.of(new SavedRulesService.SkippedSavedRule("broken", "Unknown class")), loaded.get().skipped());
    }

    @Test
    void requestCarriesTheDraftUserAndTheInternalToken() {
        List<String> urls = new java.util.ArrayList<>();
        List<String> tokens = new java.util.ArrayList<>();
        SavedRulesService service = new SavedRulesService((url, token) -> {
            urls.add(url);
            tokens.add(token);
            return SavedRulesFixtures.ok("", "none", null);
        }, "http://swrl", "tok");

        service.load("p1", null);
        service.load("p1", "u 1");

        assertEquals("http://swrl/api/swrl/p1/rules/owl?draft=false", urls.get(0));
        assertEquals("http://swrl/api/swrl/p1/rules/owl?draft=true&userId=u+1", urls.get(1));
        assertEquals("tok", tokens.get(0));
    }

    @Test
    void aNon200AnswerIsNotTheSameAsNoRulesAndBacksOff() {
        AtomicInteger calls = new AtomicInteger();
        SavedRulesService service = new SavedRulesService((url, token) -> {
            calls.incrementAndGet();
            return new SavedRulesService.HttpAnswer(500, java.util.Map.of(), "boom");
        }, "http://swrl", "tok");

        assertTrue(service.load("p1", null).isEmpty());
        assertTrue(service.load("p1", null).isEmpty());

        assertEquals(1, calls.get());
    }

    @Test
    void anUnreachableServiceGivesNoResult() {
        assertTrue(SavedRulesFixtures.unreachable().load("p1", null).isEmpty());
    }

    @Test
    void mergingAddsTheSavedRuleAndChangesTheFingerprint() throws Exception {
        OWLOntology base = baseOntology();

        SavedRulesService.Merged merged = SavedRulesFixtures.withRule().applyTo(base, "p1", null);

        assertFalse(merged.savedRulesUnavailable());
        assertEquals(1, merged.added());
        assertNotSame(base, merged.ontology());
        assertEquals(1, merged.ontology().axioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).count());
        assertEquals(0, base.axioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).count());
        assertNotEquals(OntologyRulesFingerprint.of(base), merged.fingerprint());
    }

    @Test
    void withNoSavedRulesTheOntologyAndFingerprintAreUnchanged() throws Exception {
        OWLOntology base = baseOntology();

        SavedRulesService.Merged merged = SavedRulesFixtures.withoutRules().applyTo(base, "p1", null);

        assertSame(base, merged.ontology());
        assertEquals(OntologyRulesFingerprint.NONE, merged.fingerprint());
        assertFalse(merged.savedRulesUnavailable());
    }

    @Test
    void aSavedRuleAlreadyEmbeddedInTheOntologyIsNotAddedTwice() throws Exception {
        OWLOntology base = baseOntology();
        SavedRulesService.SavedRules saved = SavedRulesFixtures.withRule().load("p1", null).orElseThrow();
        Set<SWRLRule> rules = saved.rules();
        base.getOWLOntologyManager().addAxioms(base, rules);

        SavedRulesService.Merged merged = SavedRulesService.merge(base, saved);

        assertSame(base, merged.ontology());
        assertEquals(0, merged.added());
    }

    @Test
    void anUnavailableServiceMarksTheResultAndSeparatesItsFingerprint() throws Exception {
        OWLOntology base = baseOntology();

        SavedRulesService.Merged merged = SavedRulesFixtures.unreachable().applyTo(base, "p1", null);

        assertTrue(merged.savedRulesUnavailable());
        assertSame(base, merged.ontology());
        assertNotEquals(OntologyRulesFingerprint.of(base), merged.fingerprint());
    }
}
