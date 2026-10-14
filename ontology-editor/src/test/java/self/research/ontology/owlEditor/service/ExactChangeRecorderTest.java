package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExactChangeRecorderTest {

    private static final String G = "urn:test:graph";
    private static final String DATA = """
            @prefix : <http://ex/> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            :A a owl:Class ; rdfs:label "A"@en .
            :B a owl:Class .
            """;

    private Repository repo;
    private HistoryChangeRepository history;
    private ExactChangeRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection c = repo.getConnection()) {
            c.add(new StringReader(DATA), "", RDFFormat.TURTLE, c.getValueFactory().createIRI(G));
        }
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        when(datasets.graphTarget("p")).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repo, G));
        when(datasets.getDraftGraphUri("p", "u")).thenReturn("urn:test:draft");
        history = mock(HistoryChangeRepository.class);
        recorder = new ExactChangeRecorder(datasets, history, mock(OntologyHistoryService.class));
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private static MutationOp op(String type, String iri, String property, String value, String target) {
        return new MutationOp(type, iri, null, null, property, value, target, null, null, null, null, null, null, null, null);
    }

    private void update(String sparql) {
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate(sparql.replace("INSERT DATA {", "INSERT DATA { GRAPH <" + G + "> {")
                    .replaceFirst("}\\s*$", "} }")).execute();
        }
    }

    private static HistoryChange entry(String id, String iri, long order) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setEntityIRI(iri);
        change.setChangeSetId("cs-1");
        change.setTimestamp(java.time.LocalDateTime.of(2026, 10, 13, 10, 0).plusSeconds(order));
        return change;
    }

    @Test
    void storesTheDeltaOnTheEarliestEntryAndMarksTheOthersCovered() {
        HistoryChange first = entry("e1", "http://ex/A", 0);
        HistoryChange second = entry("e2", "http://ex/B", 1);
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(second, first));
        List<MutationOp> ops = List.of(op("addSubClassOf", "http://ex/A", null, null, "http://ex/B"),
                op("addDisjointWith", "http://ex/B", null, null, "http://ex/C"));

        String id = recorder.recordAround("p", false, "u", ops, () -> update(
                "INSERT DATA { <http://ex/A> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://ex/B> . "
                        + "<http://ex/B> <http://www.w3.org/2002/07/owl#disjointWith> <http://ex/C> . }"), () -> "cs-1");

        assertEquals("cs-1", id);
        assertTrue(first.carriesExactDelta());
        assertEquals(1, first.getExactCovers());
        assertTrue(first.getExactInserted().contains("subClassOf"));
        assertTrue(first.getExactInserted().contains("disjointWith"));
        assertNull(first.getExactDeleted());
        assertEquals("e1", second.getCoveredBy());
        assertFalse(second.carriesExactDelta());
        verify(history).saveAll(anyList());
    }

    @Test
    void creatingAnEntityCarriesItsExactTriples() {
        HistoryChange created = entry("e1", "http://ex/New", 0);
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(created));
        List<MutationOp> ops = List.of(op("createClass", "http://ex/New", null, null, null));

        recorder.recordAround("p", false, "u", ops, () -> update(
                "INSERT DATA { <http://ex/New> a <http://www.w3.org/2002/07/owl#Class> . }"), () -> "cs-1");

        assertTrue(created.carriesExactDelta());
        assertTrue(created.getExactInserted().contains("http://ex/New"));
    }

    @Test
    void deletingAnEntityCapturesWhatReferredToItAcrossTheWholeGraph() {
        HistoryChange deleted = entry("e1", "http://ex/B", 0);
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(deleted));
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate("INSERT DATA { GRAPH <" + G + "> { <http://ex/A> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://ex/B> . } }").execute();
        }

        recorder.recordAround("p", false, "u", List.of(op("deleteClass", "http://ex/B", null, null, null)), () -> {
            try (RepositoryConnection c = repo.getConnection()) {
                c.prepareUpdate("DELETE WHERE { GRAPH <" + G + "> { ?s ?p <http://ex/B> } } ; DELETE WHERE { GRAPH <" + G
                        + "> { <http://ex/B> ?p ?o } }").execute();
            }
        }, () -> "cs-1");

        assertTrue(deleted.carriesExactDelta());
        assertTrue(deleted.getExactDeleted().contains("subClassOf"));
        assertNull(deleted.getExactInserted());
    }

    @Test
    void anOperationThatChangesNothingStoresNothing() {
        HistoryChange entry = entry("e1", "http://ex/A", 0);
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(entry));

        recorder.recordAround("p", false, "u", List.of(op("addSubClassOf", "http://ex/A", null, null, "http://ex/B")),
                () -> { }, () -> "cs-1");

        assertFalse(entry.carriesExactDelta());
        verify(history, never()).saveAll(anyList());
    }

    @Test
    void anOperationThatChangesNothingIsNotRecordedInHistoryAtAll() {
        boolean[] recorded = new boolean[1];

        String id = recorder.recordAround("p", false, "u", List.of(op("createClass", "http://ex/A", null, null, null)),
                () -> update("INSERT DATA { <http://ex/A> a <http://www.w3.org/2002/07/owl#Class> . }"), () -> {
                    recorded[0] = true;
                    return "cs-1";
                });

        assertNull(id);
        assertFalse(recorded[0]);
    }

    @Test
    void aRealChangeIsStillRecordedInHistory() {
        boolean[] recorded = new boolean[1];

        String id = recorder.recordAround("p", false, "u", List.of(op("createClass", "http://ex/New", null, null, null)),
                () -> update("INSERT DATA { <http://ex/New> a <http://www.w3.org/2002/07/owl#Class> . }"), () -> {
                    recorded[0] = true;
                    return "cs-1";
                });

        assertEquals("cs-1", id);
        assertTrue(recorded[0]);
    }

    @Test
    void aFailingMutationPropagatesAndStoresNothing() {
        HistoryChange entry = entry("e1", "http://ex/A", 0);
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(entry));

        assertThrows(IllegalStateException.class, () -> recorder.recordAround("p", false, "u",
                List.of(op("addSubClassOf", "http://ex/A", null, null, "http://ex/B")), () -> {
                    throw new IllegalStateException("store offline");
                }, () -> "cs-1"));

        verify(history, never()).saveAll(anyList());
    }

    @Test
    void aMissingChangeSetIdIsNotAnError() {
        String id = recorder.recordAround("p", false, "u", List.of(op("addSubClassOf", "http://ex/A", null, null, "http://ex/B")),
                () -> update("INSERT DATA { <http://ex/A> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://ex/B> . }"),
                () -> null);

        assertNull(id);
        verify(history, never()).saveAll(anyList());
    }

    @Test
    void readingFailuresNeverBlockTheMutation() {
        SparqlDatasetService broken = mock(SparqlDatasetService.class);
        when(broken.graphTarget("p")).thenThrow(new IllegalStateException("no store"));
        ExactChangeRecorder fragile = new ExactChangeRecorder(broken, history, mock(OntologyHistoryService.class));
        List<String> order = new ArrayList<>();

        String id = fragile.recordAround("p", false, "u", List.of(op("addSubClassOf", "http://ex/A", null, null, "http://ex/B")),
                () -> order.add("applied"), () -> {
                    order.add("recorded");
                    return "cs-9";
                });

        assertEquals("cs-9", id);
        assertEquals(List.of("applied", "recorded"), order);
    }
}
