package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;

import java.net.URI;

final class ProviderRequestShaper {

    private final int maxOutputTokens;

    ProviderRequestShaper(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    ObjectNode shape(String provider, String model, ObjectNode body, boolean stream) {
        body.remove("stream");
        body.remove("stream_options");
        switch (provider) {
            case AssistantProviderProxyService.CLAUDE -> {
                body.put("model", model);
                body.put("max_tokens", cappedTokens(body.get("max_tokens")));
                if (stream) {
                    body.put("stream", true);
                }
            }
            case AssistantProviderProxyService.OPENAI -> shapeOpenAi(model, body, stream);
            default -> shapeGemini(body);
        }
        return body;
    }

    private void shapeOpenAi(String model, ObjectNode body, boolean stream) {
        body.put("model", model);
        body.remove("n");
        boolean hasLegacy = body.has("max_tokens");
        boolean hasCompletion = body.has("max_completion_tokens");
        if (hasLegacy) {
            body.put("max_tokens", cappedTokens(body.get("max_tokens")));
        }
        if (hasCompletion || !hasLegacy) {
            body.put("max_completion_tokens", cappedTokens(body.get("max_completion_tokens")));
        }
        if (stream) {
            body.put("stream", true);
            body.putObject("stream_options").put("include_usage", true);
        }
    }

    private void shapeGemini(ObjectNode body) {
        body.remove("model");
        JsonNode existing = body.get("generationConfig");
        ObjectNode generationConfig = existing != null && existing.isObject()
                ? (ObjectNode) existing
                : body.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", cappedTokens(generationConfig.get("maxOutputTokens")));
        generationConfig.remove("candidateCount");
    }

    int cappedTokens(JsonNode requested) {
        int cap = Math.max(1, maxOutputTokens);
        if (requested == null || !requested.canConvertToInt() || !requested.isNumber()) {
            return cap;
        }
        int value = requested.asInt();
        return value < 1 ? cap : Math.min(value, cap);
    }

    static URI uri(String provider, String model, String baseUrlOverride, boolean stream) {
        String base = baseUrlOverride == null ? "" : baseUrlOverride.trim();
        String gemini = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
        if (!base.isEmpty()) {
            String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            return switch (provider) {
                case AssistantProviderProxyService.CLAUDE -> URI.create(root + "/v1/messages");
                case AssistantProviderProxyService.OPENAI -> URI.create(root + "/v1/chat/completions");
                default -> URI.create(root + "/v1beta/models/" + model + gemini);
            };
        }
        return switch (provider) {
            case AssistantProviderProxyService.CLAUDE -> URI.create(AssistantProviderProxyService.CLAUDE_URL);
            case AssistantProviderProxyService.OPENAI -> URI.create(AssistantProviderProxyService.OPENAI_URL);
            default -> URI.create(AssistantProviderProxyService.GEMINI_URL_PREFIX + model + gemini);
        };
    }

    static void applyAuthHeaders(String provider, String key, HttpHeaders headers) {
        switch (provider) {
            case AssistantProviderProxyService.CLAUDE -> {
                headers.set("x-api-key", key);
                headers.set("anthropic-version", "2023-06-01");
                headers.set("anthropic-beta", "prompt-caching-2024-07-31");
            }
            case AssistantProviderProxyService.OPENAI -> headers.setBearerAuth(key);
            default -> headers.set("x-goog-api-key", key);
        }
    }
}
