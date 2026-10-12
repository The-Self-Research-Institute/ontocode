package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantProviderStreamTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ClientRequest> captured = new ArrayList<>();
    private AssistantProviderProxyService service;

    private record Frame(String kind, String data) {
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private AssistantProviderProxyService newService(String provider, ClientResponse response) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.add(request);
            return Mono.just(response);
        });
        service = new AssistantProviderProxyService(builder, objectMapper);
        ReflectionTestUtils.setField(service, "provider", provider);
        ReflectionTestUtils.setField(service, "model", "m-1");
        ReflectionTestUtils.setField(service, "apiKey", "server-key");
        ReflectionTestUtils.setField(service, "maxOutputTokens", 2048);
        ReflectionTestUtils.setField(service, "maxRequestBytes", 10_000);
        ReflectionTestUtils.setField(service, "maxResponseBytes", 100_000);
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 1000);
        ReflectionTestUtils.setField(service, "responseTimeoutSeconds", 2L);
        ReflectionTestUtils.setField(service, "requestsPerMinute", 3);
        ReflectionTestUtils.setField(service, "maxConcurrentCalls", 4);
        service.init();
        return service;
    }

    private static ClientResponse sse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body)
                .build();
    }

    private List<Frame> run(AssistantProviderProxyService proxy) throws Exception {
        List<Frame> frames = new ArrayList<>();
        proxy.stream(objectMapper.readTree("{\"messages\":[]}"), new AssistantProviderProxyService.StreamSink() {
            @Override
            public void event(String data) {
                frames.add(new Frame("event", data));
            }

            @Override
            public void complete(String json) {
                frames.add(new Frame("complete", json));
            }

            @Override
            public void error(int status, String errorCode, String message, Long retryAfterSeconds) {
                frames.add(new Frame("error", status + "|" + errorCode + "|" + message + "|" + retryAfterSeconds));
            }
        });
        return frames;
    }

    @Test
    void claudeEventsAreRelayedWithoutMessageMetadata() throws Exception {
        String upstream = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_9\",\"model\":\"x\","
                + "\"usage\":{\"input_tokens\":4}}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n";

        List<Frame> frames = run(newService("claude", sse(upstream)));

        assertEquals(2, frames.size());
        JsonNode start = objectMapper.readTree(frames.get(0).data());
        assertFalse(start.path("message").has("id"));
        assertEquals(4, start.path("message").path("usage").path("input_tokens").asInt());
        assertEquals("Hi", objectMapper.readTree(frames.get(1).data()).path("delta").path("text").asText());
        assertEquals("https://api.anthropic.com/v1/messages", captured.get(0).url().toString());
    }

    @Test
    void openAiChunksKeepDeltasAndTheDoneMarker() throws Exception {
        String upstream = "data: {\"id\":\"c1\",\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Yo\"}}]}\n\n"
                + "data: [DONE]\n\n";

        List<Frame> frames = run(newService("openai", sse(upstream)));

        JsonNode chunk = objectMapper.readTree(frames.get(0).data());
        assertFalse(chunk.has("id"));
        assertEquals("Yo", chunk.path("choices").path(0).path("delta").path("content").asText());
        assertEquals("[DONE]", frames.get(1).data());
    }

    @Test
    void geminiUsesItsStreamingEndpoint() throws Exception {
        List<Frame> frames = run(newService("gemini", sse("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n")));

        assertEquals(1, frames.size());
        assertTrue(captured.get(0).url().toString().endsWith("m-1:streamGenerateContent?alt=sse"));
    }

    @Test
    void aPlainJsonReplyFromUpstreamIsSentWholeAndFiltered() throws Exception {
        ClientResponse json = ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"whole\"}]}")
                .build();

        List<Frame> frames = run(newService("claude", json));

        assertEquals(1, frames.size());
        assertEquals("complete", frames.get(0).kind());
        JsonNode body = objectMapper.readTree(frames.get(0).data());
        assertFalse(body.has("id"));
        assertEquals("whole", body.path("content").path(0).path("text").asText());
    }

    @Test
    void upstreamQuotaErrorBecomesAGenericErrorFrameWithItsRetryDelay() throws Exception {
        ClientResponse quota = ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.RETRY_AFTER, "9")
                .body("{\"error\":{\"message\":\"org-7 over quota\"}}")
                .build();

        List<Frame> frames = run(newService("openai", quota));

        assertEquals(List.of(new Frame("error", "429|null|Quota exceeded.|9")), frames);
    }

    @Test
    void rejectedServerCredentialsBecomeProviderUnavailable() throws Exception {
        ClientResponse denied = ClientResponse.create(HttpStatus.UNAUTHORIZED).body("{}").build();

        List<Frame> frames = run(newService("claude", denied));

        assertTrue(frames.get(0).data().startsWith("503|PROVIDER_UNAVAILABLE|"));
    }
}
