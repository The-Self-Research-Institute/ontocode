package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderResponseFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderResponseFilter filter = new ProviderResponseFilter(mapper);

    private JsonNode run(String provider, int status, String body) throws Exception {
        return mapper.readTree(filter.filter(provider, status, body));
    }

    @Test
    void claudeKeepsContentBlocksWholeAndDropsMetadata() throws Exception {
        JsonNode out = run("claude", 200, "{\"id\":\"msg_1\",\"model\":\"claude-x\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"sig\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"read_context\",\"input\":{\"a\":1}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"service_tier\":\"x\"}}");

        assertFalse(out.has("id"));
        assertFalse(out.has("model"));
        assertEquals("sig", out.path("content").path(0).path("signature").asText());
        assertEquals("tu1", out.path("content").path(1).path("id").asText());
        assertEquals("tool_use", out.path("stop_reason").asText());
        assertEquals(10, out.path("usage").path("input_tokens").asInt());
        assertFalse(out.path("usage").has("service_tier"));
    }

    @Test
    void openAiKeepsMessageAndToolCallsAndCachedTokens() throws Exception {
        JsonNode out = run("openai", 200, "{\"id\":\"c1\",\"system_fingerprint\":\"fp\",\"choices\":[{\"index\":0,"
                + "\"finish_reason\":\"tool_calls\",\"logprobs\":null,\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"run_sparql\",\"arguments\":\"{}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":4,\"audio_tokens\":0}}}");

        assertFalse(out.has("id"));
        assertFalse(out.has("system_fingerprint"));
        assertFalse(out.path("choices").path(0).has("logprobs"));
        assertEquals("call1", out.path("choices").path(0).path("message").path("tool_calls").path(0).path("id").asText());
        assertEquals(4, out.path("usage").path("prompt_tokens_details").path("cached_tokens").asInt());
        assertFalse(out.path("usage").path("prompt_tokens_details").has("audio_tokens"));
    }

    @Test
    void geminiKeepsPartsIncludingThoughtSignatures() throws Exception {
        JsonNode out = run("gemini", 200, "{\"responseId\":\"r1\",\"modelVersion\":\"g\",\"candidates\":[{\"index\":0,"
                + "\"finishReason\":\"STOP\",\"safetyRatings\":[],\"content\":{\"role\":\"model\",\"parts\":["
                + "{\"functionCall\":{\"name\":\"read_context\",\"args\":{}},\"thoughtSignature\":\"ts\"}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":2,\"totalTokenCount\":11}}");

        assertFalse(out.has("responseId"));
        assertFalse(out.path("candidates").path(0).has("safetyRatings"));
        assertEquals("ts", out.path("candidates").path(0).path("content").path("parts").path(0).path("thoughtSignature").asText());
        assertEquals(11, out.path("usageMetadata").path("totalTokenCount").asInt());
    }

    @Test
    void errorsKeepOnlyAGenericMessage() throws Exception {
        JsonNode out = run("openai", 400, "{\"error\":{\"message\":\"Invalid key sk-abc for org-9\",\"type\":\"invalid_request_error\"}}");

        assertEquals("The AI provider could not complete the request.", out.path("error").path("message").asText());
        assertFalse(out.toString().contains("org-9"));
        assertFalse(out.path("error").has("type"));
    }

    @Test
    void quotaErrorsKeepOnlyTheRetryDelay() throws Exception {
        JsonNode out = run("gemini", 429, "{\"error\":{\"message\":\"Quota exceeded for project 12345. Please retry in 3.2s.\"}}");

        assertEquals("Quota exceeded. Retry in 4s.", out.path("error").path("message").asText());
        assertTrue(ProviderResponseFilter.clientMessage(429, null).startsWith("Quota exceeded"));
    }
}
