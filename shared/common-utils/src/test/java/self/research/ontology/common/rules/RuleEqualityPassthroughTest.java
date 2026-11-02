package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.common.RuleStage;
import self.research.ontology.common.rules.RuleExecutionResult.Origin;
import self.research.ontology.common.rules.RuleExecutionResult.Status;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEqualityPassthroughTest {

    private static final String DECL = "Declaration(Class(:Person))\nDeclaration(Class(:Agent))\nDeclaration(ObjectProperty(:knows))\n"
            + "Declaration(DataProperty(:hasAge))\n"
            + "Declaration(NamedIndividual(:a))\nDeclaration(NamedIndividual(:b))\nClassAssertion(:Person :a)\n"
            + "ObjectPropertyAssertion(:knows :a :b)\nDataPropertyAssertion(:hasAge :a \"30\"^^xsd:integer)\n";

    private static final String ENGINE_SAME_AS = "DLSafeRule(Body(ObjectPropertyAtom(:knows Variable(:x) Variable(:y)) "
            + "DifferentIndividualsAtom(Variable(:x) Variable(:y))) Head(SameIndividualAtom(Variable(:x) Variable(:y))))\n";

    private static final String NATIVE_SAME_AS = "DLSafeRule(Body(ObjectPropertyAtom(:knows Variable(:x) Variable(:y))) "
            + "Head(SameIndividualAtom(Variable(:x) Variable(:y))))\n";

    private static RuleStage sameAsEngine() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLNamedIndividual a = df.getOWLNamedIndividual(IRI.create("http://example.org/rc#a"));
        OWLNamedIndividual b = df.getOWLNamedIndividual(IRI.create("http://example.org/rc#b"));
        return (ontology, rules) -> Optional.<Set<OWLAxiom>>of(Set.of(df.getOWLSameIndividualAxiom(a, b)));
    }

    private static boolean anySameAsDerived(RuleExecutionResult result) {
        return result.derived().stream().anyMatch(d -> d.axiom() instanceof org.semanticweb.owlapi.model.OWLSameIndividualAxiom
                || d.axiom() instanceof org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom);
    }

    @Test
    void sameAsConcludedByTheRuleEngineIsDroppedAndReportedAsALimit() throws Exception {
        OWLOntology ontology = RuleExecutionTest.inline(DECL + ENGINE_SAME_AS);
        RuleExecutionResult result = RuleExecutionTest.run(ontology, sameAsEngine());

        assertEquals(Status.COMPLETED, result.status());
        assertFalse(anySameAsDerived(result));
        assertTrue(result.limitations().stream().anyMatch(l -> l.contains("1 derived sameAs or differentFrom conclusion from the rule engine was not transferred")),
                result.limitations().toString());
        assertFalse(RuleExecutionTest.entails(result.working(), "ClassAssertion(:Person :b)"));
        assertFalse(RuleExecutionTest.derives(result, "ClassAssertion(<http://example.org/rc#Person> <http://example.org/rc#b>)"));
    }

    @Test
    void sameAsConcludedByARuleHermitCanRunMergesTheIndividualsButIsNotListedAsAFact() throws Exception {
        String agentRule = "DLSafeRule(Body(ClassAtom(:Person Variable(:x))) Head(ClassAtom(:Agent Variable(:x))))\n";
        OWLOntology ontology = RuleExecutionTest.inline(DECL + NATIVE_SAME_AS + agentRule);
        RuleExecutionResult result = RuleExecutionTest.run(ontology, new MiniRuleEngine());

        assertEquals(Status.COMPLETED, result.status());
        assertFalse(anySameAsDerived(result));
        assertTrue(RuleExecutionTest.entails(result.working(), "SameIndividual(:a :b)"));
        assertTrue(RuleExecutionTest.derives(result, "ClassAssertion(<http://example.org/rc#Agent> <http://example.org/rc#b>)"));
        assertTrue(result.derived().stream().allMatch(d -> d.origin() == Origin.OWL_REASONER));
    }

    @Test
    void aBuiltInRuleThatConcludesSameAsIsSkippedWithAReason() throws Exception {
        String rule = "DLSafeRule(Body(ObjectPropertyAtom(:knows Variable(:x) Variable(:y)) DataPropertyAtom(:hasAge Variable(:x) Variable(:n)) "
                + "BuiltInAtom(swrlb:greaterThan Variable(:n) \"17\"^^xsd:integer)) Head(SameIndividualAtom(Variable(:x) Variable(:y))))\n";
        OWLOntology ontology = RuleExecutionTest.inline(DECL + rule);
        RuleExecutionResult result = RuleExecutionTest.run(ontology, new MiniRuleEngine());

        assertEquals(Status.PARTIAL_RULES_SKIPPED, result.status());
        assertEquals(1, result.skippedRules().size());
        assertTrue(result.skippedRules().get(0).reason().contains("concludes sameAs or differentFrom is not supported"));
        assertFalse(anySameAsDerived(result));
    }
}
