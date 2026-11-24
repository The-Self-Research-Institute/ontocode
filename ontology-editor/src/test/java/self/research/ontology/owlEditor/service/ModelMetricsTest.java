package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelMetricsTest {

    private static final String NS = "http://example.org/t#";

    @Test
    void countsComeFromTheLoadedModel() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/t"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(b, a));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(c, a));
        manager.addAxiom(ontology, df.getOWLEquivalentClassesAxiom(b, c));
        manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(
                df.getOWLObjectProperty(IRI.create(NS + "p")), a));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), a.getIRI(), df.getOWLLiteral("A label")));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLAnnotationProperty(IRI.create(NS + "note"))));
        manager.addAxiom(ontology, df.getOWLDataPropertyRangeAxiom(
                df.getOWLDataProperty(IRI.create(NS + "age")), df.getIntegerOWLDatatype()));

        Map<String, Object> counts = ModelMetrics.counts(ontology);

        assertEquals(3, counts.get("classCount"));
        assertEquals(1, counts.get("objectPropertyCount"));
        assertEquals(1, counts.get("annotationPropertyCount"));
        assertEquals(2, counts.get("subClassOfAxiomCount"));
        assertEquals(1, counts.get("equivalentClassesAxiomCount"));
        assertEquals(1, counts.get("objectPropertyDomainAxiomCount"));
        assertEquals(1, counts.get("annotationAssertionAxiomCount"));
        assertEquals(2, counts.get("datatypeCount"));
        assertEquals(1, counts.get("dataPropertyCount"));
        assertEquals(0, counts.get("individualCount"));
        assertEquals(ontology.getAxiomCount(), counts.get("axiomCount"));
    }
}
