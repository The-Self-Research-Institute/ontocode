package self.research.ontology.owlEditor.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLVariable;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TriplesAtLeastAxiomsTest {

    private static final String NS = "http://ex/stress#";

    @TempDir
    Path scratch;

    private static void assertNoFewerTriplesThanAxioms(OWLOntologyManager manager, OWLOntology ontology) {
        int axioms = ontology.getAxiomCount();
        int triples = OwlApiModelSnapshot.of(manager, ontology).orElseThrow().size();
        assertTrue(triples >= axioms, triples + " triples for " + axioms + " axioms");
    }

    @ParameterizedTest
    @ValueSource(strings = {"proj-f2e0e5d8--720f846a-0dc5-4bea-915c-6f06b885f56c.owl",
            "proj-995a0280--eb2e658c-c0e6-4ae5-80dd-f4bd34a3bf58.owl", "PEO_MVP_IAO.owl", "company-swrl-test.owl",
            "synthetic-cvs-demo.owl", "pet-clinic-demo.owl", "reasonarinconsistant2.owl", "consistent-ontology.owl"})
    void aRealOntologyNeverRendersToFewerTriplesThanItHasAxioms(String file) throws Exception {
        Path copy = scratch.resolve(file);
        Files.copy(Path.of("..", "test-data", file), copy);
        OWLFormatConverter.sanitizeFileOnDisk(copy);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new FileDocumentSource(copy.toFile()),
                new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));

        assertNoFewerTriplesThanAxioms(manager, ontology);
    }

    @Test
    void axiomsThatShareTriplesStillRenderToAtLeastOneTripleEach() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://ex/stress"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLObjectProperty q = df.getOWLObjectProperty(IRI.create(NS + "q"));
        OWLNamedIndividual punned = df.getOWLNamedIndividual(IRI.create(NS + "A"));
        OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(NS + "x"));
        OWLAnonymousIndividual blank = df.getOWLAnonymousIndividual();
        OWLAnnotation note = df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("why"));
        OWLAnnotation other = df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("another reason"));
        SWRLVariable v = df.getSWRLVariable(IRI.create(NS + "v"));

        manager.addAxioms(ontology, Set.of(
                df.getOWLSubClassOfAxiom(a, b),
                df.getOWLSubClassOfAxiom(a, b, Set.of(note)),
                df.getOWLSubClassOfAxiom(a, b, Set.of(other)),
                df.getOWLSubClassOfAxiom(a, b, Set.of(note, other)),
                df.getOWLDeclarationAxiom(a),
                df.getOWLDeclarationAxiom(punned),
                df.getOWLClassAssertionAxiom(b, punned),
                df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(a, b), c),
                df.getOWLDisjointClassesAxiom(a, b, c),
                df.getOWLDisjointClassesAxiom(a, b),
                df.getOWLEquivalentClassesAxiom(a, df.getOWLObjectSomeValuesFrom(p, b)),
                df.getOWLNegativeObjectPropertyAssertionAxiom(p, x, punned),
                df.getOWLObjectPropertyAssertionAxiom(p, x, blank),
                df.getOWLClassAssertionAxiom(c, blank),
                df.getOWLSubPropertyChainOfAxiom(List.of(p, q), p),
                df.getOWLAnnotationAssertionAxiom(a.getIRI(), note),
                df.getOWLAnnotationAssertionAxiom(a.getIRI(), note, Set.of(other)),
                df.getSWRLRule(Set.of(df.getSWRLClassAtom(a, v)), Set.of(df.getSWRLClassAtom(b, v))),
                df.getSWRLRule(Set.of(df.getSWRLClassAtom(b, v)), Set.of(df.getSWRLClassAtom(c, v)))));

        assertNoFewerTriplesThanAxioms(manager, ontology);
    }
}
