package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishedHistoryTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI GRAPH = VF.createIRI("urn:test:project");
    private static final IRI P = VF.createIRI("http://ex.org/p");
    private static final IRI A = VF.createIRI("http://ex.org/A");
    private static final IRI B = VF.createIRI("http://ex.org/B");
    private static final IRI C = VF.createIRI("http://ex.org/C");

    private SailRepository repository;
    private MongoTemplate mongo;
    private OntologyHistoryService history;
    private PublishedHistory published;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        when(datasets.graphTarget("p")).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, GRAPH.stringValue()));
        mongo = mock(MongoTemplate.class);
        history = mock(OntologyHistoryService.class);
        published = new PublishedHistory(mongo, datasets, history);
    }

    private static HistoryChange row(String id, IRI object) {
        HistoryChange change = new HistoryChange.Builder("p", "edit-" + id, "u1", "User One").operationType("addX")
                .entityIRI(A.stringValue()).build();
        change.setId(id);
        change.setDraft(true);
        var inserted = new LinkedHashModel();
        inserted.add(A, P, object);
        change.setExactInserted(ExactDelta.write(inserted));
        return change;
    }

    private void publish(List<HistoryChange> rows, IRI... added) {
        when(mongo.find(any(Query.class), eq(HistoryChange.class))).thenReturn(rows);
        Optional<DeltaCapture> capture = published.begin("p");
        try (RepositoryConnection c = repository.getConnection()) {
            for (IRI object : added) {
                c.add(A, P, object, GRAPH);
            }
        }
        published.reconcile("p", "u1", capture);
    }

    @Test
    void rowsMatchingWhatWasCommittedAreLeftUndoableAndNothingExtraIsRecorded() {
        publish(List.of(row("r1", B)), B);

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(HistoryChange.class));
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(),
                anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void aRowThatDidNotReachThePublicGraphIsBlockedFromUndo() {
        publish(List.of(row("r1", B), row("r2", C)), B);

        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(HistoryChange.class));
    }

    @Test
    void changesNoDraftRowAccountsForAreRecordedAsOneExtraEntry() {
        publish(List.of(row("r1", B)), B, C);

        verify(history).recordEdit(eq("p"), eq("u1"), eq("User One"), eq("publishDraft"), any(), any(), any(), any(),
                eq("Published draft: 1 added, 0 removed beyond your recorded edits"), any(), any(), eq(false), any());
    }

    @Test
    void whenARowHasNoRecordedChangeNothingIsRewritten() {
        HistoryChange plain = new HistoryChange.Builder("p", "edit-x", "u1", "User One").operationType("addX").build();
        plain.setId("x");
        plain.setDraft(true);

        publish(List.of(row("r1", B), plain), B, C);

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(HistoryChange.class));
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(),
                anyString(), any(), any(), anyBoolean(), any());
    }
}
