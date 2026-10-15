package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache.CachedOntology;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopExactUndoTest {

    private static final String NS = "http://ex/";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;
    private ProjectOntologyCache cache;
    private DesktopOwlApiMutationService desktop;
    private HistoryChangeRepository history;

    @BeforeEach
    void setUp() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://ex/onto"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(clazz("A")));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(clazz("B")));
        cache = mock(ProjectOntologyCache.class);
        when(cache.get("p")).thenReturn(Optional.of(new CachedOntology(ontology, null, manager, false)));
        desktop = mock(DesktopOwlApiMutationService.class);
        when(desktop.isAuthoritative("p")).thenReturn(true);
        history = mock(HistoryChangeRepository.class);
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

    private static MutationOp op(String type, String iri, String target) {
        return new MutationOp(type, iri, null, null, null, null, target, null, null, null, null, null, null, null, null);
    }

    private HistoryChange recordSubClassOfEdit() {
        HistoryChange entry = new HistoryChange();
        entry.setId("e1");
        entry.setEntityIRI(NS + "A");
        entry.setChangeSetId("cs-1");
        entry.setTimestamp(java.time.LocalDateTime.of(2026, 10, 14, 10, 0));
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(entry));
        recorder().recordAround("p", false, "u", List.of(op("addSubClassOf", NS + "A", NS + "B")),
                () -> manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(clazz("A"), clazz("B"))), () -> "cs-1");
        return entry;
    }

    @Test
    void anEditOfTheInMemoryOntologyIsRecordedWithItsExactTriples() {
        HistoryChange entry = recordSubClassOfEdit();

        assertTrue(entry.carriesExactDelta());
        assertTrue(entry.getExactInserted().contains("subClassOf"));
        assertNull(entry.getExactDeleted());
    }

    @Test
    void theRecordedChangeHoldsUntilSomeoneElseChangesTheOntology() {
        HistoryChange entry = recordSubClassOfEdit();
        RollbackGraphFacts facts = new RollbackGraphFacts(mock(SparqlDatasetService.class), cache, desktop);

        assertTrue(facts.deltaHolds("p", entry, true));
        assertFalse(facts.deltaHolds("p", entry, false));

        manager.removeAxiom(ontology, factory.getOWLSubClassOfAxiom(clazz("A"), clazz("B")));

        assertFalse(facts.deltaHolds("p", entry, true));
        assertTrue(facts.deltaHolds("p", entry, false));
    }

    @Test
    void anOntologyThatIsNotInMemoryFallsBackToTheGraphStore() {
        when(desktop.isAuthoritative("p")).thenReturn(false);
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        when(datasets.graphTarget("p")).thenThrow(new IllegalStateException("no store"));
        HistoryChange entry = new HistoryChange();
        entry.setEntityIRI(NS + "A");
        entry.setChangeSetId("cs-1");
        ExactChangeRecorder recorder = new ExactChangeRecorder(datasets, history, mock(OntologyHistoryService.class));
        ReflectionTestUtils.setField(recorder, "desktopOwlApi", desktop);
        ReflectionTestUtils.setField(recorder, "ontologyCache", cache);

        recorder.recordAround("p", false, "u", List.of(op("addSubClassOf", NS + "A", NS + "B")), () -> { }, () -> "cs-1");

        verify(datasets).graphTarget("p");
        assertFalse(entry.carriesExactDelta());
    }

    @Test
    void aDeletionWithoutAnExactRecordIsNotRestoredFromPartialDataOnDesktopEither() {
        RollbackGraphFacts facts = new RollbackGraphFacts(mock(SparqlDatasetService.class), cache, desktop);

        assertTrue(facts.storeRecordsExact("p", false));
    }

    @Test
    void anEditThatLeavesTheInMemoryOntologyUnchangedIsNotRecordedInHistory() {
        boolean[] recorded = new boolean[1];

        String id = recorder().recordAround("p", false, "u", List.of(op("addSubClassOf", NS + "A", NS + "B")), () -> { },
                () -> {
                    recorded[0] = true;
                    return "cs-1";
                });

        assertNull(id);
        assertFalse(recorded[0]);
    }

    @Test
    void theSnapshotIsEmptyWithoutACache() {
        assertTrue(OwlApiModelSnapshot.of(null, "p").isEmpty());
        when(cache.get("none")).thenReturn(Optional.empty());
        assertTrue(OwlApiModelSnapshot.of(cache, "none").isEmpty());
        assertFalse(OwlApiModelSnapshot.of(cache, "p").orElseThrow().isEmpty());
    }

    @Test
    void rawUpdatesRunAgainstTheInMemoryOntologyAndPersistIt() throws Exception {
        InMemorySparqlOntologyMutator mutator = mock(InMemorySparqlOntologyMutator.class);
        DesktopOntologyLoader loader = mock(DesktopOntologyLoader.class);
        DesktopFusekiSyncScheduler scheduler = mock(DesktopFusekiSyncScheduler.class);
        StorageManager storage = mock(StorageManager.class);
        when(mutator.tryApply("p", "DELETE { } WHERE { }")).thenReturn(true);
        DesktopOwlApiMutationService real = new DesktopOwlApiMutationService(cache, mock(OwlApiMutationPatcher.class), mutator,
                storage, loader, mock(ProjectImportService.class), scheduler);
        ReflectionTestUtils.setField(real, "owlApiFirst", true);
        when(cache.has("p")).thenReturn(true);

        assertTrue(real.tryApplyRaw("p", "DELETE { } WHERE { }"));

        verify(loader).persistToDisk("p");
        verify(scheduler).scheduleAfterMutation("p");
        verify(storage).clearCodeViewCache("p");
    }

    @Test
    void aRawUpdateTheInMemoryModelCannotTakeIsLeftToTheGraphStore() throws Exception {
        InMemorySparqlOntologyMutator mutator = mock(InMemorySparqlOntologyMutator.class);
        DesktopOntologyLoader loader = mock(DesktopOntologyLoader.class);
        DesktopOwlApiMutationService real = new DesktopOwlApiMutationService(cache, mock(OwlApiMutationPatcher.class), mutator,
                mock(StorageManager.class), loader, null, null);
        when(cache.has("p")).thenReturn(true);

        assertFalse(real.tryApplyRaw("p", "DELETE { } WHERE { }"));
        ReflectionTestUtils.setField(real, "owlApiFirst", true);
        when(mutator.tryApply(anyString(), anyString())).thenReturn(false);
        assertFalse(real.tryApplyRaw("p", "DELETE { } WHERE { }"));
        assertFalse(real.tryApplyRaw("p", " "));
        verify(loader, never()).persistToDisk(anyString());
    }
}
