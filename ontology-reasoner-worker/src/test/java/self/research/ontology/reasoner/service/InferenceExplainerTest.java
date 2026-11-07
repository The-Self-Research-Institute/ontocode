package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import self.research.ontology.reasoner.service.InferenceExplainer.Outcome;
import self.research.ontology.reasoner.service.InferenceExplainer.Request;
import self.research.ontology.reasoner.service.InferenceExplainer.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceExplainerTest {

    static final String NS = "http://example.org/explain#";
    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    final OWLDataFactory df = manager.getOWLDataFactory();
    final OWLOntology ontology = create();
    final List<OWLAxiom> added = new ArrayList<>();

    private OWLOntology create() {
        try {
            return manager.createOntology(IRI.create("http://example.org/explain"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    OWLClass cls(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    OWLNamedIndividual ind(String name) {
        return df.getOWLNamedIndividual(IRI.create(NS + name));
    }

    OWLObjectProperty prop(String name) {
        return df.getOWLObjectProperty(IRI.create(NS + name));
    }

    OWLDataProperty dprop(String name) {
        return df.getOWLDataProperty(IRI.create(NS + name));
    }

    OWLAxiom add(OWLAxiom axiom) {
        manager.addAxiom(ontology, axiom);
        added.add(axiom);
        return axiom;
    }

    static String iri(String name) {
        return NS + name;
    }

    InferenceExplainer explainer() {
        return new InferenceExplainer(20_000, 50_000, 500_000);
    }

    Outcome explain(Request request) {
        return explainer().explain(ontology, request, ReasonerType.HERMIT, () -> false);
    }

    static Request classAssertion(String individual, String cls) {
        return new Request("ClassAssertion", iri(individual), RDF_TYPE, iri(cls), null, null);
    }

    static Request subClassOf(String sub, String sup) {
        return new Request("SubClassOf", iri(sub), null, iri(sup), null, null);
    }

    @Test
    void subclassChainExplainedByTheChainAndTheAssertionOnly() {
        add(df.getOWLSubClassOfAxiom(cls("Student"), cls("Person")));
        OWLAxiom a = add(df.getOWLSubClassOfAxiom(cls("Graduate"), cls("Student")));
        OWLAxiom b = add(df.getOWLSubClassOfAxiom(cls("Student"), cls("Person")));
        OWLAxiom c = add(df.getOWLClassAssertionAxiom(cls("Graduate"), ind("ann")));
        add(df.getOWLSubClassOfAxiom(cls("Pet"), cls("Animal")));
        add(df.getOWLClassAssertionAxiom(cls("Pet"), ind("rex")));
        add(df.getOWLClassAssertionAxiom(cls("Student"), ind("bob")));

        Outcome outcome = explain(classAssertion("ann", "Person"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertTrue(outcome.minimal());
        assertEquals(Set.of(a, b, c), Set.copyOf(outcome.axioms()));
        assertFalse(outcome.usesRules());
        assertEquals("HermiT", outcome.reasoner());
    }

    @Test
    void subclassFactBetweenNamedClassesIsExplained() {
        OWLAxiom a = add(df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        OWLAxiom b = add(df.getOWLSubClassOfAxiom(cls("B"), cls("C")));
        add(df.getOWLSubClassOfAxiom(cls("A"), cls("D")));

        Outcome outcome = explain(subClassOf("A", "C"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals(Set.of(a, b), Set.copyOf(outcome.axioms()));
    }

    @Test
    void domainAxiomExplainsTheTypeOfTheSubject() {
        OWLAxiom domain = add(df.getOWLObjectPropertyDomainAxiom(prop("owns"), cls("Owner")));
        OWLAxiom fact = add(df.getOWLObjectPropertyAssertionAxiom(prop("owns"), ind("ann"), ind("car")));
        add(df.getOWLObjectPropertyRangeAxiom(prop("owns"), cls("Thing1")));

        Outcome outcome = explain(classAssertion("ann", "Owner"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals(Set.of(domain, fact), Set.copyOf(outcome.axioms()));
    }

    @Test
    void rangeAxiomExplainsTheTypeOfTheObject() {
        OWLAxiom range = add(df.getOWLObjectPropertyRangeAxiom(prop("owns"), cls("Item")));
        OWLAxiom fact = add(df.getOWLObjectPropertyAssertionAxiom(prop("owns"), ind("ann"), ind("car")));

        Outcome outcome = explain(classAssertion("car", "Item"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals(Set.of(range, fact), Set.copyOf(outcome.axioms()));
    }

    @Test
    void inverseSymmetricAndTransitivePropertiesGiveThePropertyFacts() {
        OWLAxiom inverse = add(df.getOWLInverseObjectPropertiesAxiom(prop("parentOf"), prop("childOf")));
        OWLAxiom fact = add(df.getOWLObjectPropertyAssertionAxiom(prop("parentOf"), ind("ann"), ind("bob")));
        Outcome viaInverse = explain(new Request("ObjectPropertyAssertion", iri("bob"), iri("childOf"), iri("ann"), null, null));
        assertEquals(Status.EXPLAINED, viaInverse.status());
        assertEquals(Set.of(inverse, fact), Set.copyOf(viaInverse.axioms()));

        OWLAxiom symmetric = add(df.getOWLSymmetricObjectPropertyAxiom(prop("friendOf")));
        OWLAxiom friend = add(df.getOWLObjectPropertyAssertionAxiom(prop("friendOf"), ind("ann"), ind("cy")));
        Outcome viaSymmetry = explain(new Request("ObjectPropertyAssertion", iri("cy"), iri("friendOf"), iri("ann"), null, null));
        assertEquals(Set.of(symmetric, friend), Set.copyOf(viaSymmetry.axioms()));

        OWLAxiom transitive = add(df.getOWLTransitiveObjectPropertyAxiom(prop("partOf")));
        OWLAxiom first = add(df.getOWLObjectPropertyAssertionAxiom(prop("partOf"), ind("x"), ind("y")));
        OWLAxiom second = add(df.getOWLObjectPropertyAssertionAxiom(prop("partOf"), ind("y"), ind("z")));
        Outcome viaTransitive = explain(new Request("ObjectPropertyAssertion", iri("x"), iri("partOf"), iri("z"), null, null));
        assertEquals(Status.EXPLAINED, viaTransitive.status());
        assertEquals(Set.of(transitive, first, second), Set.copyOf(viaTransitive.axioms()));
    }

    @Test
    void definedClassMembershipIsExplainedByTheDefinitionAndTheParts() {
        OWLClassExpression definition = df.getOWLObjectIntersectionOf(cls("Person"),
                df.getOWLObjectSomeValuesFrom(prop("owns"), cls("Pet")));
        OWLAxiom equivalent = add(df.getOWLEquivalentClassesAxiom(cls("PetOwner"), definition));
        OWLAxiom person = add(df.getOWLClassAssertionAxiom(cls("Person"), ind("ann")));
        OWLAxiom owns = add(df.getOWLObjectPropertyAssertionAxiom(prop("owns"), ind("ann"), ind("rex")));
        OWLAxiom pet = add(df.getOWLClassAssertionAxiom(cls("Pet"), ind("rex")));
        add(df.getOWLClassAssertionAxiom(cls("Person"), ind("zed")));

        Outcome outcome = explain(classAssertion("ann", "PetOwner"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals(Set.of(equivalent, person, owns, pet), Set.copyOf(outcome.axioms()));
    }

    @Test
    void dataPropertyAssertionAndEquivalentClassesAndSameIndividualAreSupported() {
        OWLAxiom sub = add(df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        OWLAxiom sup = add(df.getOWLSubClassOfAxiom(cls("B"), cls("A")));
        Outcome equivalent = explain(new Request("EquivalentClasses", iri("A"), null, iri("B"), null, null));
        assertEquals(Status.EXPLAINED, equivalent.status());
        assertEquals(Set.of(sub, sup), Set.copyOf(equivalent.axioms()));

        OWLAxiom functional = add(df.getOWLFunctionalObjectPropertyAxiom(prop("spouse")));
        OWLAxiom one = add(df.getOWLObjectPropertyAssertionAxiom(prop("spouse"), ind("ann"), ind("p1")));
        OWLAxiom two = add(df.getOWLObjectPropertyAssertionAxiom(prop("spouse"), ind("ann"), ind("p2")));
        Outcome same = explain(new Request("SameIndividual", iri("p1"), null, iri("p2"), null, null));
        assertEquals(Status.EXPLAINED, same.status());
        assertEquals(Set.of(functional, one, two), Set.copyOf(same.axioms()));

        OWLAxiom subProperty = add(df.getOWLSubObjectPropertyOfAxiom(prop("hasMother"), prop("hasParent")));
        OWLAxiom mother = add(df.getOWLObjectPropertyAssertionAxiom(prop("hasMother"), ind("ann"), ind("mum")));
        Outcome inherited = explain(new Request("ObjectPropertyAssertion", iri("ann"), iri("hasParent"), iri("mum"), null, null));
        assertEquals(Set.of(subProperty, mother), Set.copyOf(inherited.axioms()));

        Outcome subPropertyFact = explain(new Request("SubObjectPropertyOf", iri("hasMother"), null, iri("hasParent"), null, null));
        assertEquals(Status.ASSERTED, subPropertyFact.status());

        OWLAxiom dataFact = add(df.getOWLDataPropertyAssertionAxiom(dprop("age"), ind("ann"), 41));
        OWLAxiom sub2 = add(df.getOWLSubDataPropertyOfAxiom(dprop("age"), dprop("hasNumber")));
        Outcome data = explain(new Request("DataPropertyAssertion", iri("ann"), iri("hasNumber"), null, "41",
                "http://www.w3.org/2001/XMLSchema#integer"));
        assertEquals(Status.EXPLAINED, data.status());
        assertEquals(Set.of(dataFact, sub2), Set.copyOf(data.axioms()));
    }

    @Test
    void domainAndRangeFactsBetweenNamedEntitiesAreExplained() {
        OWLAxiom domain = add(df.getOWLObjectPropertyDomainAxiom(prop("p"), cls("A")));
        OWLAxiom sub = add(df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        Outcome inheritedDomain = explain(new Request("ObjectPropertyDomain", iri("p"), null, iri("B"), null, null));
        assertEquals(Status.EXPLAINED, inheritedDomain.status());
        assertEquals(Set.of(domain, sub), Set.copyOf(inheritedDomain.axioms()));

        OWLAxiom range = add(df.getOWLObjectPropertyRangeAxiom(prop("q"), cls("C")));
        OWLAxiom subC = add(df.getOWLSubClassOfAxiom(cls("C"), cls("D")));
        Outcome inheritedRange = explain(new Request("ObjectPropertyRange", iri("q"), null, iri("D"), null, null));
        assertEquals(Set.of(range, subC), Set.copyOf(inheritedRange.axioms()));

        OWLAxiom invAxiom = add(df.getOWLInverseObjectPropertiesAxiom(prop("m"), prop("n")));
        Outcome inverse = explain(new Request("InverseObjectProperties", iri("n"), null, iri("m"), null, null));
        assertEquals(Status.ASSERTED, inverse.status());
        assertEquals(List.of(inverse.conclusion()), inverse.axioms());
        assertTrue(ontology.containsAxiom(invAxiom));
    }

    @Test
    void ruleIsLeftOutWhenTheFactFollowsWithoutIt() {
        add(df.getOWLSubClassOfAxiom(cls("Graduate"), cls("Person")));
        OWLAxiom member = add(df.getOWLClassAssertionAxiom(cls("Graduate"), ind("ann")));
        add(rule("Graduate", "Person"));

        Outcome outcome = explain(classAssertion("ann", "Person"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertFalse(outcome.usesRules());
        assertTrue(outcome.axioms().contains(member));
        assertTrue(outcome.axioms().stream().noneMatch(a -> a instanceof SWRLRule));
    }

    @Test
    void ruleIsIncludedAndMarkedWhenTheFactReallyNeedsIt() {
        OWLAxiom member = add(df.getOWLClassAssertionAxiom(cls("Employee"), ind("ann")));
        OWLAxiom rule = add(rule("Employee", "Taxpayer"));
        add(df.getOWLClassAssertionAxiom(cls("Unrelated"), ind("bob")));

        Outcome outcome = explain(classAssertion("ann", "Taxpayer"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertTrue(outcome.usesRules());
        assertEquals(Set.of(member, rule), Set.copyOf(outcome.axioms()));
    }

    SWRLRule rule(String bodyClass, String headClass) {
        SWRLVariable x = df.getSWRLVariable(IRI.create(NS + "x"));
        SWRLAtom body = df.getSWRLClassAtom(cls(bodyClass), x);
        SWRLAtom head = df.getSWRLClassAtom(cls(headClass), x);
        return df.getSWRLRule(Set.of(body), Set.of(head));
    }

    @Test
    void notEntailedFactSaysSo() {
        add(df.getOWLSubClassOfAxiom(cls("Graduate"), cls("Person")));
        add(df.getOWLClassAssertionAxiom(cls("Graduate"), ind("ann")));

        Outcome outcome = explain(classAssertion("ann", "Pet"));

        assertEquals(Status.NOT_ENTAILED, outcome.status());
        assertTrue(outcome.axioms().isEmpty());
        assertTrue(outcome.message().contains("does not conclude"));
    }

    @Test
    void directlyAssertedFactIsReportedAsAsserted() {
        OWLAxiom member = add(df.getOWLClassAssertionAxiom(cls("Graduate"), ind("ann")));
        add(df.getOWLSubClassOfAxiom(cls("Graduate"), cls("Person")));

        Outcome outcome = explain(classAssertion("ann", "Graduate"));

        assertEquals(Status.ASSERTED, outcome.status());
        assertEquals(List.of(member), outcome.axioms());
        assertEquals(0, outcome.entailmentChecks());
    }

    @Test
    void unsupportedKindIsRefusedWithTheSupportedList() {
        add(df.getOWLSubClassOfAxiom(cls("A"), cls("B")));

        Outcome outcome = explain(new Request("DisjointClasses", iri("A"), null, iri("B"), null, null));

        assertEquals(Status.UNSUPPORTED, outcome.status());
        assertTrue(outcome.message().contains("ClassAssertion"));
    }

    @Test
    void incompleteFactIsAFailureNotACrash() {
        Outcome outcome = explain(new Request("ClassAssertion", null, RDF_TYPE, iri("A"), null, null));

        assertEquals(Status.FAILED, outcome.status());
    }

    @Test
    void tooLargeOntologyIsRefusedWithAPlainMessage() {
        for (int i = 0; i < 30; i++) {
            add(df.getOWLClassAssertionAxiom(cls("C" + i), ind("i" + i)));
        }
        add(df.getOWLSubClassOfAxiom(cls("C1"), cls("C2")));

        Outcome outcome = new InferenceExplainer(20_000, 10, 20)
                .explain(ontology, classAssertion("i1", "C2"), ReasonerType.HERMIT, () -> false);

        assertEquals(Status.TOO_LARGE, outcome.status());
        assertTrue(outcome.message().contains("31 axioms"));
        assertTrue(outcome.message().contains("ELK"));
    }

    @Test
    void cancelledBeforeAnyWorkReportsCancelled() {
        add(df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        add(df.getOWLClassAssertionAxiom(cls("A"), ind("x")));
        AtomicBoolean cancel = new AtomicBoolean(true);

        Outcome outcome = explainer().explain(ontology, classAssertion("x", "B"), ReasonerType.HERMIT, cancel::get);

        assertEquals(Status.CANCELLED, outcome.status());
    }

    @Test
    void tinyBudgetOnAHardOntologyTimesOutQuickly() throws Exception {
        OWLOntology hard = HermitInterruptTest.pigeonhole(12, 11);
        OWLOntologyManager other = hard.getOWLOntologyManager();
        OWLDataFactory otherDf = other.getOWLDataFactory();
        other.addAxiom(hard, otherDf.getOWLSubClassOfAxiom(otherDf.getOWLClass(IRI.create(NS + "Q")), otherDf.getOWLClass(IRI.create("http://example.org/P0H0"))));
        other.addAxiom(hard, otherDf.getOWLClassAssertionAxiom(otherDf.getOWLClass(IRI.create(NS + "Q")), otherDf.getOWLNamedIndividual(IRI.create(NS + "k"))));

        long begin = System.currentTimeMillis();
        Outcome outcome = new InferenceExplainer(300, 50_000, 500_000)
                .explain(hard, classAssertion("k", "R"), ReasonerType.HERMIT, () -> false);
        long took = System.currentTimeMillis() - begin;

        assertTrue(outcome.status() == Status.TIMEOUT || outcome.status() == Status.EXPLAINED, outcome.status().name());
        assertTrue(took < 15_000, "took " + took);
        if (outcome.status() == Status.TIMEOUT) {
            assertTrue(outcome.budgetHit());
            assertTrue(outcome.axioms().isEmpty());
        }
    }

    @Test
    void cancellingWhileHermitIsBusyStopsPromptly() throws Exception {
        OWLOntology hard = HermitInterruptTest.pigeonhole(12, 11);
        OWLOntologyManager other = hard.getOWLOntologyManager();
        OWLDataFactory otherDf = other.getOWLDataFactory();
        OWLAxiom q = otherDf.getOWLClassAssertionAxiom(otherDf.getOWLClass(IRI.create(NS + "Q")), otherDf.getOWLNamedIndividual(IRI.create(NS + "k")));
        other.addAxiom(hard, q);
        other.addAxiom(hard, otherDf.getOWLSubClassOfAxiom(otherDf.getOWLClass(IRI.create(NS + "Q")), otherDf.getOWLClass(IRI.create("http://example.org/P0H0"))));
        AtomicBoolean cancel = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            cancel.set(true);
        });
        canceller.setDaemon(true);
        canceller.start();

        long begin = System.currentTimeMillis();
        Outcome outcome = new InferenceExplainer(120_000, 50_000, 500_000)
                .explain(hard, classAssertion("k", "R"), ReasonerType.HERMIT, cancel::get);
        long took = System.currentTimeMillis() - begin;

        assertTrue(outcome.status() == Status.CANCELLED || outcome.status() == Status.EXPLAINED, outcome.status().name());
        assertTrue(took < 20_000, "took " + took);
    }

    @Test
    void elkExplainsAnElSubclassChainWhenAsked() {
        OWLAxiom a = add(df.getOWLSubClassOfAxiom(cls("Graduate"), cls("Student")));
        OWLAxiom b = add(df.getOWLSubClassOfAxiom(cls("Student"), cls("Person")));
        OWLAxiom c = add(df.getOWLClassAssertionAxiom(cls("Graduate"), ind("ann")));
        add(df.getOWLClassAssertionAxiom(cls("Pet"), ind("rex")));

        Outcome outcome = explainer().explain(ontology, classAssertion("ann", "Person"), ReasonerType.ELK, () -> false);

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals("ELK", outcome.reasoner());
        assertFalse(outcome.reasonerFallback());
        assertEquals(Set.of(a, b, c), Set.copyOf(outcome.axioms()));
    }

    @Test
    void elkRequestedForAKindItCannotCheckFallsBackToHermit() {
        OWLAxiom inverse = add(df.getOWLInverseObjectPropertiesAxiom(prop("parentOf"), prop("childOf")));
        OWLAxiom fact = add(df.getOWLObjectPropertyAssertionAxiom(prop("parentOf"), ind("ann"), ind("bob")));

        Outcome outcome = explainer().explain(ontology,
                new Request("ObjectPropertyAssertion", iri("bob"), iri("childOf"), iri("ann"), null, null),
                ReasonerType.ELK, () -> false);

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals("HermiT", outcome.reasoner());
        assertTrue(outcome.reasonerFallback());
        assertEquals(Set.of(inverse, fact), Set.copyOf(outcome.axioms()));
    }

    @Test
    void elkThatMissesAFactOutsideItsFragmentFallsBackToHermit() {
        OWLAxiom split = add(df.getOWLSubClassOfAxiom(cls("A"), df.getOWLObjectUnionOf(cls("B"), cls("C"))));
        OWLAxiom b = add(df.getOWLSubClassOfAxiom(cls("B"), cls("D")));
        OWLAxiom c = add(df.getOWLSubClassOfAxiom(cls("C"), cls("D")));
        OWLAxiom member = add(df.getOWLClassAssertionAxiom(cls("A"), ind("x")));

        Outcome outcome = explainer().explain(ontology, classAssertion("x", "D"), ReasonerType.ELK, () -> false);

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals("HermiT", outcome.reasoner());
        assertTrue(outcome.reasonerFallback());
        assertEquals(Set.of(split, b, c, member), Set.copyOf(outcome.axioms()));
    }

    @Test
    void elkSizeGuardIsHigherThanTheHermitOne() {
        for (int i = 0; i < 30; i++) {
            add(df.getOWLClassAssertionAxiom(cls("C" + i), ind("i" + i)));
        }
        add(df.getOWLSubClassOfAxiom(cls("C1"), cls("C2")));

        Outcome outcome = new InferenceExplainer(20_000, 10, 100)
                .explain(ontology, classAssertion("i1", "C2"), ReasonerType.ELK, () -> false);

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals("ELK", outcome.reasoner());
    }

    @Test
    void sameResultEveryTimeForTheSameOntology() {
        add(df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        add(df.getOWLSubClassOfAxiom(cls("A"), cls("B2")));
        add(df.getOWLSubClassOfAxiom(cls("B"), cls("C")));
        add(df.getOWLSubClassOfAxiom(cls("B2"), cls("C")));
        add(df.getOWLClassAssertionAxiom(cls("A"), ind("x")));

        List<OWLAxiom> first = explain(classAssertion("x", "C")).axioms();
        for (int i = 0; i < 3; i++) {
            assertEquals(first, explain(classAssertion("x", "C")).axioms());
        }
        assertEquals(3, first.size());
    }

    @Test
    void unionOfNamedClassesCanBeTheExplainedClass() {
        OWLAxiom tuna = add(df.getOWLClassAssertionAxiom(cls("TunaTopping"), ind("t1")));
        OWLAxiom sub = add(df.getOWLSubClassOfAxiom(cls("TunaTopping"), cls("SeafoodTopping")));
        add(df.getOWLClassAssertionAxiom(cls("MeatTopping"), ind("t2")));

        Outcome outcome = explain(new Request("ClassAssertion", iri("t1"), RDF_TYPE, null, null, null,
                List.of(iri("MeatTopping"), iri("SeafoodTopping")), "UNION"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals(Set.of(tuna, sub), Set.copyOf(outcome.axioms()));
    }

    @Test
    void intersectionOfNamedClassesCanBeTheExplainedClass() {
        OWLAxiom a = add(df.getOWLClassAssertionAxiom(cls("A"), ind("x")));
        OWLAxiom b = add(df.getOWLClassAssertionAxiom(cls("B"), ind("x")));

        Outcome outcome = explain(new Request("ClassAssertion", iri("x"), RDF_TYPE, null, null, null,
                List.of(iri("A"), iri("B")), "INTERSECTION"));

        assertEquals(Status.EXPLAINED, outcome.status());
        assertEquals(Set.of(a, b), Set.copyOf(outcome.axioms()));
    }

    @Test
    void aUnionNeedsAKnownOperatorAndTwoClasses() {
        assertEquals(Status.FAILED, explain(new Request("ClassAssertion", iri("x"), RDF_TYPE, null, null, null,
                List.of(iri("A"), iri("B")), "XOR")).status());
        assertEquals(Status.FAILED, explain(new Request("ClassAssertion", iri("x"), RDF_TYPE, null, null, null,
                List.of(iri("A")), "UNION")).status());
    }
}
