package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticCvsRuleExecutionTest {

    private static final Path FIXTURES = Path.of("..", "..", "test-data");
    private static final String[] OUTCOMES = {"SourceVerified", "ReadyForReview", "UnexpectedOutcome"};
    private static final Set<String> OUTCOME_TYPES = Set.of(OUTCOMES);

    @Test
    void allTwentyFourRulesProduceExactlyTheExpectedOutcomes() throws Exception {
        var ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(FIXTURES.resolve("synthetic-cvs-demo.owl").toFile());
        int assertedAxioms = ontology.getAxiomCount();
        assertEquals(24, ontology.getAxiomCount(AxiomType.SWRL_RULE));
        assertEquals(3, ontology.getAxiomCount(AxiomType.SUB_PROPERTY_CHAIN_OF));

        var engine = new MiniRuleEngine();
        var result = new RuleExecution(engine, new ReasonerFactory()::createReasoner, "HermiT")
                .runForDisplay(ontology, "synthetic-cvs", RuleExecutionLimits.defaults());
        Set<String> actual = new TreeSet<>();
        for (var fact : result.derived()) {
            if (fact.axiom() instanceof OWLClassAssertionAxiom axiom
                    && axiom.getIndividual().isNamed() && !axiom.getClassExpression().isAnonymous()) {
                String type = axiom.getClassExpression().asOWLClass().getIRI().getShortForm();
                if (OUTCOME_TYPES.contains(type)) {
                    assertEquals(RuleExecutionResult.Origin.RULE_ENGINE, fact.origin(), axiom.toString());
                    actual.add(axiom.getIndividual().asOWLNamedIndividual().getIRI().getShortForm() + "=" + type);
                }
            }
        }

        Set<String> expected = expectedOutcomes();
        assertEquals(14, expected.size());
        assertTrue(result.complete(), result.message());
        assertEquals(24, result.executedRules().size());
        assertEquals(1, result.engineRules());
        assertTrue(engine.calls.get() > 1, "Should run the rule engine again with inferred OWL facts");
        assertEquals(expected, actual);
        assertEquals(assertedAxioms, ontology.getAxiomCount());
    }

    @Test
    void displayIncludesOwlFactsOutsideTheRulesAndDistinguishesSharedPredicates() throws Exception {
        var ontology = displayFixture();
        var result = new RuleExecution(null, new ReasonerFactory()::createReasoner, "HermiT")
                .runForDisplay(ontology, "display", RuleExecutionLimits.defaults());
        assertTrue(result.complete(), result.message());
        var df = ontology.getOWLOntologyManager().getOWLDataFactory();
        String ns = "https://example.org/display#";
        var a = df.getOWLNamedIndividual(IRI.create(ns + "a"));
        var b = df.getOWLNamedIndividual(IRI.create(ns + "b"));
        var c = df.getOWLNamedIndividual(IRI.create(ns + "c"));
        var outcome = df.getOWLClass(IRI.create(ns + "Outcome"));
        var unrelated = df.getOWLClass(IRI.create(ns + "Unrelated"));
        var grandparent = df.getOWLObjectProperty(IRI.create(ns + "grandparent"));
        var origins = result.derived().stream().collect(Collectors.toMap(
                RuleExecutionResult.DerivedFact::axiom, RuleExecutionResult.DerivedFact::origin));

        assertEquals(RuleExecutionResult.Origin.RULE_ENGINE, origins.get(df.getOWLClassAssertionAxiom(outcome, a)));
        assertEquals(RuleExecutionResult.Origin.OWL_REASONER, origins.get(df.getOWLClassAssertionAxiom(outcome, b)));
        assertEquals(RuleExecutionResult.Origin.OWL_REASONER, origins.get(df.getOWLClassAssertionAxiom(unrelated, a)));
        assertEquals(RuleExecutionResult.Origin.OWL_REASONER,
                origins.get(df.getOWLObjectPropertyAssertionAxiom(grandparent, a, c)));
    }

    @Test
    void failedAttributionKeepsTheFactsAndDisclosesTheMissingClassification() throws Exception {
        var result = new RuleExecution(null, input -> {
            if (input.axioms(AxiomType.SWRL_RULE).findAny().isEmpty()) {
                throw new UnsupportedOperationException("rule-free comparison unavailable");
            }
            return new ReasonerFactory().createReasoner(input);
        }, "HermiT").runForDisplay(displayFixture(), "display", RuleExecutionLimits.defaults());

        assertEquals(RuleExecutionResult.Status.PARTIAL_LIMIT_REACHED, result.status());
        assertTrue(!result.derived().isEmpty());
        assertTrue(result.derived().stream().allMatch(f -> f.origin() == RuleExecutionResult.Origin.UNDETERMINED));
    }

    private static OWLOntology displayFixture() throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new StringDocumentSource("""
                        Prefix(:=<https://example.org/display#>)
                        Ontology(
                          Declaration(Class(:Source))
                          Declaration(Class(:Outcome))
                          Declaration(Class(:Unrelated))
                          Declaration(ObjectProperty(:parent))
                          Declaration(ObjectProperty(:grandparent))
                          Declaration(NamedIndividual(:a))
                          Declaration(NamedIndividual(:b))
                          Declaration(NamedIndividual(:c))
                          ClassAssertion(:Source :a)
                          ObjectPropertyAssertion(:parent :a :b)
                          ObjectPropertyAssertion(:parent :b :c)
                          SubObjectPropertyOf(ObjectPropertyChain(:parent :parent) :grandparent)
                          ObjectPropertyDomain(:parent :Unrelated)
                          ObjectPropertyRange(:parent :Outcome)
                          DLSafeRule(Body(ClassAtom(:Source Variable(:x))) Head(ClassAtom(:Outcome Variable(:x))))
                        )
                        """));
    }

    private static Set<String> expectedOutcomes() throws Exception {
        Set<String> expected = new TreeSet<>();
        for (String line : Files.readAllLines(FIXTURES.resolve("synthetic-cvs-expected.csv")).stream().skip(1).toList()) {
            String[] row = line.split(",");
            for (int i = 0; i < OUTCOMES.length; i++) {
                if (Boolean.parseBoolean(row[i + 1])) {
                    expected.add(row[0] + "=" + OUTCOMES[i]);
                }
            }
        }
        return expected;
    }
}
