package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache.CachedOntology;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LargeOntologyEditRecordingTest {

    private static final String NS = "http://ex/";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;
    private ProjectOntologyCache cache;
    private DesktopOwlApiMutationService desktop;
    private HistoryChangeRepository history;

    @BeforeEach
    void setUp() throws Exception {
        OWLOntologyManager real = OWLManager.createOWLOntologyManager();
        factory = real.getOWLDataFactory();
        ontology = real.createOntology(IRI.create("http://ex/onto"));
        real.addAxiom(ontology, factory.getOWLDeclarationAxiom(clazz("A")));
        real.addAxiom(ontology, factory.getOWLDeclarationAxiom(clazz("B")));
        manager = spy(real);
        cache = mock(ProjectOntologyCache.class);
        when(cache.get("p")).thenReturn(Optional.of(new CachedOntology(ontology, null, manager, false)));
        desktop = mock(DesktopOwlApiMutationService.class);
        when(desktop.isAuthoritative("p")).thenReturn(true);
        history = mock(HistoryChangeRepository.class);
        compareLimit(1);
    }

    @AfterEach
    void restoreLimits() {
        Limit.install(Limit.resolve(Limit.Profile.STANDARD, Map.of()));
    }

    private static void compareLimit(int triples) {
        int[] values = Limit.resolve(Limit.Profile.STANDARD, Map.of());
        values[Limit.WHOLE_GRAPH_MAX_TRIPLES.ordinal()] = triples;
        Limit.install(values);
    }

    private OWLClass clazz(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private ExactChangeRecorder recorder() {
        ExactChangeRecorder recorder = new ExactChangeRecorder(mock(SparqlDatasetService.class), history,
                mock(OntologyHistoryService.class));
        ReflectionTestUtils.setField(recorder, "desktopOwlApi", desktop);
        ReflectionTestUtils.setField(recorder, "ontologyCache", cache);
        return recorder;
    }

    private static List<MutationOp> subClassOf() {
        return List.of(new MutationOp("addSubClassOf", NS + "A", null, null, null, null, NS + "B", null, null, null, null,
                null, null, null, null));
    }

    private void verifyNoWholeOntologyCopy() throws Exception {
        verify(manager, never()).saveOntology(any(OWLOntology.class), any(OWLDocumentFormat.class), any(OutputStream.class));
    }

    @Test
    void anEditOfAnOntologyTooLargeToCompareIsRecordedWithoutCopyingIt() throws Exception {
        String id = recorder().recordAround("p", false, "u", subClassOf(),
                () -> manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(clazz("A"), clazz("B"))), () -> "cs-1");

        assertEquals("cs-1", id);
        verifyNoWholeOntologyCopy();
        verify(history, never()).findByProjectIdAndChangeSetIdOrderByTimestampDesc(anyString(), anyString());
    }

    @Test
    void anEditThatChangesNothingInALargeOntologyIsStillNotRecorded() throws Exception {
        boolean[] recorded = new boolean[1];

        String id = recorder().recordAround("p", false, "u", subClassOf(),
                () -> manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(clazz("A"))),
                () -> {
                    recorded[0] = true;
                    return "cs-1";
                });

        assertNull(id);
        assertFalse(recorded[0]);
        verifyNoWholeOntologyCopy();
    }

    @Test
    void anEditThatReloadsTheLargeOntologyIsRecordedEvenThoughNoChangeWasSeen() throws Exception {
        OWLOntologyManager other = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = other.createOntology(IRI.create("http://ex/onto"));

        String id = recorder().recordAround("p", false, "u", subClassOf(),
                () -> when(cache.get("p")).thenReturn(Optional.of(new CachedOntology(reloaded, null, other, false))),
                () -> "cs-1");

        assertEquals("cs-1", id);
    }

    @Test
    void anOntologySmallEnoughToCompareStillGetsItsExactTriples() throws Exception {
        compareLimit(60_000);
        HistoryChange entry = new HistoryChange();
        entry.setId("e1");
        entry.setEntityIRI(NS + "A");
        entry.setChangeSetId("cs-1");
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(entry));

        recorder().recordAround("p", false, "u", subClassOf(),
                () -> manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(clazz("A"), clazz("B"))), () -> "cs-1");

        assertTrue(entry.carriesExactDelta());
        verify(manager, atLeastOnce()).saveOntology(any(OWLOntology.class), any(OWLDocumentFormat.class), any(OutputStream.class));
    }

    @Test
    void theUndoCheckDoesNotCopyTheOntologyWhenTheEditHasNoExactRecord() throws Exception {
        HistoryChange entry = new HistoryChange();
        entry.setEntityIRI(NS + "A");
        RollbackGraphFacts facts = new RollbackGraphFacts(mock(SparqlDatasetService.class), cache, desktop);

        assertTrue(facts.deltaHolds("p", entry, true));
        assertTrue(facts.deltaHolds("p", entry, false));
        verifyNoWholeOntologyCopy();
    }
}
