package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.service.AssistantSwrlToolService.SwrlToolResult;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantSwrlToolServiceTest {

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private AssistantPluginInstallChecker pluginInstallChecker;

    @Mock
    private RestTemplate restTemplate;

    private AssistantSwrlToolService toolService;
    private AssistantAdmissionLimiter limiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        limiter = new AssistantAdmissionLimiter(4, 2, 32, 30, 3, Clock.systemUTC());
        toolService = new AssistantSwrlToolService(sessionService, limiter, pluginInstallChecker);
        ReflectionTestUtils.setField(toolService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(toolService, "swrlServiceUrl", "http://127.0.0.1:18084");
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(true);
        when(sessionService.isRevisionStale(any())).thenReturn(false);
        when(pluginInstallChecker.checkInstalled(anyString(), any()))
                .thenReturn(AssistantPluginInstallChecker.InstallStatus.INSTALLED);
    }

    @SuppressWarnings("unchecked")
    private void mockExchange(ResponseEntity<Map> response) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);
    }

    @Test
    void returnsSessionNotFoundWhenSessionMissing() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.empty());

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("SESSION_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void returnsRevisionStaleAndNeverSpendsBudget() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.isRevisionStale(any())).thenReturn(true);

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("REVISION_STALE", result.getErrorCode());
        verify(sessionService, never()).tryConsumeRetrievalAttempt(anyString());
    }

    @Test
    void projectCapacityRejectsWithRateLimited() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        AssistantAdmissionLimiter.ToolAdmission a = limiter.tryAcquireTool("other1@x.com", "proj-1");
        AssistantAdmissionLimiter.ToolAdmission b = limiter.tryAcquireTool("other2@x.com", "proj-1");

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("RATE_LIMITED", result.getErrorCode());
        ((AssistantAdmissionLimiter.Admitted) a).close();
        ((AssistantAdmissionLimiter.Admitted) b).close();
    }

    @Test
    void returnsPluginNotInstalledBeforeSpendingARetrievalAttempt() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(pluginInstallChecker.checkInstalled(eq("swrl-editor-plugin"), any()))
                .thenReturn(AssistantPluginInstallChecker.InstallStatus.NOT_INSTALLED);

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("PLUGIN_NOT_INSTALLED", result.getErrorCode());
        verify(sessionService, never()).tryConsumeRetrievalAttempt(anyString());
    }

    @Test
    void returnsServiceCheckFailedWhenThePluginServiceCannotBeReachedRatherThanNotInstalled() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(pluginInstallChecker.checkInstalled(eq("swrl-editor-plugin"), any()))
                .thenReturn(AssistantPluginInstallChecker.InstallStatus.CHECK_FAILED);

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("SWRL_SERVICE_CHECK_FAILED", result.getErrorCode());
        verify(sessionService, never()).tryConsumeRetrievalAttempt(anyString());
    }

    @Test
    void returnsBudgetExhaustedWhenRetrievalAttemptFails() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(false);

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("BUDGET_EXHAUSTED", result.getErrorCode());
    }

    @Test
    void runRuleHappyPathForwardsAuthorizationAndReturnsData() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("inferredAxiomsCount", 2);
        mockExchange(new ResponseEntity<>(body, HttpStatus.OK));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer abc123");

        assertTrue(result.isOk());
        assertEquals(2, result.getData().get("inferredAxiomsCount"));
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        assertEquals("http://127.0.0.1:18084/api/swrl/proj-1/execute", urlCaptor.getValue());
        assertEquals("Bearer abc123", entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void addRuleRejectsNullRuleTextWithoutCallingSwrlOrSpendingBudget() {
        SwrlToolResult result = toolService.addRule("s1", "u@x.com", "Bearer t", "rule1", null);

        assertFalse(result.isOk());
        assertEquals("INVALID_RULE", result.getErrorCode());
        verify(sessionService, never()).getActiveSession(anyString(), anyString());
        verify(restTemplate, never()).exchange(anyString(), any(), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    void addRuleValidatesBeforeCreating() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);

        Map<String, Object> validateBody = new HashMap<>();
        validateBody.put("valid", true);
        Map<String, Object> createBody = new HashMap<>();
        createBody.put("ruleName", "rule1");

        when(restTemplate.exchange(eq("http://127.0.0.1:18084/api/swrl/proj-1/validate"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(validateBody, HttpStatus.OK));
        when(restTemplate.exchange(eq("http://127.0.0.1:18084/api/swrl/proj-1/rules"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(createBody, HttpStatus.CREATED));

        SwrlToolResult result = toolService.addRule("s1", "u@x.com", "Bearer t", "rule1", "Person(?p) -> Human(?p)");

        assertTrue(result.isOk());
        assertEquals("rule1", result.getData().get("ruleName"));
    }

    @Test
    void addRuleStopsAtInvalidRuleWithoutCallingCreate() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);

        Map<String, Object> validateBody = new HashMap<>();
        validateBody.put("valid", false);
        validateBody.put("errorMessage", "Unknown predicate 'Foo'");
        mockExchange(new ResponseEntity<>(validateBody, HttpStatus.OK));

        SwrlToolResult result = toolService.addRule("s1", "u@x.com", "Bearer t", "rule1", "Foo(?p) -> Human(?p)");

        assertFalse(result.isOk());
        assertEquals("INVALID_RULE", result.getErrorCode());
        assertTrue(result.getMessage().contains("Unknown predicate"));
        verify(restTemplate, never()).exchange(eq("http://127.0.0.1:18084/api/swrl/proj-1/rules"), any(), any(), eq(Map.class));
    }

    @Test
    void timeoutProducesSwrlUnavailableAndStillConsumesRetrievalAttemptButNotTokenBudget() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        SwrlToolResult result = toolService.runRule("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("SWRL_UNAVAILABLE", result.getErrorCode());
        verify(sessionService).tryConsumeRetrievalAttempt("s1");
        verify(sessionService, never()).tryConsumeTokenBudget(anyString(), anyInt());
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
