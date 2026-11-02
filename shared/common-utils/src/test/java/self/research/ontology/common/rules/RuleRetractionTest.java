package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.common.rules.RuleExecutionResult.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleRetractionTest {

    static final String DECL = "Declaration(Class(:A))Declaration(Class(:A2))Declaration(Class(:B))Declaration(Class(:C))Declaration(Class(:Over18))Declaration(Class(:Checked))"
            + "Declaration(DataProperty(:hasAge))Declaration(NamedIndividual(:a))\n";
    static final String A_TO_B = "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n";
    static final String B_TO_C = "DLSafeRule(Body(ClassAtom(:B Variable(:x))) Head(ClassAtom(:C Variable(:x))))\n";
    static final String A2_TO_B = "DLSafeRule(Body(ClassAtom(:A2 Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n";
    static final String AGE_TO_OVER18 = "DLSafeRule(Body(DataPropertyAtom(:hasAge Variable(:x) Variable(:v)) BuiltInAtom(swrlb:add Variable(:w) Variable(:v) \"1\"^^xsd:integer) "
            + "BuiltInAtom(swrlb:greaterThan Variable(:w) \"18\"^^xsd:integer)) Head(ClassAtom(:Over18 Variable(:x))))\n";
    static final String OVER18_TO_CHECKED = "DLSafeRule(Body(ClassAtom(:Over18 Variable(:x))) Head(ClassAtom(:Checked Variable(:x))))\n";
    static final String AGE_FACT = "DataPropertyAssertion(:hasAge :a \"30\"^^xsd:integer)\n";

    static OWLOntology inline(String body) throws Exception {
        return RuleExecutionTest.inline(DECL + body);
    }

    static RuleExecutionResult run(OWLOntology ontology, MiniRuleEngine engine) {
        return RuleExecutionTest.run(ontology, engine);
    }

    static Set<String> keys(RuleExecutionResult result) {
        return RuleExecutionTest.derivedKeys(result);
    }

    static boolean derives(RuleExecutionResult result, String fragment) {
        return RuleExecutionTest.derives(result, fragment);
    }

    @Test
    void removingTheFirstFactOfANativeChainRemovesEveryConclusionDownstream() throws Exception {
        String rules = A_TO_B + B_TO_C;
        RuleExecutionResult with = run(inline("ClassAssertion(:A :a)\n" + rules), new MiniRuleEngine());
        RuleExecutionResult without = run(inline(rules), new MiniRuleEngine());

        assertTrue(derives(with, "#B>"), keys(with).toString());
        assertTrue(derives(with, "#C>"), keys(with).toString());
        assertFalse(derives(without, "#B>"));
        assertFalse(derives(without, "#C>"));
        assertTrue(without.derived().isEmpty());
    }

    @Test
    void removingTheFirstFactOfAnEngineRoutedChainRemovesEveryConclusionDownstream() throws Exception {
        String rules = AGE_TO_OVER18 + OVER18_TO_CHECKED;
        MiniRuleEngine engine = new MiniRuleEngine();
        RuleExecutionResult with = run(inline(AGE_FACT + rules), engine);
        RuleExecutionResult without = run(inline(rules), new MiniRuleEngine());

        assertEquals(1, engine.calls.get());
        assertTrue(derives(with, "#Over18"), keys(with).toString());
        assertTrue(derives(with, "#Checked"), keys(with).toString());
        assertFalse(derives(without, "#Over18"));
        assertFalse(derives(without, "#Checked"));
        assertEquals(Status.COMPLETED, without.status());
    }

    @Test
    void aConclusionWithTwoIndependentPremisesSurvivesLosingOneAndGoesWhenBothAreGone() throws Exception {
        String rules = A_TO_B + A2_TO_B;
        RuleExecutionResult both = run(inline("ClassAssertion(:A :a)ClassAssertion(:A2 :a)\n" + rules), new MiniRuleEngine());
        RuleExecutionResult onlyA = run(inline("ClassAssertion(:A :a)\n" + rules), new MiniRuleEngine());
        RuleExecutionResult onlyA2 = run(inline("ClassAssertion(:A2 :a)\n" + rules), new MiniRuleEngine());
        RuleExecutionResult neither = run(inline(rules), new MiniRuleEngine());

        assertTrue(derives(both, "#B>"));
        assertTrue(derives(onlyA, "#B>"));
        assertTrue(derives(onlyA2, "#B>"));
        assertFalse(derives(neither, "#B>"));
        assertTrue(neither.derived().isEmpty());
    }

    @Test
    void aDisabledRuleIsAbsentFromTheInputSoItsConclusionsAndItsListingAreAbsent() throws Exception {
        String facts = "ClassAssertion(:A :a)\n";
        RuleExecutionResult enabled = run(inline(facts + A_TO_B + B_TO_C), new MiniRuleEngine());
        RuleExecutionResult disabled = run(inline(facts + A_TO_B), new MiniRuleEngine());

        assertEquals(2, enabled.executedRules().size());
        assertTrue(derives(enabled, "#C>"));
        assertEquals(1, disabled.executedRules().size());
        assertFalse(derives(disabled, "#C>"));
        assertTrue(derives(disabled, "#B>"));
        assertFalse(disabled.executedRules().stream().anyMatch(r -> r.contains("#C")), disabled.executedRules().toString());
        assertEquals(Status.COMPLETED, disabled.status());
    }

    @Test
    void derivedFactsNeverBecomeInputsSoRunsAreDeterministicAndShrinkWhenAPremiseIsRemoved() throws Exception {
        OWLOntology ontology = inline("ClassAssertion(:A :a)ClassAssertion(:A2 :a)\n" + A_TO_B + B_TO_C + AGE_FACT + AGE_TO_OVER18 + OVER18_TO_CHECKED);
        Set<OWLAxiom> before = ontology.axioms().collect(Collectors.toSet());
        RuleExecution execution = RuleExecutionTest.execution(new MiniRuleEngine());

        RuleExecutionResult first = execution.run(ontology, "s1", RuleExecutionLimits.defaults());
        assertEquals(before, ontology.axioms().collect(Collectors.toSet()));
        RuleExecutionResult second = execution.run(ontology, "s2", RuleExecutionLimits.defaults());

        assertFalse(first.derived().isEmpty());
        assertEquals(keys(first), keys(second));
        assertEquals(before, ontology.axioms().collect(Collectors.toSet()));

        OWLAxiom premise = ontology.axioms(AxiomType.DATA_PROPERTY_ASSERTION).findFirst().get();
        ontology.getOWLOntologyManager().removeAxiom(ontology, premise);
        RuleExecutionResult smaller = execution.run(ontology, "s3", RuleExecutionLimits.defaults());

        assertTrue(smaller.derived().size() < first.derived().size());
        assertTrue(keys(first).containsAll(keys(smaller)));
        assertFalse(derives(smaller, "#Over18"));
        assertFalse(derives(smaller, "#Checked"));
        assertTrue(derives(smaller, "#C>"));
    }

    @Test
    void removingTheEngineRulesPremiseRemovesTheWholeDownstreamAlternatingChain() throws Exception {
        String text = Files.readString(RuleExecutionTest.DIR.resolve("15-alternating-chain.ofn"), StandardCharsets.UTF_8);
        String withoutAge = text.replace("DataPropertyAssertion(:hasAge :a \"30\"^^xsd:integer)", "");
        assertNotEquals(text, withoutAge);

        RuleExecutionResult with = run(RuleExecutionTest.load(text), new MiniRuleEngine());
        RuleExecutionResult without = run(RuleExecutionTest.load(withoutAge), new MiniRuleEngine());

        assertTrue(derives(with, "#Adult"));
        assertTrue(derives(with, "#Staff"));
        assertTrue(derives(with, "#Flagged"));
        assertFalse(derives(without, "#Adult"));
        assertFalse(derives(without, "#Staff"));
        assertFalse(derives(without, "#Flagged"));
        assertTrue(without.derived().isEmpty());
        assertEquals(Status.COMPLETED, without.status());
    }

    @Test
    void oneExecutionInstanceKeepsNoStateBetweenRunsWithDifferentRuleSets() throws Exception {
        String facts = "ClassAssertion(:A :a)\n";
        RuleExecution execution = RuleExecutionTest.execution(new MiniRuleEngine());
        OWLOntology shortChain = inline(facts + A_TO_B);
        OWLOntology longChain = inline(facts + A_TO_B + B_TO_C);

        RuleExecutionResult one = execution.run(shortChain, "s1", RuleExecutionLimits.defaults());
        RuleExecutionResult two = execution.run(longChain, "s1", RuleExecutionLimits.defaults());
        RuleExecutionResult three = execution.run(shortChain, "s1", RuleExecutionLimits.defaults());

        assertFalse(derives(one, "#C>"));
        assertTrue(derives(two, "#C>"));
        assertFalse(derives(three, "#C>"));
        assertEquals(keys(one), keys(three));
        assertTrue(keys(one).size() < keys(two).size());
    }
}
