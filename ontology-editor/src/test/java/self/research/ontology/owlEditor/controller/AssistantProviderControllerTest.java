package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.dto.AssistantUsageReport;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.AssistantProviderProxyService;
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.service.AssistantUsageMetricsService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantProviderControllerTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private AssistantSessionRepository sessionRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ClientRequest> upstreamCalls = new ArrayList<>();
    private AssistantProviderProxyService proxyService;
    private AssistantProviderController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(sessionService.getActiveSession(anyString(), anyString())).thenReturn(Optional.empty());
        when(sessionService.getActiveSession("sess-1", EMAIL)).thenReturn(Optional.of(activeSession()));
        when(sessionRepository.findByIdAndUserEmail(anyString(), anyString())).thenReturn(Optional.empty());
        when(sessionRepository.findByIdAndUserEmail("sess-1", EMAIL)).thenReturn(Optional.of(activeSession()));
        configure("claude", "claude-sonnet-4-5", "server-key", 10);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.invokeMethod(proxyService, "shutdown");
    }

    private void configure(String provider, String model, String key, int perMinute) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            upstreamCalls.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":3}}")
                    .build());
        });
        proxyService = new AssistantProviderProxyService(builder, objectMapper);
        ReflectionTestUtils.setField(proxyService, "provider", provider);
        ReflectionTestUtils.setField(proxyService, "model", model);
        ReflectionTestUtils.setField(proxyService, "apiKey", key);
        ReflectionTestUtils.setField(proxyService, "maxOutputTokens", 1024);
        ReflectionTestUtils.setField(proxyService, "maxRequestBytes", 4000);
        ReflectionTestUtils.setField(proxyService, "maxResponseBytes", 100_000);
        ReflectionTestUtils.setField(proxyService, "connectTimeoutMs", 1000);
        ReflectionTestUtils.setField(proxyService, "responseTimeoutSeconds", 2L);
        ReflectionTestUtils.setField(proxyService, "requestsPerMinute", perMinute);
        ReflectionTestUtils.setField(proxyService, "maxConcurrentCalls", 2);
        ReflectionTestUtils.invokeMethod(proxyService, "init");
        AssistantUsageMetricsService usageService =
                new AssistantUsageMetricsService(meterRegistry, sessionRepository, 100);
        controller = new AssistantProviderController(proxyService, sessionService, usageService, objectMapper);
    }

    private static AssistantSessionDocument activeSession() {
        return AssistantSessionDocument.builder()
                .id("sess-1")
                .projectId("proj-1")
                .userEmail(EMAIL)
                .status(AssistantSessionStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static MockHttpServletRequest authed(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"email\":\"" + EMAIL + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer header." + payload + ".sig");
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        return request;
    }

    private byte[] usageJson(AssistantUsageReport report) {
        try {
            return objectMapper.writeValueAsBytes(report);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletRequest authedUsage(AssistantUsageReport report) {
        MockHttpServletRequest request = authed(null);
        request.setContent(usageJson(report));
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return request;
    }

    private void useUpstream(WebClient.Builder builder) {
        ReflectionTestUtils.invokeMethod(proxyService, "shutdown");
        ReflectionTestUtils.setField(proxyService, "webClientBuilder", builder);
        ReflectionTestUtils.invokeMethod(proxyService, "init");
    }

    private void respondWith(HttpStatus status, String body, String retryAfter) {
        useUpstream(WebClient.builder().exchangeFunction(request -> {
            upstreamCalls.add(request);
            ClientResponse.Builder response = ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body);
            if (retryAfter != null) {
                response.header(HttpHeaders.RETRY_AFTER, retryAfter);
            }
            return Mono.just(response.build());
        }));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyMap(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void providerConfigReportsManagedModeWithoutKey() throws Exception {
        ResponseEntity<Map<String, Object>> response = controller.providerConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("managed"));
        assertEquals("claude", response.getBody().get("provider"));
        assertEquals("claude-sonnet-4-5", response.getBody().get("model"));
        assertFalse(objectMapper.writeValueAsString(response.getBody()).contains("server-key"));
    }

    @Test
    void providerConfigReportsUnmanagedWithOnlyTheFlag() {
        configure("claude", "claude-sonnet-4-5", "", 10);

        ResponseEntity<Map<String, Object>> response = controller.providerConfig();

        assertEquals(Map.of("managed", false), response.getBody());
    }

    @Test
    void providerCallRequiresJwt() {
        ResponseEntity<?> response = controller.providerCall("sess-1", new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(upstreamCalls.isEmpty());
    }

    @Test
    void providerCallRejectsWhenNotManaged() {
        configure("claude", "claude-sonnet-4-5", "", 10);

        ResponseEntity<?> response = controller.providerCall("sess-1", authed("{\"request\":{}}"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("PROVIDER_UNAVAILABLE", bodyMap(response).get("errorCode"));
    }

    @Test
    void providerCallRejectsSessionOwnedBySomeoneElseOrInactive() {
        ResponseEntity<?> response = controller.providerCall("sess-other", authed("{\"request\":{}}"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("SESSION_NOT_FOUND", bodyMap(response).get("errorCode"));
        assertTrue(upstreamCalls.isEmpty());
    }

    @Test
    void providerCallForwardsAndReturnsProviderJson() throws Exception {
        ResponseEntity<?> response = controller.providerCall("sess-1",
                authed("{\"request\":{\"max_tokens\":50,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}}"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        JsonNode body = objectMapper.readTree((String) response.getBody());
        assertEquals("msg_1", body.path("id").asText());
        assertEquals(1, upstreamCalls.size());
        assertEquals("server-key", upstreamCalls.get(0).headers().getFirst("x-api-key"));
    }

    @Test
    void providerCallRejectsMissingRequestField() {
        ResponseEntity<?> response = controller.providerCall("sess-1", authed("{\"messages\":[]}"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(upstreamCalls.isEmpty());
    }

    @Test
    void providerCallRejectsMalformedJson() {
        ResponseEntity<?> response = controller.providerCall("sess-1", authed("{\"request\":"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", bodyMap(response).get("errorCode"));
    }

    @Test
    void providerCallRejectsOversizedBodyWithoutForwarding() {
        String big = "a".repeat(10_000);

        ResponseEntity<?> response = controller.providerCall("sess-1",
                authed("{\"request\":{\"messages\":[{\"content\":\"" + big + "\"}]}}"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertTrue(upstreamCalls.isEmpty());
    }

    @Test
    void providerCallIsRateLimitedPerUserWithRetryAfter() {
        configure("claude", "claude-sonnet-4-5", "server-key", 2);

        controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));
        controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));
        ResponseEntity<?> limited = controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode());
        assertEquals("RATE_LIMITED", bodyMap(limited).get("errorCode"));
        long retryAfter = ((Number) bodyMap(limited).get("retryAfterSeconds")).longValue();
        assertTrue(retryAfter >= 1 && retryAfter <= 60);
        assertEquals(String.valueOf(retryAfter), limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(2, upstreamCalls.size());
    }

    @Test
    void readBoundedStopsAtTheLimit() throws Exception {
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(data, AssistantProviderController.readBounded(new ByteArrayInputStream(data), 10));
        assertNull(AssistantProviderController.readBounded(new ByteArrayInputStream(data), 9));
    }

    @Test
    void usageReportRequiresJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        request.setContent(usageJson(new AssistantUsageReport("claude", "claude-sonnet-4-5", 100L, 1L, 1L, null, null)));
        ResponseEntity<?> response = controller.reportUsage("sess-1", request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void usageReportRecordsMetricsAndReturnsNoContent() {
        ResponseEntity<?> response = controller.reportUsage("sess-1", authedUsage(
                new AssistantUsageReport("claude", "claude-sonnet-4-5-20250929", 1500L, 1200L, 300L, 800L, 50L)));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals(1, meterRegistry.get("assistant.provider.latency")
                .tags("provider", "claude", "model", "claude-sonnet-4-5").timer().count());
        assertEquals(1200.0, meterRegistry.get("assistant.provider.tokens")
                .tags("provider", "claude", "model", "claude-sonnet-4-5", "kind", "input").counter().count());
    }

    @Test
    void usageReportForSomeoneElsesSessionIsRejected() {
        ResponseEntity<?> response = controller.reportUsage("sess-other", authedUsage(
                new AssistantUsageReport("claude", "claude-sonnet-4-5", 100L, 1L, 1L, null, null)));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("SESSION_NOT_FOUND", bodyMap(response).get("errorCode"));
        assertTrue(meterRegistry.find("assistant.provider.latency").timers().isEmpty());
    }

    @Test
    void usageReportWithOutOfRangeNumbersIsRejected() {
        ResponseEntity<?> response = controller.reportUsage("sess-1", authedUsage(
                new AssistantUsageReport("claude", "claude-sonnet-4-5", -1L, 1L, 1L, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", bodyMap(response).get("errorCode"));
    }

    @Test
    void usageReportAcceptsFractionalLatencyAndUnknownFields() {
        MockHttpServletRequest request = authed("{\"provider\":\"claude\",\"model\":\"claude-sonnet-4-5\","
                + "\"latencyMs\":812.6,\"inputTokens\":10,\"futureField\":true}");

        ResponseEntity<?> response = controller.reportUsage("sess-1", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals(10.0, meterRegistry.get("assistant.provider.tokens")
                .tags("provider", "claude", "model", "claude-sonnet-4-5", "kind", "input").counter().count());
    }

    @Test
    void usageReportWithMalformedJsonIsAValidationErrorNotAServerError() {
        for (String body : List.of("{\"provider\":", "[1,2]", "\"text\"", "{\"latencyMs\":\"soon\"}",
                "{\"latencyMs\":99999999999999999999999}")) {
            ResponseEntity<?> response = controller.reportUsage("sess-1", authed(body));

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), body);
            assertEquals("VALIDATION_FAILED", bodyMap(response).get("errorCode"), body);
            assertEquals(false, bodyMap(response).get("ok"), body);
        }
        assertTrue(meterRegistry.find("assistant.provider.latency").timers().isEmpty());
    }

    @Test
    void usageReportWithoutABodyIsRejected() {
        ResponseEntity<?> response = controller.reportUsage("sess-1", authed(null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", bodyMap(response).get("errorCode"));
    }

    @Test
    void oversizedUsageReportIsRejectedWithoutRecording() {
        String padding = "x".repeat(AssistantProviderController.MAX_USAGE_BODY_BYTES);
        MockHttpServletRequest declared = authed("{\"provider\":\"claude\",\"model\":\"claude-sonnet-4-5\","
                + "\"latencyMs\":5,\"pad\":\"" + padding + "\"}");
        MockHttpServletRequest undeclared = new MockHttpServletRequest("POST", "/x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        undeclared.addHeader("Authorization", declared.getHeader("Authorization"));
        undeclared.setContent(declared.getContentAsByteArray());

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, controller.reportUsage("sess-1", declared).getStatusCode());
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, controller.reportUsage("sess-1", undeclared).getStatusCode());
        assertTrue(meterRegistry.find("assistant.provider.latency").timers().isEmpty());
    }

    @Test
    void upstreamServerErrorsBecomeProviderUnavailable() throws Exception {
        respondWith(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"secret detail\"}}", null);

        ResponseEntity<?> response = controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("PROVIDER_UNAVAILABLE", bodyMap(response).get("errorCode"));
        assertEquals(false, bodyMap(response).get("ok"));
        assertFalse(objectMapper.writeValueAsString(response.getBody()).contains("secret detail"));
        assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void upstreamOverloadedKeepsItsRetryAfterHint() {
        respondWith(HttpStatus.SERVICE_UNAVAILABLE, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}", "12");

        ResponseEntity<?> response = controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("PROVIDER_UNAVAILABLE", bodyMap(response).get("errorCode"));
        assertEquals("12", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void upstreamClientErrorsAndThrottlingPassThroughWithProviderJson() throws Exception {
        respondWith(HttpStatus.BAD_REQUEST, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}", null);

        ResponseEntity<?> bad = controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));

        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("invalid_request_error",
                objectMapper.readTree((String) bad.getBody()).path("error").path("type").asText());

        respondWith(HttpStatus.TOO_MANY_REQUESTS, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}", "4");

        ResponseEntity<?> throttled = controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, throttled.getStatusCode());
        assertEquals("4", throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void upstreamNetworkFailureBecomesProviderUnavailable() {
        useUpstream(WebClient.builder().exchangeFunction(request ->
                Mono.error(new java.net.ConnectException("refused"))));

        ResponseEntity<?> response = controller.providerCall("sess-1", authed("{\"request\":{\"messages\":[]}}"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("PROVIDER_UNAVAILABLE", bodyMap(response).get("errorCode"));
    }
}
