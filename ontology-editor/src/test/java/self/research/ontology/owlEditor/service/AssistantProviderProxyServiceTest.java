package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantProviderProxyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AtomicReference<Function<ClientRequest, Mono<ClientResponse>>> responder;
    private List<ClientRequest> captured;
    private List<String> capturedBodies;
    private AssistantProviderProxyService service;

    @BeforeEach
    void setUp() {
        responder = new AtomicReference<>(req -> Mono.just(jsonResponse(HttpStatus.OK, "{\"id\":\"msg_1\"}")));
        captured = new ArrayList<>();
        capturedBodies = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private AssistantProviderProxyService newService(String provider, String model, String key) {
        ExchangeFunction exchangeFunction = request -> {
            captured.add(request);
            capturedBodies.add(bodyOf(request));
            return responder.get().apply(request);
        };
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        service = new AssistantProviderProxyService(builder, objectMapper);
        ReflectionTestUtils.setField(service, "provider", provider);
        ReflectionTestUtils.setField(service, "model", model);
        ReflectionTestUtils.setField(service, "apiKey", key);
        ReflectionTestUtils.setField(service, "maxOutputTokens", 2048);
        ReflectionTestUtils.setField(service, "maxRequestBytes", 10_000);
        ReflectionTestUtils.setField(service, "maxResponseBytes", 100_000);
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 1000);
        ReflectionTestUtils.setField(service, "responseTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(service, "requestsPerMinute", 3);
        ReflectionTestUtils.setField(service, "maxConcurrentCalls", 4);
        service.init();
        return service;
    }

    private static ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static String bodyOf(ClientRequest request) {
        MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
        BodyInserter<?, ? super ClientHttpRequest> inserter = request.body();
        inserter.insert(mock, new BodyInserter.Context() {
            @Override
            public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                return java.util.Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block(Duration.ofSeconds(5));
        return mock.getBodyAsString().block(Duration.ofSeconds(5));
    }

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    @Test
    void notManagedWhenAnyOfProviderModelOrKeyIsMissing() {
        assertFalse(newService("", "claude-sonnet-4-5", "k").isManaged());
        assertFalse(newService("claude", "", "k").isManaged());
        assertFalse(newService("claude", "claude-sonnet-4-5", "").isManaged());
        assertFalse(newService("mistral", "mistral-large", "k").isManaged());
        assertFalse(newService("gemini", "../evil", "k").isManaged());
        assertTrue(newService("Claude", "claude-sonnet-4-5", "k").isManaged());
    }

    @Test
    void configNeverExposesTheKey() throws Exception {
        AssistantProviderProxyService managed = newService("openai", "gpt-4o", "sk-secret-value");
        AssistantProviderProxyService.ProviderConfigView view = managed.config();

        assertTrue(view.managed());
        assertEquals("openai", view.provider());
        assertEquals("gpt-4o", view.model());
        assertFalse(objectMapper.writeValueAsString(view).contains("sk-secret-value"));

        AssistantProviderProxyService.ProviderConfigView off = newService("openai", "gpt-4o", "").config();
        assertFalse(off.managed());
        assertNull(off.provider());
        assertNull(off.model());
    }

    @Test
    void forwardRefusesWhenNotManagedWithoutCallingUpstream() throws Exception {
        AssistantProviderProxyService off = newService("claude", "claude-sonnet-4-5", "");

        AssistantProviderProxyService.ProviderCallResult result = off.forward(json("{\"messages\":[]}"));

        assertEquals(503, result.status());
        assertEquals("PROVIDER_UNAVAILABLE", result.errorCode());
        assertTrue(captured.isEmpty());
    }

    @Test
    void claudeCallForcesModelCapsTokensAndUsesServerHeaders() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        responder.set(req -> Mono.just(jsonResponse(HttpStatus.OK,
                "{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}")));

        AssistantProviderProxyService.ProviderCallResult result = claude.forward(json(
                "{\"model\":\"claude-opus-4\",\"max_tokens\":64000,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}"));

        assertFalse(result.isError());
        assertEquals(200, result.status());
        assertEquals("hi", json(result.body()).path("content").path(0).path("text").asText());

        ClientRequest sent = captured.get(0);
        assertEquals("https://api.anthropic.com/v1/messages", sent.url().toString());
        assertEquals("server-key", sent.headers().getFirst("x-api-key"));
        assertEquals("2023-06-01", sent.headers().getFirst("anthropic-version"));
        assertEquals("prompt-caching-2024-07-31", sent.headers().getFirst("anthropic-beta"));
        assertNull(sent.headers().getFirst(HttpHeaders.AUTHORIZATION));

        JsonNode sentBody = json(capturedBodies.get(0));
        assertEquals("claude-sonnet-4-5", sentBody.path("model").asText());
        assertEquals(2048, sentBody.path("max_tokens").asInt());
        assertFalse(sentBody.has("stream"));
        assertEquals("x", sentBody.path("messages").path(0).path("content").asText());
    }

    @Test
    void claudeKeepsSmallerRequestedMaxTokensAndFillsMissingOne() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");

        claude.forward(json("{\"max_tokens\":500,\"messages\":[]}"));
        claude.forward(json("{\"messages\":[]}"));
        claude.forward(json("{\"max_tokens\":\"lots\",\"messages\":[]}"));

        assertEquals(500, json(capturedBodies.get(0)).path("max_tokens").asInt());
        assertEquals(2048, json(capturedBodies.get(1)).path("max_tokens").asInt());
        assertEquals(2048, json(capturedBodies.get(2)).path("max_tokens").asInt());
    }

    @Test
    void openAiCallUsesBearerAuthAndCapsWhicheverTokenFieldIsSent() throws Exception {
        AssistantProviderProxyService openai = newService("openai", "gpt-4o", "sk-server");

        openai.forward(json("{\"model\":\"gpt-4\",\"max_tokens\":99999,\"n\":5,\"messages\":[]}"));
        openai.forward(json("{\"messages\":[]}"));

        ClientRequest sent = captured.get(0);
        assertEquals("https://api.openai.com/v1/chat/completions", sent.url().toString());
        assertEquals("Bearer sk-server", sent.headers().getFirst(HttpHeaders.AUTHORIZATION));
        JsonNode first = json(capturedBodies.get(0));
        assertEquals("gpt-4o", first.path("model").asText());
        assertEquals(2048, first.path("max_tokens").asInt());
        assertFalse(first.has("max_completion_tokens"));
        assertFalse(first.has("n"));
        JsonNode second = json(capturedBodies.get(1));
        assertEquals(2048, second.path("max_completion_tokens").asInt());
        assertFalse(second.has("max_tokens"));
    }

    @Test
    void geminiCallPutsModelInPathAndCapsMaxOutputTokens() throws Exception {
        AssistantProviderProxyService gemini = newService("gemini", "gemini-2.5-flash", "g-key");

        gemini.forward(json("{\"model\":\"x\",\"contents\":[],\"generationConfig\":{\"temperature\":0.2,\"maxOutputTokens\":100000}}"));

        ClientRequest sent = captured.get(0);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
                sent.url().toString());
        assertEquals("g-key", sent.headers().getFirst("x-goog-api-key"));
        assertFalse(sent.url().toString().contains("g-key"));
        JsonNode body = json(capturedBodies.get(0));
        assertFalse(body.has("model"));
        assertEquals(2048, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals(0.2, body.path("generationConfig").path("temperature").asDouble(), 1e-9);
    }

    @Test
    void providerErrorStatusAndJsonArePassedThrough() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        responder.set(req -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.RETRY_AFTER, "7")
                .body("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}")
                .build()));

        AssistantProviderProxyService.ProviderCallResult result = claude.forward(json("{\"messages\":[]}"));

        assertFalse(result.isError());
        assertEquals(429, result.status());
        assertEquals(7L, result.retryAfterSeconds());
        assertEquals("rate_limit_error", json(result.body()).path("error").path("type").asText());
    }

    @Test
    void rejectedServerCredentialsBecomeProviderUnavailable() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        responder.set(req -> Mono.just(jsonResponse(HttpStatus.UNAUTHORIZED,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}")));

        AssistantProviderProxyService.ProviderCallResult result = claude.forward(json("{\"messages\":[]}"));

        assertEquals(503, result.status());
        assertEquals("PROVIDER_UNAVAILABLE", result.errorCode());
        assertNull(result.body());
    }

    @Test
    void networkFailureTimeoutAndNonJsonBecomeProviderUnavailable() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");

        responder.set(req -> Mono.error(new java.net.ConnectException("refused")));
        assertEquals("PROVIDER_UNAVAILABLE", claude.forward(json("{\"messages\":[]}")).errorCode());

        responder.set(req -> Mono.error(new TimeoutException("slow")));
        assertEquals("PROVIDER_UNAVAILABLE", claude.forward(json("{\"messages\":[]}")).errorCode());

        responder.set(req -> Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<html>bad gateway</html>")
                .build()));
        AssistantProviderProxyService.ProviderCallResult html = claude.forward(json("{\"messages\":[]}"));
        assertEquals(503, html.status());
        assertEquals("PROVIDER_UNAVAILABLE", html.errorCode());
    }

    @Test
    void responseThatNeverArrivesTimesOut() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        ReflectionTestUtils.setField(claude, "responseTimeoutSeconds", 0L);
        responder.set(req -> Mono.never());

        long start = System.nanoTime();
        AssistantProviderProxyService.ProviderCallResult result = claude.forward(json("{\"messages\":[]}"));

        assertEquals("PROVIDER_UNAVAILABLE", result.errorCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 30);
    }

    @Test
    void oversizedRequestIsRejectedBeforeCallingUpstream() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        String big = "a".repeat(20_000);

        AssistantProviderProxyService.ProviderCallResult result = claude.forward(
                json("{\"messages\":[{\"role\":\"user\",\"content\":\"" + big + "\"}]}"));

        assertEquals(413, result.status());
        assertEquals("VALIDATION_FAILED", result.errorCode());
        assertTrue(captured.isEmpty());
    }

    @Test
    void nonObjectRequestIsRejected() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");

        AssistantProviderProxyService.ProviderCallResult result = claude.forward(json("[1,2]"));

        assertEquals(400, result.status());
        assertTrue(captured.isEmpty());
    }

    @Test
    void perUserRateLimitAllowsConfiguredCallsPerMinute() {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");

        assertTrue(claude.tryAcquireRate("a@x.com").allowed());
        assertTrue(claude.tryAcquireRate("A@x.com").allowed());
        assertTrue(claude.tryAcquireRate("a@x.com").allowed());
        AssistantProviderRateLimiter.Decision denied = claude.tryAcquireRate("a@x.com");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1);
        assertTrue(claude.tryAcquireRate("b@x.com").allowed());
    }

    @Test
    void concurrentCallLimitReturnsRateLimited() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        java.util.concurrent.Semaphore limit =
                (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(claude, "concurrencyLimit");
        limit.drainPermits();

        AssistantProviderProxyService.ProviderCallResult result = claude.forward(json("{\"messages\":[]}"));

        assertEquals(429, result.status());
        assertEquals("RATE_LIMITED", result.errorCode());
        assertTrue(captured.isEmpty());
    }

    @Test
    void permitIsReleasedAfterUpstreamFailure() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        responder.set(req -> Mono.error(new IllegalStateException("boom")));

        for (int i = 0; i < 6; i++) {
            claude.forward(json("{\"messages\":[]}"));
        }
        java.util.concurrent.Semaphore limit =
                (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(claude, "concurrencyLimit");
        assertEquals(4, limit.availablePermits());
    }

    @Test
    void emptyStreamingBodyIsNotAccepted() throws Exception {
        AssistantProviderProxyService claude = newService("claude", "claude-sonnet-4-5", "server-key");
        responder.set(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(Flux.<DataBuffer>empty())
                .build()));

        AssistantProviderProxyService.ProviderCallResult result = claude.forward(json("{\"messages\":[]}"));

        assertEquals("PROVIDER_UNAVAILABLE", result.errorCode());
    }
}
