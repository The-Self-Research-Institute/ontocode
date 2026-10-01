package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProviderResponseFilter {

    private static final Logger log = LoggerFactory.getLogger(ProviderResponseFilter.class);
    private static final int MAX_LOGGED_ERROR_CHARS = 500;
    private static final Pattern RETRY_IN = Pattern.compile("retry in\\s+([\\d.]+)\\s*s", Pattern.CASE_INSENSITIVE);

    private static final List<String> CLAUDE_TOP = List.of("type", "role", "content", "stop_reason", "stop_sequence");
    private static final List<String> CLAUDE_USAGE = List.of("input_tokens", "output_tokens",
            "cache_creation_input_tokens", "cache_read_input_tokens");
    private static final List<String> CLAUDE_EVENT = List.of("type", "index", "content_block", "delta");
    private static final List<String> CLAUDE_STOP = List.of("stop_reason", "stop_sequence");
    private static final List<String> OPENAI_CHOICE = List.of("index", "finish_reason");
    private static final List<String> OPENAI_MESSAGE = List.of("role", "content", "tool_calls", "refusal");
    private static final List<String> OPENAI_USAGE = List.of("prompt_tokens", "completion_tokens", "total_tokens");
    private static final List<String> GEMINI_CANDIDATE = List.of("content", "finishReason", "index");
    private static final List<String> GEMINI_USAGE = List.of("promptTokenCount", "candidatesTokenCount",
            "totalTokenCount", "cachedContentTokenCount");

    private final ObjectMapper objectMapper;

    ProviderResponseFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String filter(String provider, int status, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        ObjectNode filtered = status >= 200 && status < 300 ? success(provider, root) : error(provider, status, root);
        return objectMapper.writeValueAsString(filtered);
    }

    private ObjectNode success(String provider, JsonNode root) {
        return switch (provider) {
            case AssistantProviderProxyService.CLAUDE -> claude(root);
            case AssistantProviderProxyService.OPENAI -> openAi(root);
            default -> gemini(root);
        };
    }

    private ObjectNode claude(JsonNode root) {
        ObjectNode out = pick(root, CLAUDE_TOP);
        putPicked(out, "usage", root.path("usage"), CLAUDE_USAGE);
        return out;
    }

    private ObjectNode openAi(JsonNode root) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode choices = out.putArray("choices");
        for (JsonNode choice : root.path("choices")) {
            ObjectNode kept = pick(choice, OPENAI_CHOICE);
            putPicked(kept, "message", choice.path("message"), OPENAI_MESSAGE);
            choices.add(kept);
        }
        putOpenAiUsage(out, root);
        return out;
    }

    private void putOpenAiUsage(ObjectNode out, JsonNode root) {
        ObjectNode usage = putPicked(out, "usage", root.path("usage"), OPENAI_USAGE);
        JsonNode cached = root.path("usage").path("prompt_tokens_details").path("cached_tokens");
        if (usage != null && cached.isNumber()) {
            usage.putObject("prompt_tokens_details").set("cached_tokens", cached);
        }
    }

    private ObjectNode gemini(JsonNode root) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode candidates = out.putArray("candidates");
        for (JsonNode candidate : root.path("candidates")) {
            candidates.add(pick(candidate, GEMINI_CANDIDATE));
        }
        putPicked(out, "usageMetadata", root.path("usageMetadata"), GEMINI_USAGE);
        return out;
    }

    private ObjectNode error(String provider, int status, JsonNode root) {
        ObjectNode out = objectMapper.createObjectNode();
        out.putObject("error").put("message", logAndReduce(provider, status, root));
        return out;
    }

    String upstreamErrorMessage(String provider, int status, String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            root = objectMapper.createObjectNode();
        }
        return logAndReduce(provider, status, root);
    }

    private String logAndReduce(String provider, int status, JsonNode root) {
        String upstreamMessage = root.path("error").path("message").asText("");
        log.warn("[Assistant] Managed provider {} returned {}: {}", provider, status,
                upstreamMessage.length() > MAX_LOGGED_ERROR_CHARS
                        ? upstreamMessage.substring(0, MAX_LOGGED_ERROR_CHARS) : upstreamMessage);
        return clientMessage(status, upstreamMessage);
    }

    String filterStreamEvent(String provider, String data) throws IOException {
        if ("[DONE]".equals(data.trim())) {
            return "[DONE]";
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (IOException e) {
            return "{}";
        }
        if (root.has("error")) {
            return objectMapper.writeValueAsString(streamError(provider, root));
        }
        ObjectNode filtered = switch (provider) {
            case AssistantProviderProxyService.CLAUDE -> claudeEvent(root);
            case AssistantProviderProxyService.OPENAI -> openAiChunk(root);
            default -> gemini(root);
        };
        return objectMapper.writeValueAsString(filtered);
    }

    private ObjectNode streamError(String provider, JsonNode root) {
        ObjectNode out = objectMapper.createObjectNode();
        if (AssistantProviderProxyService.CLAUDE.equals(provider)) {
            out.put("type", "error");
        }
        out.putObject("error").put("message", logAndReduce(provider, 500, root));
        return out;
    }

    private ObjectNode claudeEvent(JsonNode root) {
        ObjectNode out = pick(root, CLAUDE_EVENT);
        String type = root.path("type").asText("");
        if ("message_start".equals(type)) {
            putPicked(out.putObject("message"), "usage", root.path("message").path("usage"), CLAUDE_USAGE);
        }
        if ("message_delta".equals(type)) {
            putPicked(out, "delta", root.path("delta"), CLAUDE_STOP);
            putPicked(out, "usage", root.path("usage"), CLAUDE_USAGE);
        }
        return out;
    }

    private ObjectNode openAiChunk(JsonNode root) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode choices = out.putArray("choices");
        for (JsonNode choice : root.path("choices")) {
            ObjectNode kept = pick(choice, OPENAI_CHOICE);
            putPicked(kept, "delta", choice.path("delta"), OPENAI_MESSAGE);
            choices.add(kept);
        }
        putOpenAiUsage(out, root);
        return out;
    }

    static String clientMessage(int status, String upstreamMessage) {
        if (status != 429) {
            return "The AI provider could not complete the request.";
        }
        Matcher retry = RETRY_IN.matcher(upstreamMessage == null ? "" : upstreamMessage);
        return retry.find()
                ? "Quota exceeded. Retry in " + (int) Math.ceil(Double.parseDouble(retry.group(1))) + "s."
                : "Quota exceeded.";
    }

    private ObjectNode pick(JsonNode source, List<String> fields) {
        ObjectNode out = objectMapper.createObjectNode();
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null) {
                out.set(field, value);
            }
        }
        return out;
    }

    private ObjectNode putPicked(ObjectNode target, String name, JsonNode source, List<String> fields) {
        if (!source.isObject()) {
            return null;
        }
        ObjectNode picked = pick(source, fields);
        target.set(name, picked);
        return picked;
    }
}
