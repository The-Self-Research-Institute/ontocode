package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SwrlRuleClient {

    private static final String TOKEN_HEADER = "X-Ontocode-Internal-Token";
    private static final String DEFAULT_INTERNAL_TOKEN = "ontocode-internal";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Answer(int status, String body) {
    }

    @FunctionalInterface
    public interface Transport {
        Answer send(String method, String url, String body, String internalToken) throws Exception;
    }

    public record Parsed(boolean valid, String axiom, List<String> errors, List<String> suggestions) {
    }

    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    private final Transport transport;
    private final String baseUrl;
    private final String internalToken;

    @Autowired
    public SwrlRuleClient(@Value("${ontocode.swrl.service.url:http://localhost:8084}") String baseUrl) {
        this(new JdkTransport(), baseUrl, System.getenv("ONTOCODE_INTERNAL_TOKEN"));
    }

    public SwrlRuleClient(Transport transport, String baseUrl, String internalToken) {
        this.transport = transport;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null || internalToken.isBlank() ? DEFAULT_INTERNAL_TOKEN : internalToken;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public List<Map<String, Object>> listEmbedded(String projectId, boolean draft, String userId) {
        StringBuilder url = new StringBuilder(baseUrl).append("/api/swrl/").append(encode(projectId))
                .append("/rules/embedded?draft=").append(draft);
        if (draft && userId != null && !userId.isBlank()) {
            url.append("&userId=").append(encode(userId));
        }
        Answer answer = call("GET", url.toString(), null);
        if (answer.status() != 200) {
            throw new UnavailableException("The rule list could not be read (HTTP " + answer.status() + ")");
        }
        try {
            JsonNode root = MAPPER.readTree(answer.body() == null ? "[]" : answer.body());
            JsonNode array = root.isArray() ? root : root.path("rules");
            List<Map<String, Object>> rules = new ArrayList<>();
            if (array.isArray()) {
                for (JsonNode item : array) {
                    rules.add(MAPPER.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    }));
                }
            }
            return rules;
        } catch (Exception e) {
            throw new UnavailableException("The rule list could not be read");
        }
    }

    public List<Map<String, Object>> listLegacy(String projectId) {
        String url = baseUrl + "/api/swrl/" + encode(projectId) + "/rules/legacy";
        Answer answer = call("GET", url, null);
        if (answer.status() != 200) {
            throw new UnavailableException("The older saved rules could not be read (HTTP " + answer.status() + ")");
        }
        try {
            JsonNode root = MAPPER.readTree(answer.body() == null ? "[]" : answer.body());
            JsonNode array = root.isArray() ? root : root.path("rules");
            List<Map<String, Object>> rows = new ArrayList<>();
            if (array.isArray()) {
                for (JsonNode item : array) {
                    rows.add(MAPPER.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    }));
                }
            }
            return rows;
        } catch (Exception e) {
            throw new UnavailableException("The older saved rules could not be read");
        }
    }

    public void markMigrated(String projectId, String legacyId) {
        String url = baseUrl + "/api/swrl/" + encode(projectId) + "/rules/legacy/" + encode(legacyId) + "/migrated";
        Answer answer = call("POST", url, null);
        if (answer.status() != 200 && answer.status() != 404) {
            throw new UnavailableException("The older saved rule could not be marked as moved (HTTP " + answer.status() + ")");
        }
    }

    public Parsed parse(String projectId, String ruleName, String ruleText) {
        String url = baseUrl + "/api/swrl/" + encode(projectId) + "/rules/parse";
        String body;
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("ruleName", ruleName);
            payload.put("ruleText", ruleText);
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Answer answer = call("POST", url, body);
        if (answer.status() != 200 && answer.status() != 400) {
            throw new UnavailableException("The rule could not be checked (HTTP " + answer.status() + ")");
        }
        try {
            JsonNode node = MAPPER.readTree(answer.body() == null ? "{}" : answer.body());
            boolean valid = node.path("valid").asBoolean(false);
            String axiom = node.path("axiom").isNull() ? null : node.path("axiom").asText(null);
            return new Parsed(valid, axiom, texts(node.path("errors")), texts(node.path("suggestions")));
        } catch (Exception e) {
            throw new UnavailableException("The rule could not be checked");
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item.isTextual()) {
                    out.add(item.asText());
                } else if (item.has("message")) {
                    out.add(item.path("message").asText());
                } else {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }

    private Answer call(String method, String url, String body) {
        if (baseUrl.isEmpty()) {
            throw new UnavailableException("The rule service is not configured");
        }
        try {
            return transport.send(method, url, body, internalToken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("The rule service call was interrupted");
        } catch (Exception e) {
            throw new UnavailableException("The rule service could not be reached");
        }
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

        @Override
        public Answer send(String method, String url, String body, String internalToken) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header(TOKEN_HEADER, internalToken);
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Answer(response.statusCode(), response.body());
        }
    }
}
