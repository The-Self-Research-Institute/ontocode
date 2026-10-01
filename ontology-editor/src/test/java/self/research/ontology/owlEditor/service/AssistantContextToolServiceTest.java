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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private AssistantAdmissionLimiter limiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        limiter = new AssistantAdmissionLimiter(4, 8, 32, 30, 1, Clock.systemUTC());
        toolService = new AssistantContextToolService(sessionService, datasetService, storageManager,
                new ProjectWriteLockRegistry(), limiter);
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(true);
        when(sessionService.isRevisionStale(any())).thenReturn(false);
    }

    @Test
    void returnsRevisionStaleWhenProjectHasMovedOnAndNeverSpendsBudget() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.isRevisionStale(any())).thenReturn(true);

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertFalse(result.isOk());
        assertEquals("REVISION_STALE", result.getErrorCode());
        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never()).tryConsumeRetrievalAttempt(anyString());
    }

    @Test
    void revisionThatMovesDuringTheReadDiscardsTheResultInsteadOfMislabelingIt() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(sessionService.isRevisionStale(any())).thenReturn(false, true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("p", "o"),
                        List.of(Map.of("p", "rdf:type", "o", "owl:Class")), false, null));

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertFalse(result.isOk());
        assertEquals("REVISION_STALE", result.getErrorCode());
        org.mockito.Mockito.verify(datasetService).execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong());
        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never()).tryConsumeTokenBudget(anyString(), anyInt());
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

    @Test
    void rejectsWithRateLimitedBeforeSpendingAnyRetrievalAttemptWhenUserIsAtCapacity() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        List<AssistantAdmissionLimiter.ToolAdmission> held = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            held.add(limiter.tryAcquireTool("u@x.com", "proj-other-" + i));
        }

        ContextToolResult result = toolService.readContext(
                "s1", "u@x.com", List.of(new Target("identifier", "http://ex.org/A")), "definitions");

        assertFalse(result.isOk());
        assertEquals("RATE_LIMITED", result.getErrorCode());
        assertEquals(1, result.getRetryAfterSeconds());
        org.mockito.Mockito.verify(sessionService, org.mockito.Mockito.never()).tryConsumeRetrievalAttempt(anyString());
        held.forEach(h -> ((AssistantAdmissionLimiter.Admitted) h).close());
        assertEquals(0, limiter.inFlightForUser("u@x.com"));
    }

    @Test
    void releasesItsSlotAfterSuccessAndAfterEveryFailurePath() throws Exception {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true, false, true);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new CappedSparqlResult(List.of("p", "o"),
                        List.of(Map.of("p", "rdf:type", "o", "owl:Class")), false, null));
        when(storageManager.readCodeViewPage(anyString(), anyString(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("disk gone"));

        assertTrue(toolService.readContext("s1", "u@x.com",
                List.of(new Target("identifier", "http://ex.org/A")), "definitions").isOk());
        assertEquals("BUDGET_EXHAUSTED", toolService.readContext("s1", "u@x.com",
                List.of(new Target("identifier", "http://ex.org/A")), "definitions").getErrorCode());
        toolService.readContext("s1", "u@x.com", List.of(new Target("range", "turtle:0-10")), "definitions");

        assertEquals(0, limiter.inFlightForUser("u@x.com"));
        assertEquals(0, limiter.inFlightForProject("proj-1"));
        assertEquals(0, limiter.inFlightGlobal());
    }

    @Test
    void onlyFourConcurrentReadsPerUserAreAdmittedAndTheRestAreRateLimited() throws Exception {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return new CappedSparqlResult(List.of("p"), List.of(Map.of("p", "x")), false, null);
                });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ContextToolResult>> running = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                running.add(pool.submit(() -> toolService.readContext("s1", "u@x.com",
                        List.of(new Target("identifier", "http://ex.org/A")), "definitions")));
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            ContextToolResult rejected = toolService.readContext("s1", "u@x.com",
                    List.of(new Target("identifier", "http://ex.org/A")), "definitions");
            assertEquals("RATE_LIMITED", rejected.getErrorCode());

            release.countDown();
            for (Future<ContextToolResult> future : running) {
                assertTrue(future.get(10, TimeUnit.SECONDS).isOk());
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(0, limiter.inFlightForUser("u@x.com"));
        assertTrue(toolService.readContext("s1", "u@x.com",
                List.of(new Target("identifier", "http://ex.org/A")), "definitions").isOk());
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
