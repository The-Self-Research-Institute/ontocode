package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.service.AssistantContextToolService.ContextToolResult;
import self.research.ontology.owlEditor.service.AssistantContextToolService.Target;
import self.research.ontology.owlEditor.service.SparqlDatasetService.CappedSparqlResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AssistantContextToolServiceTest {

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private SparqlDatasetService datasetService;

    @Mock
    private StorageManager storageManager;

    private AssistantContextToolService toolService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        toolService = new AssistantContextToolService(sessionService, datasetService, storageManager);
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(true);
    }

    @Test
    void returnsSessionNotFoundWhenSessionMissing() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.empty());

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertFalse(result.isOk());
        assertEquals("SESSION_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void returnsBudgetExhaustedWhenRetrievalAttemptFails() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(false);

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertFalse(result.isOk());
        assertEquals("BUDGET_EXHAUSTED", result.getErrorCode());
    }

    @Test
    void returnsBudgetExhaustedWhenTokenBudgetFails() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(false);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("p", "o"),
                        List.of(Map.of("p", "rdf:type", "o", "owl:Class")), false, null));

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertFalse(result.isOk());
        assertEquals("BUDGET_EXHAUSTED", result.getErrorCode());
    }

    @Test
    void resolvesIdentifierDefinitionsViaSparql() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("p", "o"),
                        List.of(Map.of("p", "rdf:type", "o", "owl:Class")), false, null));

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertTrue(result.isOk());
        assertEquals("complete", result.getCoverage());
        assertEquals(1, result.getItems().size());
        assertEquals("definitions", result.getItems().get(0).getKind());
    }

    @Test
    void identifierCapExceededMarksCoveragePartialWithoutFailingTheWholeCall() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("p", "o"), List.of(), true, "ROW_CAP_EXCEEDED"));

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertTrue(result.isOk());
        assertEquals("partial", result.getCoverage());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    void resolvesRangeTargetViaCodeViewPagination() throws Exception {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 100L, 50))
                .thenReturn(new StorageManager.CodeViewPage(":A a owl:Class .", 100L, 1, 500L, 12345L));

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("range", "turtle:100-50")), "definitions");

        assertTrue(result.isOk());
        assertEquals("complete", result.getCoverage());
        assertEquals(":A a owl:Class .", result.getItems().get(0).getText());
    }

    @Test
    void duplicateTargetsAreResolvedOnceNotOncePerOccurrence() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("p", "o"),
                        List.of(Map.of("p", "rdf:type", "o", "owl:Class")), false, null));

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com",
                List.of(new Target("identifier", "http://ex.org/A"), new Target("identifier", "http://ex.org/A")),
                "definitions");

        assertTrue(result.isOk());
        assertEquals(1, result.getItems().size());
        org.mockito.Mockito.verify(datasetService, org.mockito.Mockito.times(1))
                .execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void diagnosticsKindReturnsPartialCoverageWithoutFakingData() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "diagnostics");

        assertTrue(result.isOk());
        assertEquals("partial", result.getCoverage());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    void aTargetFailureMarksCoveragePartialWithoutFailingTheWholeCall() throws Exception {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(storageManager.readCodeViewPage(anyString(), anyString(), anyLong(), anyInt()))
                .thenThrow(new java.io.IOException("boom"));

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("range", "turtle:0-10")), "definitions");

        assertTrue(result.isOk());
        assertEquals("partial", result.getCoverage());
        assertTrue(result.getItems().isEmpty());
    }

    private AssistantSessionDocument activeSession() {
        return AssistantSessionDocument.builder()
                .id("s1")
                .projectId("proj-1")
                .pinnedRevision(42L)
                .status(AssistantSessionStatus.ACTIVE)
                .build();
    }
}
