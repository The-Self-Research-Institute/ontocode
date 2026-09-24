package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.service.SparqlDatasetService.CappedSparqlResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AssistantSparqlToolServiceTest {

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private SparqlDatasetService datasetService;

    private AssistantSparqlToolService toolService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        toolService = new AssistantSparqlToolService(sessionService, datasetService);
        ReflectionTestUtils.setField(toolService, "maxRows", 200);
        ReflectionTestUtils.setField(toolService, "maxBytes", 200000L);
        ReflectionTestUtils.setField(toolService, "timeoutSeconds", 15);
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(true);
        when(sessionService.isRevisionStale(any())).thenReturn(false);
    }

    @Test
    void returnsRevisionStaleWhenProjectHasMovedOnAndNeverSpendsBudget() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.isRevisionStale(any())).thenReturn(true);

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT * WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("REVISION_STALE", result.getErrorCode());
        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never()).tryConsumeRetrievalAttempt(anyString());
    }

    @Test
    void returnsSessionNotFoundWhenSessionMissing() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.empty());

        AssistantSparqlToolService.SparqlToolResult result = toolService.runSparql("s1", "u@x.com", "SELECT * WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("SESSION_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void rejectsNonSelectQueries() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "DELETE WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("NOT_SELECT_ONLY", result.getErrorCode());
    }

    @Test
    void allowsSelectWithLeadingPrefixDeclarations() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("s"), List.of(), false, null));

        AssistantSparqlToolService.SparqlToolResult result = toolService.runSparql(
                "s1", "u@x.com", "PREFIX ex: <http://example.org/> SELECT ?s WHERE { ?s a ex:Thing }");

        assertTrue(result.isOk());
    }

    @Test
    void returnsBudgetExhaustedWhenRetrievalAttemptFails() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(false);

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT * WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("BUDGET_EXHAUSTED", result.getErrorCode());
    }

    @Test
    void returnsCappedRowsOnSuccessWhenUnderCap() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("s", "p"), List.of(Map.of("s", "a", "p", "b")), false, null));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT ?s ?p WHERE { ?s ?p ?o }");

        assertTrue(result.isOk());
        assertFalse(result.isTruncated());
        assertEquals(1, result.getRowCount());
        assertEquals(42L, result.getRevision());
    }

    @Test
    void returnsRowCapExceededErrorWhenRowCapHit() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("s"), List.of(), true, "ROW_CAP_EXCEEDED"));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT ?s WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("ROW_CAP_EXCEEDED", result.getErrorCode());
    }

    @Test
    void returnsByteCapExceededErrorWhenByteCapHit() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("s"), List.of(), true, "BYTE_CAP_EXCEEDED"));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT ?s WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("BYTE_CAP_EXCEEDED", result.getErrorCode());
    }

    @Test
    void returnsBudgetExhaustedWhenTokenBudgetFails() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(false);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("s", "p"), List.of(Map.of("s", "a", "p", "b")), false, null));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT ?s ?p WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("BUDGET_EXHAUSTED", result.getErrorCode());
    }

    @Test
    void mapsTimeoutMessageToTimeoutErrorCode() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("Query interrupted"));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT * WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("TIMEOUT", result.getErrorCode());
    }

    @Test
    void mapsOtherFailuresToQueryError() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("malformed query"));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT * WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("QUERY_ERROR", result.getErrorCode());
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
