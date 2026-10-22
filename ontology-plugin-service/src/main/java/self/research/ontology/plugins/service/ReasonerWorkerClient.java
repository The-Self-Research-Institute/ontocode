package self.research.ontology.plugins.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import self.research.ontology.common.ReasoningFriendlyErrors;

import java.util.HashMap;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "ontocode.reasoner-worker.enabled", havingValue = "true")
public class ReasonerWorkerClient {

    private final org.springframework.web.client.RestTemplate restTemplate;
    private final org.springframework.web.client.RestTemplate shortRestTemplate;
    private final String workerUrl;
    private final String internalToken;

    public ReasonerWorkerClient(@Value("${ontocode.reasoner-worker.url}") String workerUrl,
                                @Value("${ontocode.internal.token:ontocode-internal}") String internalToken) {
        this.workerUrl = workerUrl.endsWith("/") ? workerUrl.substring(0, workerUrl.length() - 1) : workerUrl;
        this.internalToken = internalToken;
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new org.springframework.web.client.RestTemplate(factory);
        org.springframework.http.client.SimpleClientHttpRequestFactory shortFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        shortFactory.setConnectTimeout(2_000);
        shortFactory.setReadTimeout(5_000);
        this.shortRestTemplate = new org.springframework.web.client.RestTemplate(shortFactory);
    }

    public Map<String, Object> submit(String jobType, String projectId, String reasonerType) {
        return submit(jobType, projectId, reasonerType, null, null);
    }

    public Map<String, Object> submit(String jobType, String projectId, String reasonerType,
                                       String explanationMode, Integer maxJustifications) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobType", jobType);
        body.put("projectId", projectId);
        body.put("reasonerType", reasonerType);
        if (explanationMode != null) {
            body.put("explanationMode", explanationMode);
        }
        if (maxJustifications != null) {
            body.put("maxJustifications", maxJustifications);
        }
        return postJob(jobType, projectId, body);
    }

    public Map<String, Object> submitExplainEntailment(String projectId, String reasonerType, String ownerEmail,
                                                         Integer maxJustifications, String explanationMode,
                                                         String entailmentType, String subjectIri, String targetIri,
                                                         String propertyIri, String literalValue) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobType", "REASONER_EXPLAIN_ENTAILMENT");
        body.put("projectId", projectId);
        body.put("reasonerType", reasonerType);
        if (ownerEmail != null && !ownerEmail.isBlank()) {
            body.put("ownerEmail", ownerEmail);
        }
        if (maxJustifications != null) {
            body.put("maxJustifications", maxJustifications);
        }
        if (explanationMode != null) {
            body.put("explanationMode", explanationMode);
        }
        body.put("entailmentType", entailmentType);
        body.put("subjectIri", subjectIri);
        if (targetIri != null) {
            body.put("targetIri", targetIri);
        }
        if (propertyIri != null) {
            body.put("propertyIri", propertyIri);
        }
        if (literalValue != null) {
            body.put("literalValue", literalValue);
        }
        return postJob("REASONER_EXPLAIN_ENTAILMENT", projectId, body);
    }

    private Map<String, Object> postJob(String jobType, String projectId, Map<String, Object> body) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    workerUrl + "/api/reasoning/jobs",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers()),
                    Map.class);
            Map<String, Object> result = response.getBody() != null ? response.getBody() : Map.of("success", false);

            org.slf4j.LoggerFactory.getLogger(ReasonerWorkerClient.class).info(
                    "[DIAG] submit: sentJobType={} projectId={} receivedJobId={} receivedJobType={} atMs={}",
                    jobType, projectId, result.get("jobId"), result.get("jobType"), System.currentTimeMillis());
            return result;
        } catch (HttpClientErrorException.TooManyRequests e) {
            return Map.of("success", false, "rateLimited", true, "error", queueFullMessage(e));
        } catch (Exception e) {
            return Map.of("success", false, "error", ReasoningFriendlyErrors.forUser(e.getMessage()));
        }
    }

    static String queueFullMessage(HttpClientErrorException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("error") instanceof String message && !message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
        }
        return "Too many reasoning jobs are already queued for this project. Please wait for them to finish and try again.";
    }

    public Map<String, Object> getJob(String jobId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    workerUrl + "/api/reasoning/jobs/" + jobId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    Map.class);
            Map<String, Object> result = response.getBody();

            org.slf4j.LoggerFactory.getLogger(ReasonerWorkerClient.class).info(
                    "[DIAG] getJob: requestedJobId={} receivedJobType={} receivedStatus={} atMs={}",
                    jobId, result != null ? result.get("jobType") : "NULL_BODY",
                    result != null ? result.get("status") : "NULL_BODY", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", ReasoningFriendlyErrors.forUser(e.getMessage()));
        }
    }

    public Map<String, Object> cancelJob(String jobId) {
        try {
            ResponseEntity<Map> response = shortRestTemplate.exchange(
                    workerUrl + "/api/reasoning/jobs/" + jobId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers()),
                    Map.class);
            Map<?, ?> body = response.getBody();
            return Map.of("success", true, "cancelled", body != null && Boolean.TRUE.equals(body.get("cancelled")));
        } catch (HttpClientErrorException.NotFound e) {
            return Map.of("success", true, "cancelled", false);
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "cancelled", false,
                    "error", ReasoningFriendlyErrors.forUser(e.getMessage()));
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Ontocode-Internal-Token", internalToken);
        headers.set("Content-Type", "application/json");
        return headers;
    }
}
