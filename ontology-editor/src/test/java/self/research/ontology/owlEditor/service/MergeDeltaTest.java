package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.owlEditor.service.delta.DeltaBatches;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeDeltaTest {

    private static final String NS = "http://ex.org/";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLClass cls(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private OWLOntology base() throws Exception {
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "onto"));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls("A")));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls("B")));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(p));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls("A"), df.getOWLObjectSomeValuesFrom(p, cls("B"))));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls("A").getIRI(), df.getOWLLiteral("A")));
        return ontology;
    }

    private void mergeInto(OWLOntology ontology) {
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls("C")));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls("D")));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls("D"), cls("C")));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls("D"), df.getOWLObjectSomeValuesFrom(p, cls("A"))));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls("B"), df.getOWLObjectSomeValuesFrom(p, cls("C"))));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls("C").getIRI(), df.getOWLLiteral("C")));
        manager.removeAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls("A").getIRI(), df.getOWLLiteral("A")));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls("A").getIRI(), df.getOWLLiteral("A renamed")));
    }

    private static void apply(SailRepository repository, String update) {
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.prepareUpdate(update).execute();
        }
    }

    private static SailRepository storeOf(Model model) {
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(model);
        }
        return repository;
    }

    private static Model contentOf(SailRepository repository) {
        Model content = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.getStatements(null, null, null).forEach(content::add);
        }
        return content;
    }

    @Test
    void theAxiomComparisonMatchesTheWholeOntologyComparison() throws Exception {
        OWLOntology ontology = base();
        Model before = OwlApiModelSnapshot.of(manager, ontology).orElseThrow();
        AxiomDelta.Before captured = AxiomDelta.capture(ontology);
        mergeInto(ontology);
        Model after = OwlApiModelSnapshot.of(manager, ontology).orElseThrow();

        Optional<ExactDelta> viaAxioms = AxiomDelta.between(manager, captured, ontology, 100_000);
        ExactDelta viaSnapshots = ExactOrigins.deltaBetween(before, after).orElseThrow();

        assertTrue(viaAxioms.isPresent());
        assertTrue(Models.isomorphic(viaAxioms.get().inserted(), viaSnapshots.inserted()));
        assertTrue(Models.isomorphic(viaAxioms.get().deleted(), viaSnapshots.deleted()));
    }

    @Test
    void undoingInSmallBatchesBringsTheGraphBackAndRedoRestoresTheMerge() throws Exception {
        OWLOntology ontology = base();
        Model before = OwlApiModelSnapshot.of(manager, ontology).orElseThrow();
        AxiomDelta.Before captured = AxiomDelta.capture(ontology);
        mergeInto(ontology);
        Model after = OwlApiModelSnapshot.of(manager, ontology).orElseThrow();
        ExactDelta delta = AxiomDelta.between(manager, captured, ontology, 100_000).orElseThrow();

        List<ExactDelta> batches = DeltaBatches.split(delta, 3);
        assertTrue(batches.size() > 1);

        SailRepository repository = storeOf(after);
        for (ExactDelta batch : batches) {
            apply(repository, DeltaSparql.reverse(batch));
        }
        assertTrue(Models.isomorphic(contentOf(repository), before));

        for (ExactDelta batch : batches) {
            apply(repository, DeltaSparql.forward(batch));
        }
        assertTrue(Models.isomorphic(contentOf(repository), after));
        repository.shutDown();
    }

    @Test
    void headerAnnotationsAndImportsAreCapturedAndReversed() throws Exception {
        OWLOntology ontology = base();
        Model before = OwlApiModelSnapshot.of(manager, ontology).orElseThrow();
        AxiomDelta.Before captured = AxiomDelta.capture(ontology);
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("merged in", "en"))));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                df.getOWLImportsDeclaration(IRI.create(NS + "other"))));
        Model after = OwlApiModelSnapshot.of(manager, ontology).orElseThrow();

        ExactDelta delta = AxiomDelta.between(manager, captured, ontology, 100_000).orElseThrow();
        assertEquals(2, delta.inserted().size());
        assertTrue(delta.deleted().isEmpty());

        SailRepository repository = storeOf(after);
        for (ExactDelta batch : DeltaBatches.split(delta, 1)) {
            apply(repository, DeltaSparql.reverse(batch));
        }
        assertTrue(Models.isomorphic(contentOf(repository), before));
        repository.shutDown();
    }

    @Test
    void batchesKeepEachBlankNodeStructureWhole() throws Exception {
        OWLOntology ontology = base();
        AxiomDelta.Before captured = AxiomDelta.capture(ontology);
        mergeInto(ontology);
        ExactDelta delta = AxiomDelta.between(manager, captured, ontology, 100_000).orElseThrow();
        int total = delta.deleted().size() + delta.inserted().size();
        int seen = 0;
        for (ExactDelta batch : DeltaBatches.split(delta, 3)) {
            seen += batch.deleted().size() + batch.inserted().size();
        }
        assertEquals(total, seen);
    }

    @Test
    void largeChangeDataIsStoredCompressedAndReadsBackIdentically() {
        Model model = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        org.eclipse.rdf4j.model.ValueFactory vf = org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance();
        for (int i = 0; i < 6_000; i++) {
            model.add(vf.createIRI(NS + "subject-number-" + i), vf.createIRI(NS + "p"), vf.createLiteral("value " + i));
        }
        String stored = ExactDelta.write(model);
        assertTrue(stored.startsWith("gz:"));
        assertTrue(Models.isomorphic(ExactDelta.read(stored), model));
    }

    @Test
    void anUnchangedOntologyHasNoDelta() throws Exception {
        OWLOntology ontology = base();
        AxiomDelta.Before captured = AxiomDelta.capture(ontology);
        assertTrue(AxiomDelta.between(manager, captured, ontology, 100_000).isEmpty());
    }

    @Test
    void aChangeBeyondTheLimitHasNoDelta() throws Exception {
        OWLOntology ontology = base();
        AxiomDelta.Before captured = AxiomDelta.capture(ontology);
        mergeInto(ontology);
        assertTrue(AxiomDelta.between(manager, captured, ontology, 2).isEmpty());
    }
}
