package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.ValidationResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class FriendlyRuleFeedbackTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";
    private static final String UNKNOWN_CLASS = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Childd(?p)";

    private static SWRLRule heightIntoMod() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        String ns = "http://example.org/triage#";
        SWRLVariable p = df.getSWRLVariable(IRI.create(ns + "p"));
        SWRLVariable h = df.getSWRLVariable(IRI.create(ns + "h"));
        SWRLVariable r = df.getSWRLVariable(IRI.create(ns + "r"));
        Set<SWRLAtom> body = new LinkedHashSet<>();
        body.add(df.getSWRLClassAtom(df.getOWLClass(IRI.create(ns + "Patient")), p));
        body.add(df.getSWRLDataPropertyAtom(df.getOWLDataProperty(IRI.create(ns + "hasHeight")), p, h));
        body.add(df.getSWRLBuiltInAtom(IRI.create("http://www.w3.org/2003/11/swrlb#mod"),
                List.of(r, h, df.getSWRLLiteralArgument(df.getOWLLiteral(7)))));
        body.add(df.getSWRLBuiltInAtom(IRI.create("http://www.w3.org/2003/11/swrlb#equal"),
                List.of(r, df.getSWRLLiteralArgument(df.getOWLLiteral(0)))));
        Set<SWRLAtom> head = Set.of(df.getSWRLClassAtom(df.getOWLClass(IRI.create(ns + "WeeklyReview")), p));
        return df.getSWRLRule(body, head, Set.of(df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("Bad maths"))));
    }

    @Test
    void whenEveryRuleRunsTheRunIsCompleteWithNothingSkipped() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertEquals("completed", result.getCompletion());
        assertTrue(result.getSkippedRules().isEmpty());
        assertEquals(List.of("Age under 18"), result.getExecutedRuleNames());
    }

    @Test
    void validatingAnUnknownNameSuggestsTheClosestExistingOne() throws Exception {
        TriageHarness harness = new TriageHarness();

        ValidationResult result = harness.service.validateRule(TriageHarness.PROJECT, UNKNOWN_CLASS);

        assertFalse(result.isValid());
        assertEquals("Unknown class or property 'Childd'", result.getErrorMessage());
        assertEquals("Did you mean: Child?", result.getSuggestions().get(0));
    }

    @Test
    void aTypeMismatchIsExplainedInPlainWords() throws Exception {
        TriageHarness harness = new TriageHarness();
        harness.addAxiom(heightIntoMod());

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertFalse(result.isSuccess());
        assertEquals("failed", result.getCompletion());
        String message = result.getErrorMessage();
        assertTrue(message.contains("needs a whole number"), message);
        assertTrue(message.contains("Technical detail:"), message);
    }

    @Test
    void textWrittenWithoutATypeStillMatches() throws Exception {
        String bare = "Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, \"ICU\") -> Critical(?p)";
        TriageHarness harness = new TriageHarness(rule("r1", "ICU ward", bare));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertTrue(result.getInferredAxioms().stream().anyMatch(axiom -> String.valueOf(axiom.getDescription()).contains("Critical")
                && String.valueOf(axiom.getDescription()).contains("bob")), "bob should be Critical");
    }
}
