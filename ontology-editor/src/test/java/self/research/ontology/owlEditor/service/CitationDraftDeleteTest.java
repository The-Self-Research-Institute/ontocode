package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.controller.CitationController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitationDraftDeleteTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String P = "p1";
    private static final String DRAFT = "urn:draft:u1";
    private static final String MAIN = "urn:main";
    private static final String CITATION = "urn:citation:smith2020";

    private SailRepository repository;
    private SparqlDatasetService datasets;
    private OntologyHistoryService history;
    private OntologyMutationService mutations;
    private DraftTrackingService drafts;
    private CitationService citations;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection c = repository.getConnection()) {
            for (String graph : List.of(DRAFT, MAIN)) {
                c.add(VF.createIRI(CITATION), VF.createIRI("http://purl.org/dc/terms/title"), VF.createLiteral("A paper"),
                        VF.createIRI(graph));
            }
        }
        datasets = mock(SparqlDatasetService.class);
        when(datasets.graphTarget(P)).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, MAIN));
        when(datasets.getDraftGraphUri(P, "u1")).thenReturn(DRAFT);
        history = mock(OntologyHistoryService.class);
        mutations = mock(OntologyMutationService.class);
        doAnswer(inv -> {
            try (RepositoryConnection c = repository.getConnection()) {
                Update update = c.prepareUpdate((String) inv.getArgument(1));
                SimpleDataset dataset = new SimpleDataset();
                IRI graph = VF.createIRI(DRAFT);
                dataset.addDefaultGraph(graph);
                dataset.addDefaultRemoveGraph(graph);
                dataset.setDefaultInsertGraph(graph);
                update.setDataset(dataset);
                update.execute();
            }
            return null;
        }).when(mutations).applyRawUpdate(eq(P), anyString(), eq(true), eq("u1"));
        drafts = mock(DraftTrackingService.class);
        citations = new CitationService();
        ReflectionTestUtils.setField(citations, "datasetService", datasets);
        ReflectionTestUtils.setField(citations, "citationHistory",
                new CitationHistory(datasets, history, new ProjectWriteLockRegistry()));
        ReflectionTestUtils.setField(citations, "mutationService", mutations);
        ReflectionTestUtils.setField(citations, "draftTracking", drafts);
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    private boolean present(String graph) {
        try (RepositoryConnection c = repository.getConnection()) {
            return c.hasStatement(VF.createIRI(CITATION), null, null, false, VF.createIRI(graph));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void deletingACitationInADraftOnlyChangesTheDraftAndListsItForReview() {
        citations.deleteCitation(P, CITATION, new RequestActor("u1", "User One"), true);

        assertFalse(present(DRAFT));
        assertTrue(present(MAIN));
        verify(datasets, never()).execUpdate(anyString(), anyString());
        ArgumentCaptor<ChangeOrigin> origin = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(history).recordEdit(eq(P), eq("u1"), eq("User One"), eq("DELETE_CITATION"), eq(CITATION), any(), any(),
                any(), any(), any(), any(), eq(true), origin.capture());
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Collection<String>> changeSets = ArgumentCaptor.forClass(Collection.class);
        verify(drafts).recordDescribed(eq(P), eq("u1"), eq("User One"), eq(DraftRecords.DELETE_CITATION), data.capture(),
                changeSets.capture());
        assertEquals(CITATION, data.getValue().get("iri"));
        assertEquals(List.of(origin.getValue().changeSetId()), List.copyOf(changeSets.getValue()));
    }

    @Test
    void aPublicDeleteStillGoesToTheSharedGraph() {
        citations.deleteCitation(P, CITATION, new RequestActor("u1", "User One"), false);

        verify(datasets).execUpdate(eq(P), anyString());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(drafts, never()).recordDescribed(anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    void theControllerPassesTheDraftFlagFromTheUrl() {
        CitationService service = mock(CitationService.class);
        CitationController controller = new CitationController();
        ReflectionTestUtils.setField(controller, "citationService", service);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/citations/p1/x");
        request.setParameter("draft", "true");

        ResponseEntity<?> response = controller.deleteCitation(P, CITATION, "u1", "User One", null, request);

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<RequestActor> actor = ArgumentCaptor.forClass(RequestActor.class);
        verify(service).deleteCitation(eq(P), eq(CITATION), actor.capture(), eq(true));
        assertEquals("u1", actor.getValue().userId());
    }
}
