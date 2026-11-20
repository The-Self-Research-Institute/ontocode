package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveAxiomCountsTest {

    @AfterEach
    void clear() {
        SparqlQueryContext.clear();
    }

    private static OWLOntology ontologyWithTwoClasses() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://ex.org/o"));
        var a = df.getOWLClass(IRI.create("http://ex.org/o#A"));
        var b = df.getOWLClass(IRI.create("http://ex.org/o#B"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(b, a));
        return ontology;
    }

    @Test
    void theLiveModelAnswersTheAxiomCountsWithoutReloadingTheOntology() throws Exception {
        OWLOntology ontology = ontologyWithTwoClasses();
        var manchester = mock(ManchesterExpressionService.class);
        var dataset = mock(SparqlDatasetService.class);
        var metrics = new OntologyMetrics(dataset, mock(ProjectMetadataService.class), null, manchester, null,
                p -> null, (project, user) -> 0L, p -> Optional.of(ontology));

        var counts = metrics.getOwlApiAxiomCounts("p1");

        assertEquals(ontology.getAxiomCount(), counts.total());
        assertEquals(ontology.getLogicalAxiomCount(), counts.logical());
        verify(manchester, never()).loadFreshOntology("p1");
    }

    @Test
    void aUserWithADraftNeverGetsTheSharedLiveModelCounts() throws Exception {
        OWLOntology ontology = ontologyWithTwoClasses();
        var dataset = mock(SparqlDatasetService.class);
        when(dataset.hasActiveDraftOverlay("p1", "kay")).thenReturn(true);
        var metrics = new OntologyMetrics(dataset, mock(ProjectMetadataService.class), null,
                mock(ManchesterExpressionService.class), null, p -> null, (project, user) -> 0L,
                p -> Optional.of(ontology));
        SparqlQueryContext.setUserId("kay");

        var counts = metrics.getOwlApiAxiomCounts("p1");

        assertEquals(0, counts.total());
    }
}
