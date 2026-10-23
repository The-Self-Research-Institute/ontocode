package self.research.ontology.common;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwrlBuiltInsTest {

    private static final String NS = "http://example.org#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
    private final OWLClass oldDog = df.getOWLClass(IRI.create(NS + "OldDog"));
    private final OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
    private final OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "age"));
    private final SWRLVariable x = df.getSWRLVariable(IRI.create(NS + "x"));
    private final SWRLVariable a = df.getSWRLVariable(IRI.create(NS + "a"));

    private OWLOntology ontologyWithOneRuleOfEachKind() throws Exception {
        OWLOntology ontology = manager.createOntology();
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dog, animal));
        manager.addAxiom(ontology, df.getSWRLRule(
                Set.of(df.getSWRLClassAtom(dog, x),
                        df.getSWRLDataPropertyAtom(age, x, a),
                        df.getSWRLBuiltInAtom(IRI.create("http://www.w3.org/2003/11/swrlb#greaterThan"),
                                List.of(a, df.getSWRLLiteralArgument(df.getOWLLiteral(10))))),
                Set.of(df.getSWRLClassAtom(oldDog, x))));
        manager.addAxiom(ontology, df.getSWRLRule(
                Set.of(df.getSWRLClassAtom(dog, x)),
                Set.of(df.getSWRLClassAtom(animal, x))));
        return ontology;
    }

    @Test
    void countsOnlyTheRulesThatUseABuiltIn() throws Exception {
        assertEquals(1, SwrlBuiltIns.countBuiltInRules(ontologyWithOneRuleOfEachKind()));
    }

    @Test
    void theCopyKeepsEverythingExceptTheBuiltInRules() throws Exception {
        OWLOntology original = ontologyWithOneRuleOfEachKind();

        OWLOntology copy = SwrlBuiltIns.withoutBuiltInRules(original);

        assertEquals(0, SwrlBuiltIns.countBuiltInRules(copy));
        assertEquals(1, copy.axioms(AxiomType.SWRL_RULE).count());
        assertEquals(1, copy.axioms(AxiomType.SUBCLASS_OF).count());
        assertEquals(1, SwrlBuiltIns.countBuiltInRules(original));
    }

    @Test
    void anOntologyWithoutBuiltInRulesIsReturnedUnchanged() throws Exception {
        OWLOntology ontology = manager.createOntology();
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dog, animal));

        assertSame(ontology, SwrlBuiltIns.withoutBuiltInRules(ontology));
    }

    @Test
    void hermitCanReasonOverTheCopyWhereItRefusesTheOriginal() throws Exception {
        OWLOntology original = ontologyWithOneRuleOfEachKind();

        OWLReasoner reasoner = new ReasonerFactory().createReasoner(SwrlBuiltIns.withoutBuiltInRules(original));

        assertTrue(reasoner.isConsistent());
        assertTrue(reasoner.getSuperClasses(dog, true).containsEntity(animal));
        reasoner.dispose();
    }

    @Test
    void theNoteIsWorded() {
        assertEquals("HermiT does not support SWRL built-in functions, so 1 rule using them was left out of reasoning.",
                SwrlBuiltIns.omissionNote(1));
        assertTrue(SwrlBuiltIns.omissionNote(3).contains("3 rules"));
        assertTrue(SwrlBuiltIns.omissionNote(3).contains("were left out"));
    }
}
