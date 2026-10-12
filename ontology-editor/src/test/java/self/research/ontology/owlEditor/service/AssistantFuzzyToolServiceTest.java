package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.service.AssistantFuzzyToolService.FuzzyToolResult;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantFuzzyToolServiceTest {

    private static final String DIABETIC = "http://example.org/onto#Diabetic";
    private static final String HEART_DISEASE = "http://example.org/onto#HeartDisease";

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private SparqlDatasetService datasetService;

    private AssistantFuzzyToolService toolService;
    private AssistantAdmissionLimiter limiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        limiter = new AssistantAdmissionLimiter(4, 2, 32, 30, 3, Clock.systemUTC());
        toolService = new AssistantFuzzyToolService(sessionService, limiter, datasetService);
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(true);
        when(sessionService.isRevisionStale(any())).thenReturn(false);
        when(sessionService.tryConsumeRetrievalAttempt(anyString())).thenReturn(true);
    }

    private void stubRows(List<Map<String, String>> rows) {
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(
                        List.of("entity", "class", "degree"), rows, false, null));
    }

    private Map<String, String> row(String entity, String classUri, double degree) {
        return Map.of("entity", entity, "class", classUri, "degree", String.valueOf(degree));
    }

    @Test
    void returnsSessionNotFoundWhenSessionMissing() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.empty());

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals WHERE memberOf(Diabetic) >= 0.8");

        assertFalse(result.isOk());
        assertEquals("SESSION_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void returnsRevisionStaleAndNeverSpendsBudget() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.isRevisionStale(any())).thenReturn(true);

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals WHERE memberOf(Diabetic) >= 0.8");

        assertFalse(result.isOk());
        assertEquals("REVISION_STALE", result.getErrorCode());
        verify(sessionService, never()).tryConsumeRetrievalAttempt(anyString());
    }

    @Test
    void projectCapacityRejectsWithRateLimited() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        AssistantAdmissionLimiter.ToolAdmission a = limiter.tryAcquireTool("other1@x.com", "proj-1");
        AssistantAdmissionLimiter.ToolAdmission b = limiter.tryAcquireTool("other2@x.com", "proj-1");

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals WHERE memberOf(Diabetic) >= 0.8");

        assertFalse(result.isOk());
        assertEquals("RATE_LIMITED", result.getErrorCode());
        ((AssistantAdmissionLimiter.Admitted) a).close();
        ((AssistantAdmissionLimiter.Admitted) b).close();
    }

    @Test
    void returnsBudgetExhaustedWhenRetrievalAttemptFails() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(false);

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals WHERE memberOf(Diabetic) >= 0.8");

        assertFalse(result.isOk());
        assertEquals("BUDGET_EXHAUSTED", result.getErrorCode());
    }

    @Test
    void rejectsQueryMissingLeadingKeyword() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "memberOf(Diabetic) >= 0.8");

        assertFalse(result.isOk());
        assertEquals("INVALID_QUERY", result.getErrorCode());
    }

    @Test
    void rejectsQueryMissingWhereClause() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals");

        assertFalse(result.isOk());
        assertEquals("INVALID_QUERY", result.getErrorCode());
    }

    @Test
    void rejectsExistsAsUnsupported() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com",
                "FIND individuals WHERE exists(hasSymptom, Fever)");

        assertFalse(result.isOk());
        assertEquals("UNSUPPORTED_QUERY", result.getErrorCode());
    }

    @Test
    void malformedThresholdIsRejectedInsteadOfCrashing() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        stubRows(List.of(row("ex:Alice", DIABETIC, 0.9)));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com",
                "FIND individuals WHERE memberOf(Diabetic) >= 1.2.3");

        assertFalse(result.isOk());
        assertEquals("INVALID_QUERY", result.getErrorCode());
    }

    @Test
    void malformedLimitIsRejectedInsteadOfCrashing() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        stubRows(List.of(row("ex:Alice", DIABETIC, 0.9)));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com",
                "FIND individuals WHERE memberOf(Diabetic) >= 0.5 LIMIT 99999999999999999999");

        assertFalse(result.isOk());
        assertEquals("INVALID_QUERY", result.getErrorCode());
    }

    @Test
    void resolvesShortNamesAndAppliesThreshold() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        stubRows(List.of(
                row("ex:Alice", DIABETIC, 0.9),
                row("ex:Bob", DIABETIC, 0.5),
                row("ex:Carol", DIABETIC, 0.85)
        ));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals WHERE memberOf(Diabetic) >= 0.8");

        assertTrue(result.isOk());
        List<?> individuals = (List<?>) result.getData().get("individuals");
        assertEquals(2, individuals.size());
        assertEquals(2, result.getData().get("count"));
    }

    @Test
    void classNameContainingOrderOrLimitAsASubstringIsNotTruncated() {
        String borderCase = "http://example.org/onto#BorderCase";
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        stubRows(List.of(row("ex:Alice", borderCase, 0.9)));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals WHERE memberOf(BorderCase) >= 0.8");

        assertTrue(result.isOk(), result.getMessage());
        List<?> individuals = (List<?>) result.getData().get("individuals");
        assertEquals(1, individuals.size());
    }

    @Test
    void evaluatesAndCombinatorAsProduct() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        stubRows(List.of(
                row("ex:Alice", DIABETIC, 0.9), row("ex:Alice", HEART_DISEASE, 0.3),
                row("ex:Bob", DIABETIC, 0.5), row("ex:Bob", HEART_DISEASE, 0.7),
                row("ex:Carol", DIABETIC, 0.85)
        ));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com",
                "FIND individuals WHERE memberOf(Diabetic AND HeartDisease) >= 0.3");

        assertTrue(result.isOk());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> individuals = (List<Map<String, Object>>) result.getData().get("individuals");
        assertEquals(1, individuals.size());
        assertEquals("ex:Bob", individuals.get(0).get("uri"));
    }

    @Test
    void evaluatesNotCombinatorAsOneMinusDegree() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        stubRows(List.of(
                row("ex:Alice", DIABETIC, 0.9),
                row("ex:Bob", DIABETIC, 0.5),
                row("ex:Carol", DIABETIC, 0.85)
        ));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com", "FIND individuals WHERE memberOf(NOT Diabetic) >= 0.5");

        assertTrue(result.isOk());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> individuals = (List<Map<String, Object>>) result.getData().get("individuals");
        assertEquals(1, individuals.size());
        assertEquals("ex:Bob", individuals.get(0).get("uri"));
    }

    @Test
    void appliesOrderByDescAndLimit() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        stubRows(List.of(
                row("ex:Alice", DIABETIC, 0.9),
                row("ex:Bob", DIABETIC, 0.5),
                row("ex:Carol", DIABETIC, 0.85)
        ));

        FuzzyToolResult result = toolService.runQuery("s1", "u@x.com",
                "FIND individuals WHERE memberOf(Diabetic) >= 0.5 ORDER BY degree DESC LIMIT 1");

        assertTrue(result.isOk());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> individuals = (List<Map<String, Object>>) result.getData().get("individuals");
        assertEquals(1, individuals.size());
        assertEquals("ex:Alice", individuals.get(0).get("uri"));
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
