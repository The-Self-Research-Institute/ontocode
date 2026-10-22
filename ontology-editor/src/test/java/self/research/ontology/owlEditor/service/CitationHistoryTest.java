package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitationHistoryTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI GRAPH = VF.createIRI("urn:test:project");
    private static final IRI CITATION = VF.createIRI("http://ex.org/cite/1");
    private static final IRI TITLE = VF.createIRI("http://purl.org/dc/terms/title");
    private static final IRI TYPE = VF.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
    private static final IRI ARTICLE = VF.createIRI("http://purl.org/ontology/bibo/Article");

    private SailRepository repository;
    private OntologyHistoryService history;
    private CitationHistory citationHistory;
    private final RequestActor actor = new RequestActor("alice@x.com", "Alice");

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        when(datasets.graphTarget("p")).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, GRAPH.stringValue()));
        history = mock(OntologyHistoryService.class);
        citationHistory = new CitationHistory(datasets, history, new ProjectWriteLockRegistry());
    }

    private ChangeOrigin recordedOrigin(String operation) {
        ArgumentCaptor<ChangeOrigin> origin = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(history).recordEdit(eq("p"), eq("alice@x.com"), eq("Alice"), eq(operation), eq(CITATION.stringValue()),
                anyString(), any(), any(), anyString(), any(), any(), anyBoolean(), origin.capture());
        return origin.getValue();
    }

    private static Map<String, Object> stamped(ChangeOrigin origin) {
        Map<String, Object> data = new HashMap<>();
        origin.exact().stamp(data, "edit-1");
        return data;
    }

    @Test
    void anInsertedCitationIsRecordedWithTheTriplesItAdded() {
        citationHistory.around("p", actor,
                new CitationHistory.Entry("INSERT_CITATION", CITATION.stringValue(), "A paper", null, "ttl", "Inserted"),
                List.of(CITATION.stringValue()), List.of(), () -> {
                    try (RepositoryConnection c = repository.getConnection()) {
                        c.add(CITATION, TYPE, ARTICLE, GRAPH);
                        c.add(CITATION, TITLE, VF.createLiteral("A paper"), GRAPH);
                    }
                });

        ChangeOrigin origin = recordedOrigin("INSERT_CITATION");
        assertNotNull(origin.exact());
        String inserted = (String) stamped(origin).get("exactInserted");
        assertTrue(inserted.contains("A paper"));
        assertTrue(inserted.contains("bibo/Article"));
    }

    @Test
    void aDeletedCitationIsRecordedWithTheTriplesItRemoved() {
        try (RepositoryConnection c = repository.getConnection()) {
            c.add(CITATION, TITLE, VF.createLiteral("A paper"), GRAPH);
        }

        citationHistory.around("p", actor,
                new CitationHistory.Entry("DELETE_CITATION", CITATION.stringValue(), "Citation", "x", null, "Deleted"),
                List.of(CITATION.stringValue()), List.of(CITATION.stringValue()), () -> {
                    try (RepositoryConnection c = repository.getConnection()) {
                        c.remove(CITATION, null, null, GRAPH);
                    }
                });

        String deleted = (String) stamped(recordedOrigin("DELETE_CITATION")).get("exactDeleted");
        assertTrue(deleted.contains("A paper"));
    }

    @Test
    void aChangeThatTheGraphCannotCaptureIsStillRecordedWithoutADelta() {
        SparqlDatasetService broken = mock(SparqlDatasetService.class);
        when(broken.graphTarget("p")).thenThrow(new IllegalStateException("store offline"));
        new CitationHistory(broken, history, new ProjectWriteLockRegistry()).around("p", actor,
                new CitationHistory.Entry("INSERT_CITATION", CITATION.stringValue(), "A paper", null, "ttl", "Inserted"),
                List.of(CITATION.stringValue()), List.of(), () -> { });

        assertTrue(recordedOrigin("INSERT_CITATION").exact() == null);
    }
}
