package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import self.research.ontology.swrl.model.InferredAxiom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomWordingCoverageTest {

    private static final String NS = "http://example.org/library#";
    private static final OWLDataFactory F = OWLManager.getOWLDataFactory();

    private static OWLClass c(String n) {
        return F.getOWLClass(IRI.create(NS + n));
    }

    private static OWLObjectProperty o(String n) {
        return F.getOWLObjectProperty(IRI.create(NS + n));
    }

    private static OWLDataProperty d(String n) {
        return F.getOWLDataProperty(IRI.create(NS + n));
    }

    private static OWLNamedIndividual i(String n) {
        return F.getOWLNamedIndividual(IRI.create(NS + n));
    }

    private static Map<String, OWLAxiom> oneOfEach() {
        OWLAnnotationProperty note = F.getOWLAnnotationProperty(IRI.create(NS + "note"));
        Map<String, OWLAxiom> m = new LinkedHashMap<>();
        m.put("label", F.getOWLAnnotationAssertionAxiom(F.getRDFSLabel(), IRI.create(NS + "Novel"), F.getOWLLiteral("A story")));
        m.put("comment", F.getOWLAnnotationAssertionAxiom(F.getRDFSComment(), IRI.create(NS + "Novel"), F.getOWLLiteral("x".repeat(300))));
        m.put("seeAlso", F.getOWLAnnotationAssertionAxiom(F.getRDFSSeeAlso(), IRI.create(NS + "Novel"), IRI.create("http://example.org/other#Page")));
        m.put("custom", F.getOWLAnnotationAssertionAxiom(note, IRI.create(NS + "Novel"), F.getOWLLiteral("hi", "en")));
        m.put("subObj", F.getOWLSubObjectPropertyOfAxiom(o("writes"), o("creates")));
        m.put("subData", F.getOWLSubDataPropertyOfAxiom(d("pages"), d("size")));
        m.put("inverse", F.getOWLInverseObjectPropertiesAxiom(o("writes"), o("writtenBy")));
        m.put("functional", F.getOWLFunctionalObjectPropertyAxiom(o("writtenBy")));
        m.put("inverseFunctional", F.getOWLInverseFunctionalObjectPropertyAxiom(o("isbnOf")));
        m.put("transitive", F.getOWLTransitiveObjectPropertyAxiom(o("partOf")));
        m.put("symmetric", F.getOWLSymmetricObjectPropertyAxiom(o("friendOf")));
        m.put("asymmetric", F.getOWLAsymmetricObjectPropertyAxiom(o("parentOf")));
        m.put("reflexive", F.getOWLReflexiveObjectPropertyAxiom(o("knows")));
        m.put("irreflexive", F.getOWLIrreflexiveObjectPropertyAxiom(o("cites")));
        m.put("functionalData", F.getOWLFunctionalDataPropertyAxiom(d("isbn")));
        m.put("objDomain", F.getOWLObjectPropertyDomainAxiom(o("writes"), c("Author")));
        m.put("objRange", F.getOWLObjectPropertyRangeAxiom(o("writes"), c("Book")));
        m.put("dataDomain", F.getOWLDataPropertyDomainAxiom(d("pages"), c("Book")));
        m.put("dataRange", F.getOWLDataPropertyRangeAxiom(d("pages"), F.getOWLDatatype(OWL2Datatype.XSD_INTEGER.getIRI())));
        m.put("different", F.getOWLDifferentIndividualsAxiom(i("ann"), i("bob"), i("cy")));
        m.put("same", F.getOWLSameIndividualAxiom(i("ann"), i("anna")));
        m.put("negObj", F.getOWLNegativeObjectPropertyAssertionAxiom(o("writes"), i("ann"), i("bob")));
        m.put("negData", F.getOWLNegativeDataPropertyAssertionAxiom(d("pages"), i("ann"), F.getOWLLiteral(3)));
        m.put("hasKey", F.getOWLHasKeyAxiom(c("Book"), d("isbn"), o("publishedBy")));
        m.put("disjointUnion", F.getOWLDisjointUnionAxiom(c("Item"), java.util.Set.of(c("Book"), c("Journal"))));
        m.put("equivObj", F.getOWLEquivalentObjectPropertiesAxiom(o("writes"), o("authors")));
        m.put("equivData", F.getOWLEquivalentDataPropertiesAxiom(d("pages"), d("leaves")));
        m.put("disjointObj", F.getOWLDisjointObjectPropertiesAxiom(o("writes"), o("cites")));
        m.put("disjointData", F.getOWLDisjointDataPropertiesAxiom(d("pages"), d("isbn")));
        m.put("chain", F.getOWLSubPropertyChainOfAxiom(List.of(o("writes"), o("partOf")), o("contributesTo")));
        m.put("inverseOfExpr", F.getOWLObjectPropertyAssertionAxiom(o("writes").getInverseProperty(), i("ann"), i("bob")));
        m.put("disjointClasses", F.getOWLDisjointClassesAxiom(c("Book"), c("Journal")));
        m.put("equivClasses", F.getOWLEquivalentClassesAxiom(c("Tome"), F.getOWLObjectIntersectionOf(c("Book"), c("Long"))));
        m.put("subAnnotation", F.getOWLSubAnnotationPropertyOfAxiom(note, F.getRDFSComment()));
        m.put("annDomain", F.getOWLAnnotationPropertyDomainAxiom(note, IRI.create(NS + "Book")));
        m.put("annRange", F.getOWLAnnotationPropertyRangeAxiom(note, IRI.create(NS + "Book")));
        m.put("declaration", F.getOWLDeclarationAxiom(c("Book")));
        return m;
    }

    @Test
    void everyAxiomTypeGetsAShortSentenceWithNoWebAddresses() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/library"));
        AxiomWording wording = AxiomWording.of(ontology);
        for (Map.Entry<String, OWLAxiom> entry : oneOfEach().entrySet()) {
            String text = wording.sentence(entry.getValue());
            assertFalse(text.isBlank(), entry.getKey());
            assertFalse(text.contains("<http"), entry.getKey() + ": " + text);
            assertFalse(text.contains("http://"), entry.getKey() + ": " + text);
            InferredAxiom item = InferredAxiomPresenter.format(entry.getValue(), wording);
            assertFalse(item.getReadable().contains("<http"), entry.getKey() + ": " + item.getReadable());
            assertTrue(item.getDescription().contains("http://example.org/"), entry.getKey() + ": " + item.getDescription());
        }
    }

    @Test
    void sentencesReadNaturally() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/library"));
        AxiomWording w = AxiomWording.of(ontology);
        Map<String, OWLAxiom> m = oneOfEach();

        assertEquals("Novel has label \"A story\"", w.sentence(m.get("label")));
        assertEquals("Novel has see also Page", w.sentence(m.get("seeAlso")));
        assertEquals("Novel has note \"hi\"@en", w.sentence(m.get("custom")));
        assertEquals("writes is a kind of creates", w.sentence(m.get("subObj")));
        assertEquals("writes is the inverse of writtenBy", w.sentence(m.get("inverse")));
        assertEquals("partOf is transitive", w.sentence(m.get("transitive")));
        assertEquals("pages holds xsd:integer", w.sentence(m.get("dataRange")));
        assertEquals("writes points to Book", w.sentence(m.get("objRange")));
        assertEquals("ann does not writes bob", w.sentence(m.get("negObj")));
        assertTrue(w.sentence(m.get("different")).endsWith("are different from each other"));
        assertEquals("ann inverse writes bob", w.sentence(m.get("inverseOfExpr")));
    }

    @Test
    void longTextIsClippedInTheSentenceButKeptInTheDescription() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/library"));
        AxiomWording wording = AxiomWording.of(ontology);
        OWLAxiom comment = oneOfEach().get("comment");

        InferredAxiom item = InferredAxiomPresenter.format(comment, wording);

        assertTrue(item.getReadable().startsWith("Novel has comment \"" + "x".repeat(120) + "...\""), item.getReadable());
        assertTrue(item.getReadable().length() < 160);
        assertTrue(item.getDescription().contains("x".repeat(300)));
    }
}
