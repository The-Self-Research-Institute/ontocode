package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomPlainTextTest {

    private static final String NS = "http://www.example.org/ontologies/2023/6/PizzaTutorial#";
    private final OWLDataFactory df = OWLManager.getOWLDataFactory();
    private final OWLClass pizza = cls("Pizza");
    private final IRI pizzaIri = pizza.getIRI();

    private OWLClass cls(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    @Test
    void declarationsSayWhatKindOfThingItIs() {
        assertEquals("This is a class", AxiomPlainText.describe(df.getOWLDeclarationAxiom(pizza), pizzaIri));
        OWLNamedIndividual one = df.getOWLNamedIndividual(IRI.create(NS + "Pizza1"));
        assertEquals("This is an individual", AxiomPlainText.describe(df.getOWLDeclarationAxiom(one), one.getIRI()));
    }

    @Test
    void subclassAxiomsReadAsKindOfAndParentOf() {
        assertEquals("Kind of: Food", AxiomPlainText.describe(df.getOWLSubClassOfAxiom(pizza, cls("Food")), pizzaIri));
        assertEquals("Parent of: Calzone", AxiomPlainText.describe(df.getOWLSubClassOfAxiom(cls("Calzone"), pizza), pizzaIri));
    }

    @Test
    void restrictionsReadAsSentencesWithoutAnyIri() {
        OWLObjectProperty hasTopping = df.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(pizza, df.getOWLObjectSomeValuesFrom(hasTopping, cls("Cheese")));

        String text = AxiomPlainText.describe(axiom, pizzaIri);

        assertEquals("Must satisfy: has some hasTopping Cheese", text);
    }

    @Test
    void labelsAndCommentsShowTheirTextNotTheAxiomSyntax() {
        OWLAxiom labelAxiom = df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), pizzaIri, df.getOWLLiteral("Alpha"));
        OWLAxiom commentAxiom = df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), pizzaIri, df.getOWLLiteral("A round bread"));

        assertEquals("Label: \"Alpha\"", AxiomPlainText.describe(labelAxiom, pizzaIri));
        assertEquals("Comment: \"A round bread\"", AxiomPlainText.describe(commentAxiom, pizzaIri));
    }

    @Test
    void disjointAndEquivalentListOnlyTheOtherClasses() {
        OWLAxiom disjoint = df.getOWLDisjointClassesAxiom(pizza, cls("Topping"), cls("Base"));
        OWLAxiom equivalent = df.getOWLEquivalentClassesAxiom(pizza, cls("Pie"));

        assertEquals("Cannot overlap with: Base, Topping", AxiomPlainText.describe(disjoint, pizzaIri));
        assertEquals("Same as: Pie", AxiomPlainText.describe(equivalent, pizzaIri));
    }

    @Test
    void individualsReadAsIsAAndPropertyValues() {
        OWLNamedIndividual one = df.getOWLNamedIndividual(IRI.create(NS + "Pizza1"));
        OWLObjectProperty hasTopping = df.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        OWLNamedIndividual cheese = df.getOWLNamedIndividual(IRI.create(NS + "CheeseA"));

        assertEquals("Is a: Pizza", AxiomPlainText.describe(df.getOWLClassAssertionAxiom(pizza, one), one.getIRI()));
        assertEquals("hasTopping: CheeseA",
                AxiomPlainText.describe(df.getOWLObjectPropertyAssertionAxiom(hasTopping, one, cheese), one.getIRI()));
    }

    @Test
    void anythingUnrecognisedStillHasItsLongAddressesShortened() {
        OWLObjectProperty hasTopping = df.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        OWLObjectProperty hasIngredient = df.getOWLObjectProperty(IRI.create(NS + "hasIngredient"));

        String text = AxiomPlainText.describe(df.getOWLSubObjectPropertyOfAxiom(hasTopping, hasIngredient), hasTopping.getIRI());

        assertFalse(text.contains("http://"), text);
        assertTrue(text.contains("hasTopping") && text.contains("hasIngredient"), text);
    }

    @Test
    void summaryDropsDuplicatesAndCapsLongLists() {
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            axioms.add(df.getOWLSubClassOfAxiom(pizza, cls("Parent" + i)));
        }
        axioms.add(df.getOWLSubClassOfAxiom(pizza, cls("Parent0")));

        String summary = AxiomPlainText.summarize(Set.copyOf(axioms), pizzaIri);

        assertEquals(AxiomPlainText.MAX_LINES + 1, summary.split("\n").length);
        assertTrue(summary.endsWith("+ 5 more"), summary);
        assertFalse(summary.contains("http://"));
    }
}
