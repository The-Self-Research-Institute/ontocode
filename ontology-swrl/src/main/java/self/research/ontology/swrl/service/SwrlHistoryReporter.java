package self.research.ontology.swrl.service;

import jakarta.servlet.ServletException;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.swrl.model.SwrlRule;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class SwrlHistoryReporter {

    private static final Logger logger = LoggerFactory.getLogger(SwrlHistoryReporter.class);
    private static final JsonWriterSettings EXACT = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private static final Set<String> VOLATILE = Set.of("executionCount", "lastExecutionTime", "lastExecutedAt");

    private final SwrlRuleRepository repository;
    private final MongoTemplate mongoTemplate;
    private final String editorServiceUrl;
    private final RestTemplate restTemplate;

    @Autowired
    public SwrlHistoryReporter(SwrlRuleRepository repository, MongoTemplate mongoTemplate,
                               @Value("${ontology.editor.service.url}") String editorServiceUrl) {
        this(repository, mongoTemplate, editorServiceUrl, newRestTemplate());
    }

    SwrlHistoryReporter(SwrlRuleRepository repository, MongoTemplate mongoTemplate, String editorServiceUrl,
                        RestTemplate restTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.editorServiceUrl = editorServiceUrl;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate newRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(8_000);
        return new RestTemplate(factory);
    }

    @FunctionalInterface
    public interface Request {
        boolean run() throws ServletException, IOException;
    }

    public void aroundRequest(String projectId, String authorization, Request request) throws ServletException, IOException {
        Map<String, Document> before = snapshots(projectId);
        if (!request.run()) {
            return;
        }
        try {
            report(projectId, authorization, before, snapshots(projectId));
        } catch (RuntimeException e) {
            logger.warn("Could not report SWRL rule changes of project {} to the editor history: {}", projectId, e.getMessage());
        }
    }

    public <T> T around(String projectId, String authorization, Supplier<T> action) {
        Map<String, Document> before = snapshots(projectId);
        T result = action.get();
        try {
            report(projectId, authorization, before, snapshots(projectId));
        } catch (RuntimeException e) {
            logger.warn("Could not report SWRL rule changes of project {} to the editor history: {}", projectId, e.getMessage());
        }
        return result;
    }

    private Map<String, Document> snapshots(String projectId) {
        Map<String, Document> byId = new LinkedHashMap<>();
        for (SwrlRule rule : repository.findByProjectId(projectId)) {
            Document document = new Document();
            mongoTemplate.getConverter().write(rule, document);
            byId.put(String.valueOf(document.get("_id")), document);
        }
        return byId;
    }

    private void report(String projectId, String authorization, Map<String, Document> before, Map<String, Document> after) {
        for (Map.Entry<String, Document> entry : after.entrySet()) {
            Document previous = before.get(entry.getKey());
            if (previous == null) {
                send(projectId, authorization, "createSwrlRule", null, entry.getValue());
            } else if (!stable(previous).equals(stable(entry.getValue()))) {
                send(projectId, authorization, "updateSwrlRule", previous, entry.getValue());
            }
        }
        for (Map.Entry<String, Document> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                send(projectId, authorization, "deleteSwrlRule", entry.getValue(), null);
            }
        }
    }

    private static Document stable(Document document) {
        Document copy = new Document(document);
        VOLATILE.forEach(copy::remove);
        return copy;
    }

    private void send(String projectId, String authorization, String operation, Document before, Document after) {
        Document subject = after != null ? after : before;
        String name = String.valueOf(subject.get("ruleName"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("store", "swrlRule");
        body.put("operationType", operation);
        body.put("label", name);
        body.put("oldText", before == null ? null : before.getString("ruleText"));
        body.put("newText", after == null ? null : after.getString("ruleText"));
        body.put("description", describe(operation, name));
        body.put("before", before == null ? null : before.toJson(EXACT));
        body.put("after", after == null ? null : after.toJson(EXACT));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        try {
            restTemplate.postForEntity(editorServiceUrl + "/api/ontology/" + projectId + "/changes/document",
                    new HttpEntity<>(body, headers), String.class);
        } catch (RuntimeException e) {
            logger.warn("The editor did not record SWRL rule {} ({}): {}", name, operation, e.getMessage());
        }
    }

    private static String describe(String operation, String name) {
        return switch (operation) {
            case "createSwrlRule" -> "Created SWRL rule: " + name;
            case "deleteSwrlRule" -> "Deleted SWRL rule: " + name;
            default -> "Updated SWRL rule: " + name;
        };
    }
}
