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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CitationInsertTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String P = "p1";
    private static final String DRAFT = "urn:draft:u1";
    private static final String MAIN = "urn:main";
    private static final String CITATION = "urn:citation:jones2021";
    private static final String TURTLE = "<" + CITATION + "> <http://purl.org/dc/terms/title> \"A new paper\" .";

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
        datasets = mock(SparqlDatasetService.class);
        when(datasets.graphTarget(P)).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, MAIN));
        when(datasets.getDraftGraphUri(P, "u1")).thenReturn(DRAFT);
        doAnswer(inv -> applyTo(MAIN, inv.getArgument(1))).when(datasets).execUpdate(eq(P), anyString());
        history = mock(OntologyHistoryService.class);
        mutations = mock(OntologyMutationService.class);
        doAnswer(inv -> applyTo(DRAFT, inv.getArgument(1))).when(mutations).applyRawUpdate(eq(P), anyString(), eq(true), eq("u1"));
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

    private Object applyTo(String graph, String sparql) {
        try (RepositoryConnection c = repository.getConnection()) {
            Update update = c.prepareUpdate(sparql);
            SimpleDataset dataset = new SimpleDataset();
            IRI target = VF.createIRI(graph);
            dataset.addDefaultGraph(target);
            dataset.addDefaultRemoveGraph(target);
            dataset.setDefaultInsertGraph(target);
            update.setDataset(dataset);
            update.execute();
        }
        return null;
    }

    private boolean present(String graph) {
        try (RepositoryConnection c = repository.getConnection()) {
            return c.hasStatement(VF.createIRI(CITATION), null, null, false, VF.createIRI(graph));
        }
    }

    private ChangeOrigin recordedInsert(boolean draft) {
        ArgumentCaptor<ChangeOrigin> origin = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(history).recordEdit(eq(P), eq("u1"), eq("User One"), eq("INSERT_CITATION"), eq(CITATION), any(), any(),
                any(), any(), any(), any(), eq(draft), origin.capture());
        return origin.getValue();
    }

    @Test
    void aPublicInsertWritesTheSharedGraphAndRecordsOneUndoableHistoryRow() {
        citations.insertCitation(P, TURTLE, "turtle", Map.of("title", "A new paper"), new RequestActor("u1", "User One"), false);

        assertTrue(present(MAIN));
        assertFalse(present(DRAFT));
        ChangeOrigin origin = recordedInsert(false);
        assertNotNull(origin.exact());
        Map<String, Object> stamped = new HashMap<>();
        origin.exact().stamp(stamped, "edit-1");
        assertTrue(((String) stamped.get("exactInserted")).contains(CITATION));
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verifyNoInteractions(drafts);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aDraftInsertOnlyChangesTheDraftAndListsItForReview() {
        citations.insertCitation(P, TURTLE, "turtle", Map.of(), new RequestActor("u1", "User One"), true);

        assertTrue(present(DRAFT));
        assertFalse(present(MAIN));
        verify(datasets, never()).execUpdate(anyString(), anyString());
        ChangeOrigin origin = recordedInsert(true);
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Collection<String>> changeSets = ArgumentCaptor.forClass(Collection.class);
        verify(drafts).recordDescribed(eq(P), eq("u1"), eq("User One"), eq(DraftRecords.ADD_CITATION), data.capture(),
                changeSets.capture());
        assertEquals(CITATION, data.getValue().get("iri"));
        assertEquals(List.of(origin.changeSetId()), List.copyOf(changeSets.getValue()));
    }

    @Test
    void theTurtleTheWebAppSendsIsParsedAndRecordedUnderTheCitationIri() {
        String webAppTurtle = String.join("\n",
                "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
                "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .",
                "@prefix dc: <http://purl.org/dc/elements/1.1/> .",
                "@prefix prov: <http://www.w3.org/ns/prov#> .",
                "",
                "",
                "###  Citation: A new paper",
                "<" + CITATION + "> rdf:type owl:NamedIndividual ,",
                "         prov:Entity ;",
                "    dc:title \"A new paper\" ;",
                "    dc:date \"2021\"^^xsd:gYear .",
                "");

        citations.insertCitation(P, webAppTurtle, "turtle", Map.of(), new RequestActor("u1", "User One"), false);

        assertTrue(present(MAIN));
        assertNotNull(recordedInsert(false).exact());
    }

    @Test
    void aProjectIdWithASuffixIsWrittenAndRecordedOnTheSameProject() {
        citations.insertCitation(P + "$codeview", TURTLE, "turtle", Map.of(), new RequestActor("u1", "User One"), false);

        assertTrue(present(MAIN));
        assertNotNull(recordedInsert(false).exact());
    }

    @Test
    void theControllerPassesTheDraftFlagFromTheUrl() {
        CitationService service = mock(CitationService.class);
        CitationController controller = new CitationController();
        ReflectionTestUtils.setField(controller, "citationService", service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/citations/p1/insert");
        request.setParameter("draft", "true");

        ResponseEntity<?> response = controller.insertCitation(P,
                new CitationController.InsertCitationRequest(TURTLE, "turtle", Map.of(), 0, "u1", "User One"), null, request);

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<RequestActor> actor = ArgumentCaptor.forClass(RequestActor.class);
        verify(service).insertCitation(eq(P), eq(TURTLE), eq("turtle"), any(), actor.capture(), eq(true));
        assertEquals("u1", actor.getValue().userId());
    }

    @Test
    void aDraftInsertWithoutAUserIsRefused() {
        CitationService service = mock(CitationService.class);
        CitationController controller = new CitationController();
        ReflectionTestUtils.setField(controller, "citationService", service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/citations/p1/insert");

        ResponseEntity<?> response = controller.insertCitation(P,
                new CitationController.InsertCitationRequest(TURTLE, "turtle", Map.of(), 0, null, null), "true", request);

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
