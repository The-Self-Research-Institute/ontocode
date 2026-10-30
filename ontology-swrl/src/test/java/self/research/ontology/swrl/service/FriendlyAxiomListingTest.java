package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.swrl.model.InferredAxiom;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendlyAxiomListingTest {

    private static final String NS = "http://example.org/clinic#";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String SWRL_NS = "http://www.w3.org/2003/11/swrl#";

    private static final String RULES_RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xml:base="http://example.org/clinic" xmlns:rdf="%2$s"
              xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
              xmlns:c="%1$s" xmlns:swrl="%3$s">
              <owl:Ontology rdf:about="http://example.org/clinic"/>
              <owl:Class rdf:about="%1$sPatient"/>
              <owl:Class rdf:about="%1$sSenior"/>
              <owl:NamedIndividual rdf:about="%1$sann"><rdf:type rdf:resource="%1$sPatient"/></owl:NamedIndividual>
              <rdf:Description><rdf:type rdf:resource="%1$sPatient"/></rdf:Description>
              <swrl:Variable rdf:about="%1$sp"/>
              <swrl:Imp>
                <swrl:body><swrl:AtomList>
                  <rdf:first><swrl:ClassAtom><swrl:classPredicate rdf:resource="%1$sPatient"/><swrl:argument1 rdf:resource="%1$sp"/></swrl:ClassAtom></rdf:first>
                  <rdf:rest rdf:resource="%2$snil"/>
                </swrl:AtomList></swrl:body>
                <swrl:head><swrl:AtomList>
                  <rdf:first><swrl:ClassAtom><swrl:classPredicate rdf:resource="%1$sSenior"/><swrl:argument1 rdf:resource="%1$sp"/></swrl:ClassAtom></rdf:first>
                  <rdf:rest rdf:resource="%2$snil"/>
                </swrl:AtomList></swrl:head>
              </swrl:Imp>
            </rdf:RDF>
            """.formatted(NS, RDF_NS, SWRL_NS);

    private static final String RULES_TTL = """
            @prefix : <%1$s> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdf: <%2$s> .
            @prefix swrl: <%3$s> .
            <http://example.org/clinic> a owl:Ontology .
            :Patient a owl:Class .
            :Senior a owl:Class .
            :ann a owl:NamedIndividual, :Patient .
            [] a :Patient .
            :p a swrl:Variable .
            [] a swrl:Imp ;
              swrl:body [ a swrl:AtomList ; rdf:first [ a swrl:ClassAtom ; swrl:classPredicate :Patient ; swrl:argument1 :p ] ; rdf:rest rdf:nil ] ;
              swrl:head [ a swrl:AtomList ; rdf:first [ a swrl:ClassAtom ; swrl:classPredicate :Senior ; swrl:argument1 :p ] ; rdf:rest rdf:nil ] .
            """.formatted(NS, RDF_NS, SWRL_NS);

    private static OWLOntology load(String text) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
    }

    private static void addStoredRuleParts(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        OWLAnonymousIndividual list = f.getOWLAnonymousIndividual("genid1");
        OWLAnonymousIndividual atom = f.getOWLAnonymousIndividual("genid2");
        OWLAnonymousIndividual tail = f.getOWLAnonymousIndividual("genid3");
        OWLNamedIndividual variable = f.getOWLNamedIndividual(IRI.create(NS + "p"));
        Set<OWLAxiom> parts = new HashSet<>();
        parts.add(f.getOWLClassAssertionAxiom(f.getOWLClass(IRI.create(RDF_NS + "rest")), tail));
        parts.add(f.getOWLClassAssertionAxiom(f.getOWLClass(IRI.create(RDF_NS + "List")), tail));
        parts.add(f.getOWLClassAssertionAxiom(f.getOWLClass(IRI.create(SWRL_NS + "AtomList")), list));
        parts.add(f.getOWLClassAssertionAxiom(f.getOWLClass(IRI.create(SWRL_NS + "ClassAtom")), atom));
        parts.add(f.getOWLClassAssertionAxiom(f.getOWLClass(IRI.create(SWRL_NS + "Variable")), variable));
        parts.add(f.getOWLClassAssertionAxiom(f.getOWLClass(IRI.create(NS + "Patient")), atom));
        parts.add(f.getOWLObjectPropertyAssertionAxiom(f.getOWLObjectProperty(IRI.create(SWRL_NS + "argument1")), atom, variable));
        parts.add(f.getOWLObjectPropertyAssertionAxiom(f.getOWLObjectProperty(IRI.create(RDF_NS + "first")), list, atom));
        parts.add(f.getOWLObjectPropertyAssertionAxiom(f.getOWLObjectProperty(IRI.create(RDF_NS + "rest")), list, tail));
        manager.addAxioms(ontology, parts);
    }

    private static List<String> shown(AssertedAxiomLister.Page page) {
        return page.items().stream().map(i -> i.getReadable() + " | " + i.getDescription()).toList();
    }

    @Test
    void rulesStoredAsTriplesLeaveNothingBehindInEitherSyntax() throws Exception {
        for (String document : List.of(RULES_RDF, RULES_TTL)) {
            OWLOntology ontology = load(document);
            AssertedAxiomLister.Page clean = AssertedAxiomLister.list(ontology, null, null, 0, 200, 100_000);
            addStoredRuleParts(ontology);
            AssertedAxiomLister.Page page = AssertedAxiomLister.list(ontology, null, null, 0, 200, 100_000);

            for (InferredAxiom item : page.items()) {
                assertFalse(item.getDescription().contains(RDF_NS), item.getDescription());
                assertFalse(item.getDescription().contains(SWRL_NS), item.getDescription());
            }
            assertEquals(clean.total(), page.total(), shown(page).toString());
            assertEquals(page.total(), page.items().size());
        }
    }

    @Test
    void anAnonymousIndividualThatIsARealFactIsKept() throws Exception {
        OWLOntology ontology = load(RULES_TTL);
        addStoredRuleParts(ontology);

        AssertedAxiomLister.Page page = AssertedAxiomLister.list(ontology, null, null, 0, 200, 100_000);

        assertEquals(2, page.total(), shown(page).toString());
        assertTrue(page.items().stream().anyMatch(i -> i.getReadable().startsWith("_:") && i.getReadable().endsWith(" is a Patient")),
                shown(page).toString());
        assertTrue(page.items().stream().anyMatch(i -> i.getReadable().equals("ann is a Patient")), shown(page).toString());
    }

    @Test
    void theTypeFilterAndSearchNeverBringTheArtifactsBack() throws Exception {
        OWLOntology ontology = load(RULES_TTL);
        addStoredRuleParts(ontology);

        AssertedAxiomLister.Page byType = AssertedAxiomLister.list(ontology, null, AssertedAxiomLister.parseType("ObjectPropertyAssertion"), 0, 200, 100_000);
        AssertedAxiomLister.Page bySearch = AssertedAxiomLister.list(ontology, "swrl", null, 0, 200, 100_000);

        assertEquals(0, byType.total());
        assertEquals(0, bySearch.total());
    }

    @Test
    void namesAreShortAndTheFullFormStaysInTheDescription() throws Exception {
        OWLOntology ontology = load(RULES_TTL);
        AssertedAxiomLister.Page page = AssertedAxiomLister.list(ontology, "ann", null, 0, 10, 1000);

        InferredAxiom item = page.items().get(0);
        assertEquals("ann is a Patient", item.getReadable());
        assertTrue(item.getDescription().contains("<http://example.org/clinic#ann>"), item.getDescription());
        assertEquals(NS + "ann", item.getSubjectIri());
    }

    @Test
    void sentencesReadNaturallyForEachCommonAxiomShape() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/clinic"));
        OWLClass patient = f.getOWLClass(IRI.create(NS + "Patient"));
        OWLClass person = f.getOWLClass(IRI.create(NS + "Person"));
        OWLClass fever = f.getOWLClass(IRI.create(NS + "Fever"));
        OWLClass senior = f.getOWLClass(IRI.create(NS + "Senior"));
        OWLObjectProperty hasDoctor = f.getOWLObjectProperty(IRI.create(NS + "hasDoctor"));
        OWLNamedIndividual ann = f.getOWLNamedIndividual(IRI.create(NS + "ann"));
        OWLNamedIndividual bob = f.getOWLNamedIndividual(IRI.create(NS + "bob"));
        AxiomWording wording = AxiomWording.of(o);

        assertEquals("ann hasDoctor bob", wording.sentence(f.getOWLObjectPropertyAssertionAxiom(hasDoctor, ann, bob)));
        assertEquals("ann age 42", wording.sentence(f.getOWLDataPropertyAssertionAxiom(f.getOWLDataProperty(IRI.create(NS + "age")), ann, 42)));
        assertEquals("ann ward \"General\"", wording.sentence(f.getOWLDataPropertyAssertionAxiom(f.getOWLDataProperty(IRI.create(NS + "ward")), ann, "General")));
        assertEquals("Patient is a kind of Person", wording.sentence(f.getOWLSubClassOfAxiom(patient, person)));
        assertEquals("ann is a (Fever and Senior)", wording.sentence(f.getOWLClassAssertionAxiom(f.getOWLObjectIntersectionOf(fever, senior), ann)));
        assertEquals("Patient is a kind of (hasDoctor some Person)",
                wording.sentence(f.getOWLSubClassOfAxiom(patient, f.getOWLObjectSomeValuesFrom(hasDoctor, person))));
    }
}
