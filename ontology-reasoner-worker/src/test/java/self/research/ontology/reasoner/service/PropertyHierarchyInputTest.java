package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyHierarchyInputTest {

    @Test
    void onlyThePropertyAxiomsAndDeclarationsAreKept() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology source = manager.createOntology();
        OWLClass a = df.getOWLClass(IRI.create("urn:t#A"));
        OWLClass b = df.getOWLClass(IRI.create("urn:t#B"));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create("urn:t#p"));
        OWLObjectProperty q = df.getOWLObjectProperty(IRI.create("urn:t#q"));
        OWLNamedIndividual i = df.getOWLNamedIndividual(IRI.create("urn:t#i"));
        manager.addAxiom(source, df.getOWLDeclarationAxiom(p));
        manager.addAxiom(source, df.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(source, df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(p, b)));
        manager.addAxiom(source, df.getOWLSubObjectPropertyOfAxiom(p, q));
        manager.addAxiom(source, df.getOWLObjectPropertyDomainAxiom(p, a));
        manager.addAxiom(source, df.getOWLClassAssertionAxiom(a, i));
        manager.addAxiom(source, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), a.getIRI(), df.getOWLLiteral("A")));

        OWLOntology input = OntologySessionService.propertyHierarchyInput(source);

        assertEquals(0, input.getAxiomCount(AxiomType.SUBCLASS_OF));
        assertEquals(0, input.getAxiomCount(AxiomType.CLASS_ASSERTION));
        assertEquals(0, input.getAxiomCount(AxiomType.ANNOTATION_ASSERTION));
        assertEquals(1, input.getAxiomCount(AxiomType.SUB_OBJECT_PROPERTY));
        assertEquals(1, input.getAxiomCount(AxiomType.OBJECT_PROPERTY_DOMAIN));
        assertTrue(input.containsObjectPropertyInSignature(p.getIRI()));
    }

    @Test
    void anElkSnapshotStillGetsItsPropertyHierarchyFromHermit() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor().project("pizza", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor));

        Map<String, Object> snapshot = snapshots.snapshot("pizza", "ELK", null);

        assertEquals("ELK", snapshot.get("reasonerType"));
        assertEquals("HermiT", snapshot.get("propertyReasonerType"));
        assertTrue(!((java.util.List<?>) snapshot.get("objectPropertyHierarchyFlat")).isEmpty());
    }
}
