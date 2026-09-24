package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.service.SparqlDatasetService.CappedSparqlResult;

import java.time.Clock;
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
    private AssistantAdmissionLimiter limiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        limiter = new AssistantAdmissionLimiter(4, 2, 32, 30, 3, Clock.systemUTC());
        toolService = new AssistantSparqlToolService(sessionService, datasetService, new ProjectWriteLockRegistry(),
                limiter);
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
    void revisionThatMovesDuringTheQueryDiscardsTheResultInsteadOfMislabelingIt() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(sessionService.isRevisionStale(any())).thenReturn(false, true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("s"), List.of(Map.of("s", "a")), false, null));

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT ?s WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("REVISION_STALE", result.getErrorCode());
        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never()).tryConsumeTokenBudget(anyString(), anyInt());
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

    @Test
    void projectCapacityRejectsWithRateLimitedAndNeverSpendsARetrievalAttempt() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        AssistantAdmissionLimiter.ToolAdmission a = limiter.tryAcquireTool("other1@x.com", "proj-1");
        AssistantAdmissionLimiter.ToolAdmission b = limiter.tryAcquireTool("other2@x.com", "proj-1");

        AssistantSparqlToolService.SparqlToolResult result =
                toolService.runSparql("s1", "u@x.com", "SELECT * WHERE { ?s ?p ?o }");

        assertFalse(result.isOk());
        assertEquals("RATE_LIMITED", result.getErrorCode());
        assertEquals(3, result.getRetryAfterSeconds());
        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never()).tryConsumeRetrievalAttempt(anyString());
        assertEquals(0, limiter.inFlightForUser("u@x.com"));
        ((AssistantAdmissionLimiter.Admitted) a).close();
        ((AssistantAdmissionLimiter.Admitted) b).close();
    }

    @Test
    void slotIsReleasedWhenTheQueryThrows() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("parse error"));

        for (int i = 0; i < 6; i++) {
            assertEquals("QUERY_ERROR",
                    toolService.runSparql("s1", "u@x.com", "SELECT * WHERE { ?s ?p ?o }").getErrorCode());
        }
        assertEquals(0, limiter.inFlightForProject("proj-1"));
        assertEquals(0, limiter.inFlightGlobal());
    }

    @Test
    void nonSelectQueriesAreRejectedWithoutTakingASlot() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        AssistantAdmissionLimiter.ToolAdmission a = limiter.tryAcquireTool("other1@x.com", "proj-1");
        AssistantAdmissionLimiter.ToolAdmission b = limiter.tryAcquireTool("other2@x.com", "proj-1");

        assertEquals("NOT_SELECT_ONLY",
                toolService.runSparql("s1", "u@x.com", "DELETE WHERE { ?s ?p ?o }").getErrorCode());
        ((AssistantAdmissionLimiter.Admitted) a).close();
        ((AssistantAdmissionLimiter.Admitted) b).close();
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
