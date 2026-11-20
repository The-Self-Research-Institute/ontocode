package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DesktopChildrenIndexTest {

    private static final String NS = "http://ex.org/t#";

    private final HierarchySnapshotBuilder builder = spy(new HierarchySnapshotBuilder());
    private final String project = "p-" + UUID.randomUUID();
    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private OWLOntology ontology;

    private OWLClass cls(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private void sub(OWLOntology ont, String child, String parent) {
        manager.addAxiom(ont, df.getOWLSubClassOfAxiom(cls(child), cls(parent)));
    }

    private OWLOntology ontologyWith(String... childParentPairs) throws Exception {
        OWLOntology ont = manager.createOntology(IRI.create("http://ex.org/t" + UUID.randomUUID()));
        for (int i = 0; i < childParentPairs.length; i += 2) {
            sub(ont, childParentPairs[i], childParentPairs[i + 1]);
        }
        return ont;
    }

    private List<String> children(OWLOntology ont, String parent) {
        var reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ont);
        return DesktopChildrenIndex.children(builder, project, ont, reasoner, NS + parent, 100, 0, Imports.EXCLUDED)
                .stream().map(n -> n.getId().replace(NS, "")).sorted().collect(Collectors.toList());
    }

    @Test
    void theIndexIsBuiltOnceAndReusedUntilTheOntologyChanges() throws Exception {
        ontology = ontologyWith("B", "A", "C", "A");

        assertEquals(List.of("B", "C"), children(ontology, "A"));
        assertEquals(List.of("B", "C"), children(ontology, "A"));
        assertEquals(List.of(), children(ontology, "B"));

        verify(builder, times(1)).assertedChildrenIndex(ontology, Imports.EXCLUDED);
    }

    @Test
    void anAddedSubclassShowsUpImmediately() throws Exception {
        ontology = ontologyWith("B", "A");
        assertEquals(List.of("B"), children(ontology, "A"));

        sub(ontology, "C", "A");

        assertEquals(List.of("B", "C"), children(ontology, "A"));
    }

    @Test
    void aReparentedClassMovesWithoutChangingTheAxiomCount() throws Exception {
        ontology = ontologyWith("B", "A", "X", "A", "Y", "A");
        assertEquals(List.of("B", "X", "Y"), children(ontology, "A"));

        manager.removeAxiom(ontology, df.getOWLSubClassOfAxiom(cls("B"), cls("A")));
        sub(ontology, "B", "X");

        assertEquals(List.of("X", "Y"), children(ontology, "A"));
        assertEquals(List.of("B"), children(ontology, "X"));
    }

    @Test
    void aReloadedOntologyForTheSameProjectNeverSeesTheOldIndex() throws Exception {
        OWLOntology first = ontologyWith("B", "A");
        assertEquals(List.of("B"), children(first, "A"));

        OWLOntology second = ontologyWith("C", "A", "D", "A");

        assertEquals(List.of("C", "D"), children(second, "A"));
    }

    @Test
    void topLevelAndTotalFollowEdits() throws Exception {
        ontology = ontologyWith("B", "A");
        var reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
        assertEquals(1, DesktopChildrenIndex.topLevelTotal(builder, project, ontology, Imports.EXCLUDED));

        sub(ontology, "D", "C");

        assertEquals(2, DesktopChildrenIndex.topLevelTotal(builder, project, ontology, Imports.EXCLUDED));
        assertEquals(2, DesktopChildrenIndex.topLevel(builder, project, ontology, 100, 0, Imports.EXCLUDED).size());
    }

    @Test
    void childrenAreOrderedByLabelIgnoringCase() throws Exception {
        ontology = ontologyWith("B", "A", "C", "A", "D", "A", "E", "A");
        labelled("B", "banana");
        labelled("C", "Apple");
        labelled("D", "cherry");
        labelled("E", "Zebra");
        var reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);

        List<String> ordered = DesktopChildrenIndex.children(builder, project, ontology, reasoner, NS + "A", 100, 0, Imports.EXCLUDED)
                .stream().map(n -> n.getId().replace(NS, "")).collect(Collectors.toList());

        assertEquals(List.of("C", "B", "D", "E"), ordered);
    }

    private void labelled(String name, String label) {
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls(name).getIRI(), df.getOWLLiteral(label)));
    }
}
