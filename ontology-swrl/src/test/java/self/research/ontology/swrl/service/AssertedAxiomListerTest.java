package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.swrl.model.InferredAxiom;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertedAxiomListerTest {

    static final String NS = "http://example.org/bakery#";

    static OWLOntology bakery() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/bakery"));
        OWLClass bread = f.getOWLClass(IRI.create(NS + "Bread"));
        OWLClass loaf = f.getOWLClass(IRI.create(NS + "Loaf"));
        OWLClass baker = f.getOWLClass(IRI.create(NS + "Baker"));
        OWLObjectProperty bakes = f.getOWLObjectProperty(IRI.create(NS + "bakes"));
        OWLDataProperty weight = f.getOWLDataProperty(IRI.create(NS + "weightGrams"));
        OWLNamedIndividual rye = f.getOWLNamedIndividual(IRI.create(NS + "rye1"));
        OWLNamedIndividual sour = f.getOWLNamedIndividual(IRI.create(NS + "sourdough1"));
        OWLNamedIndividual ana = f.getOWLNamedIndividual(IRI.create(NS + "ana"));
        manager.addAxiom(o, f.getOWLDeclarationAxiom(bread));
        manager.addAxiom(o, f.getOWLSubClassOfAxiom(loaf, bread));
        manager.addAxiom(o, f.getOWLClassAssertionAxiom(loaf, rye));
        manager.addAxiom(o, f.getOWLClassAssertionAxiom(loaf, sour));
        manager.addAxiom(o, f.getOWLClassAssertionAxiom(baker, ana));
        manager.addAxiom(o, f.getOWLClassAssertionAxiom(f.getOWLThing(), ana));
        manager.addAxiom(o, f.getOWLObjectPropertyAssertionAxiom(bakes, ana, rye));
        manager.addAxiom(o, f.getOWLObjectPropertyAssertionAxiom(bakes, ana, sour));
        manager.addAxiom(o, f.getOWLDataPropertyAssertionAxiom(weight, rye, 800));
        manager.addAxiom(o, f.getOWLSameIndividualAxiom(rye, rye));
        return o;
    }

    static AxiomType<?> type(String name) {
        return AssertedAxiomLister.parseType(name);
    }

    @Test
    void declarationsRuleAxiomsAndTrivialAxiomsAreLeftOut() throws Exception {
        AssertedAxiomLister.Page page = AssertedAxiomLister.list(bakery(), null, null, 0, 50, 1000);

        assertEquals(7, page.total());
        assertEquals(7, page.items().size());
        assertTrue(page.items().stream().noneMatch(i -> i.getAxiomType().equals("Declaration")));
        assertTrue(page.items().stream().noneMatch(i -> i.getAxiomType().equals("SWRLRule")));
        assertTrue(page.items().stream().noneMatch(i -> i.getReadable().contains("owl:Thing")));
        assertTrue(page.items().stream().noneMatch(i -> i.getAxiomType().equals("SameIndividual")));
        assertFalse(page.truncated());
    }

    @Test
    void itemsAreOrderedByAxiomTypeThenText() throws Exception {
        List<InferredAxiom> items = AssertedAxiomLister.list(bakery(), null, null, 0, 50, 1000).items();

        List<String> types = items.stream().map(InferredAxiom::getAxiomType).toList();
        assertEquals(List.of("ClassAssertion", "ClassAssertion", "ClassAssertion", "DataPropertyAssertion",
                "ObjectPropertyAssertion", "ObjectPropertyAssertion", "SubClassOf"), types);
        assertTrue(items.get(0).getReadable().compareTo(items.get(1).getReadable()) < 0);
        assertTrue(items.get(1).getReadable().compareTo(items.get(2).getReadable()) < 0);
    }

    @Test
    void pagingSplitsTheListWithoutGapsOrRepeats() throws Exception {
        OWLOntology o = bakery();

        AssertedAxiomLister.Page first = AssertedAxiomLister.list(o, null, null, 0, 3, 1000);
        AssertedAxiomLister.Page second = AssertedAxiomLister.list(o, null, null, 1, 3, 1000);
        AssertedAxiomLister.Page third = AssertedAxiomLister.list(o, null, null, 2, 3, 1000);
        AssertedAxiomLister.Page beyond = AssertedAxiomLister.list(o, null, null, 3, 3, 1000);

        assertEquals(List.of(3, 3, 1, 0), List.of(first.items().size(), second.items().size(), third.items().size(), beyond.items().size()));
        assertEquals(7, third.total());
        assertEquals(1, second.page());
        List<String> all = AssertedAxiomLister.list(o, null, null, 0, 50, 1000).items().stream().map(InferredAxiom::getDescription).toList();
        List<String> joined = new java.util.ArrayList<>();
        for (AssertedAxiomLister.Page p : List.of(first, second, third)) {
            p.items().forEach(i -> joined.add(i.getDescription()));
        }
        assertEquals(all, joined);
    }

    @Test
    void searchIsCaseInsensitiveOnTheRenderedText() throws Exception {
        AssertedAxiomLister.Page page = AssertedAxiomLister.list(bakery(), "  SOURDOUGH1 ", null, 0, 50, 1000);

        assertEquals(2, page.total());
        assertTrue(page.items().stream().allMatch(i -> i.getReadable().toLowerCase().contains("sourdough1")));
        assertEquals(0, AssertedAxiomLister.list(bakery(), "no such text", null, 0, 50, 1000).total());
    }

    @Test
    void theTypeFilterKeepsOnlyThatKindOfAxiomAndAcceptsAnyCase() throws Exception {
        assertEquals(3, AssertedAxiomLister.list(bakery(), null, type("ClassAssertion"), 0, 50, 1000).total());
        assertEquals(2, AssertedAxiomLister.list(bakery(), null, type("objectpropertyassertion"), 0, 50, 1000).total());
        assertEquals(1, AssertedAxiomLister.list(bakery(), null, type("DataPropertyAssertion"), 0, 50, 1000).total());
        assertEquals(1, AssertedAxiomLister.list(bakery(), null, type("SubClassOf"), 0, 50, 1000).total());
        assertEquals(1, AssertedAxiomLister.list(bakery(), "rye1", type("ObjectPropertyAssertion"), 0, 50, 1000).total());
    }

    @Test
    void ruleAxiomsAndDeclarationsCannotBeAskedFor() {
        assertThrows(IllegalArgumentException.class, () -> type("SWRLRule"));
        assertThrows(IllegalArgumentException.class, () -> type("Declaration"));
        assertThrows(IllegalArgumentException.class, () -> type("Nonsense"));
        assertNull(type(null));
        assertNull(type("  "));
    }

    @Test
    void thePageSizeIsCappedAtTwoHundredAndDefaultsWhenMissing() {
        assertEquals(200, AssertedAxiomLister.clampPageSize(5000));
        assertEquals(200, AssertedAxiomLister.clampPageSize(200));
        assertEquals(50, AssertedAxiomLister.clampPageSize(0));
        assertEquals(50, AssertedAxiomLister.clampPageSize(-4));
        assertEquals(7, AssertedAxiomLister.clampPageSize(7));
    }

    @Test
    void aHugePageSizeReturnsAtMostTwoHundredItemsAndReportsTheCap() throws Exception {
        OWLOntology o = manyIndividuals(450);

        AssertedAxiomLister.Page page = AssertedAxiomLister.list(o, null, null, 0, 100_000, 100_000);

        assertEquals(450, page.total());
        assertEquals(200, page.pageSize());
        assertEquals(200, page.items().size());
    }

    @Test
    void aPageDeepPastTheWindowIsRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AssertedAxiomLister.list(bakery(), null, null, 100, 200, 1000));
    }

    @Test
    void scanningStopsAtTheCapAndSaysSo() throws Exception {
        OWLOntology o = manyIndividuals(300);

        AssertedAxiomLister.Page page = AssertedAxiomLister.list(o, null, null, 0, 50, 120);

        assertTrue(page.truncated());
        assertEquals(120, page.scanned());
        assertEquals(120, page.total());
        assertFalse(AssertedAxiomLister.list(o, null, null, 0, 50, 5000).truncated());
    }

    @Test
    void anAnnotatedAxiomIsStillListedAsItsKind() throws Exception {
        OWLOntology o = bakery();
        OWLDataFactory f = o.getOWLOntologyManager().getOWLDataFactory();
        o.getOWLOntologyManager().addAxiom(o, f.getOWLClassAssertionAxiom(f.getOWLClass(IRI.create(NS + "Baker")),
                f.getOWLNamedIndividual(IRI.create(NS + "ben")),
                java.util.Set.of(f.getOWLAnnotation(f.getRDFSLabel(), f.getOWLLiteral("typed by hand")))));

        assertEquals(4, AssertedAxiomLister.list(o, null, type("ClassAssertion"), 0, 50, 1000).total());
    }

    static OWLOntology manyIndividuals(int count) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology();
        OWLClass loaf = f.getOWLClass(IRI.create(NS + "Loaf"));
        for (int i = 0; i < count; i++) {
            manager.addAxiom(o, f.getOWLClassAssertionAxiom(loaf, f.getOWLNamedIndividual(IRI.create(NS + "loaf" + i))));
        }
        return o;
    }
}
