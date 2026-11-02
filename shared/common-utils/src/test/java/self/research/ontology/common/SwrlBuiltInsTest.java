package self.research.ontology.common;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwrlBuiltInsTest {

    private static final String NS = "http://example.org/t#";
    private static final String SWRLB = "http://www.w3.org/2003/11/swrlb#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLClass person = df.getOWLClass(IRI.create(NS + "Person"));
    private final OWLClass adult = df.getOWLClass(IRI.create(NS + "Adult"));
    private final OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "hasAge"));
    private final OWLDataProperty name = df.getOWLDataProperty(IRI.create(NS + "hasName"));
    private final OWLNamedIndividual ann = df.getOWLNamedIndividual(IRI.create(NS + "ann"));
    private final OWLNamedIndividual kid = df.getOWLNamedIndividual(IRI.create(NS + "kid"));
    private final SWRLVariable x = df.getSWRLVariable(IRI.create(NS + "x"));
    private final SWRLVariable v = df.getSWRLVariable(IRI.create(NS + "v"));

    private SWRLRule rule(String op, int constant) {
        SWRLAtom builtIn = df.getSWRLBuiltInAtom(IRI.create(SWRLB + op), List.of(v, df.getSWRLLiteralArgument(df.getOWLLiteral(constant))));
        return df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), builtIn),
                List.of(df.getSWRLClassAtom(adult, x)));
    }

    private OWLOntology people(SWRLRule rule) throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLClassAssertionAxiom(person, ann));
        manager.addAxiom(o, df.getOWLClassAssertionAxiom(person, kid));
        manager.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(age, ann, 30));
        manager.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(age, kid, 9));
        manager.addAxiom(o, rule);
        return o;
    }

    private Set<OWLNamedIndividual> adults(OWLOntology o) {
        OWLReasoner r = new ReasonerFactory().createReasoner(o);
        try {
            return r.getInstances(adult, false).entities().collect(java.util.stream.Collectors.toSet());
        } finally {
            r.dispose();
        }
    }

    @Test
    void ontologyWithoutBuiltInRulesIsReturnedUntouched() throws Exception {
        OWLOntology o = manager.createOntology();
        PreparedOntology p = SwrlBuiltIns.prepareForHermit(o, null);
        assertSame(o, p.forHermit());
        assertNull(p.note());
    }

    @Test
    void greaterThanBecomesOwlAndHermitInfersTheAdult() throws Exception {
        OWLOntology o = people(rule("greaterThan", 17));
        PreparedOntology p = SwrlBuiltIns.prepareForHermit(o, null);
        assertEquals(1, p.translated());
        assertEquals(0, p.leftOut());
        assertEquals(Set.of(ann), adults(p.forHermit()));
        assertTrue(p.note().contains("1 rule was turned into OWL"));
    }

    @Test
    void everyComparisonKeepsTheRightPeople() throws Exception {
        assertEquals(Set.of(ann), adults(SwrlBuiltIns.prepareForHermit(people(rule("greaterThanOrEqual", 30)), null).forHermit()));
        assertEquals(Set.of(kid), adults(SwrlBuiltIns.prepareForHermit(people(rule("lessThan", 10)), null).forHermit()));
        assertEquals(Set.of(kid), adults(SwrlBuiltIns.prepareForHermit(people(rule("lessThanOrEqual", 9)), null).forHermit()));
        assertEquals(Set.of(ann), adults(SwrlBuiltIns.prepareForHermit(people(rule("equal", 30)), null).forHermit()));
        assertEquals(Set.of(ann), adults(SwrlBuiltIns.prepareForHermit(people(rule("notEqual", 9)), null).forHermit()));
    }

    @Test
    void constantOnTheLeftIsFlipped() throws Exception {
        SWRLAtom builtIn = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "lessThan"), List.of(df.getSWRLLiteralArgument(df.getOWLLiteral(17)), v));
        SWRLRule r = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), builtIn),
                List.of(df.getSWRLClassAtom(adult, x)));
        assertEquals(Set.of(ann), adults(SwrlBuiltIns.prepareForHermit(people(r), null).forHermit()));
    }

    @Test
    void twoComparisonsOnOneValueFormARange() throws Exception {
        SWRLAtom low = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "greaterThan"), List.of(v, df.getSWRLLiteralArgument(df.getOWLLiteral(5))));
        SWRLAtom high = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "lessThan"), List.of(v, df.getSWRLLiteralArgument(df.getOWLLiteral(12))));
        SWRLRule r = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), low, high),
                List.of(df.getSWRLClassAtom(adult, x)));
        assertEquals(Set.of(kid), adults(SwrlBuiltIns.prepareForHermit(people(r), null).forHermit()));
    }

    @Test
    void decimalConstantsAndIntegerValuesCompare() throws Exception {
        SWRLAtom builtIn = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "greaterThan"),
                List.of(v, df.getSWRLLiteralArgument(df.getOWLLiteral("17.5", OWL2Datatype.XSD_DECIMAL))));
        SWRLRule r = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), builtIn),
                List.of(df.getSWRLClassAtom(adult, x)));
        assertEquals(Set.of(ann), adults(SwrlBuiltIns.prepareForHermit(people(r), null).forHermit()));
    }

    @Test
    void textEqualityIsTranslated() throws Exception {
        SWRLAtom builtIn = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "equal"), List.of(v, df.getSWRLLiteralArgument(df.getOWLLiteral("Ann"))));
        SWRLRule r = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(name, x, v), builtIn),
                List.of(df.getSWRLClassAtom(adult, x)));
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLClassAssertionAxiom(person, ann));
        manager.addAxiom(o, df.getOWLClassAssertionAxiom(person, kid));
        manager.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(name, ann, "Ann"));
        manager.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(name, kid, "Bob"));
        manager.addAxiom(o, r);
        assertEquals(Set.of(ann), adults(SwrlBuiltIns.prepareForHermit(o, null).forHermit()));
    }

    @Test
    void textOrderingIsNotTranslated() {
        SWRLAtom builtIn = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "greaterThan"), List.of(v, df.getSWRLLiteralArgument(df.getOWLLiteral("M"))));
        SWRLRule r = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(name, x, v), builtIn),
                List.of(df.getSWRLClassAtom(adult, x)));
        assertTrue(SwrlComparisonTranslator.translate(r, df).isEmpty());
    }

    @Test
    void otherShapesAreNotTranslated() {
        SWRLAtom add = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "add"), List.of(v, v, df.getSWRLLiteralArgument(df.getOWLLiteral(1))));
        SWRLRule math = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), add),
                List.of(df.getSWRLClassAtom(adult, x)));
        assertTrue(SwrlComparisonTranslator.translate(math, df).isEmpty());
        SWRLVariable y = df.getSWRLVariable(IRI.create(NS + "y"));
        SWRLAtom cmp = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "greaterThan"), List.of(v, df.getSWRLLiteralArgument(df.getOWLLiteral(1))));
        SWRLRule otherSubject = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, y, v), cmp),
                List.of(df.getSWRLClassAtom(adult, x)));
        assertTrue(SwrlComparisonTranslator.translate(otherSubject, df).isEmpty());
        SWRLRule dataHead = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), cmp),
                List.of(df.getSWRLDataPropertyAtom(name, x, v)));
        assertTrue(SwrlComparisonTranslator.translate(dataHead, df).isEmpty());
    }

    @Test
    void engineResultIsMergedForRulesThatCannotBeTranslated() throws Exception {
        SWRLAtom add = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "add"), List.of(v, v, df.getSWRLLiteralArgument(df.getOWLLiteral(1))));
        SWRLRule math = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), add),
                List.of(df.getSWRLClassAtom(adult, x)));
        OWLOntology o = people(math);
        RuleStage stage = (ontology, rules) -> Optional.of(Set.of(df.getOWLClassAssertionAxiom(adult, kid)));
        PreparedOntology p = SwrlBuiltIns.prepareForHermit(o, stage);
        assertEquals(1, p.engineRules());
        assertEquals(0, p.leftOut());
        assertEquals(Set.of(kid), adults(p.forHermit()));
        assertTrue(p.note().contains("applied with the rule engine"));
    }

    @Test
    void unavailableOrFailingEngineLeavesTheRuleOutWithANote() throws Exception {
        SWRLAtom add = df.getSWRLBuiltInAtom(IRI.create(SWRLB + "add"), List.of(v, v, df.getSWRLLiteralArgument(df.getOWLLiteral(1))));
        SWRLRule math = df.getSWRLRule(List.of(df.getSWRLClassAtom(person, x), df.getSWRLDataPropertyAtom(age, x, v), add),
                List.of(df.getSWRLClassAtom(adult, x)));
        PreparedOntology down = SwrlBuiltIns.prepareForHermit(people(math), (o, r) -> Optional.empty());
        assertEquals(1, down.leftOut());
        assertTrue(down.note().contains("could not be reached"));
        PreparedOntology thrown = SwrlBuiltIns.prepareForHermit(people(math), (o, r) -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(1, thrown.leftOut());
        PreparedOntology none = SwrlBuiltIns.prepareForHermit(people(math), null);
        assertEquals(1, none.leftOut());
        assertNotNull(none.note());
        assertFalse(adults(none.forHermit()).contains(kid));
    }
}
