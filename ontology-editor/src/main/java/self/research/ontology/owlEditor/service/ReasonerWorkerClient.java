package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;

@Slf4j
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

    public Map<String, Object> submitJob(String jobType,
                                         String projectId,
                                         String expression,
                                         List<String> queryTypes,
                                         String reasonerType,
                                         String ownerEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobType", jobType);
        body.put("projectId", projectId);
        if (expression != null) {
            body.put("expression", expression);
        }
        if (queryTypes != null) {
            body.put("queryTypes", queryTypes);
        }
        if (reasonerType != null) {
            body.put("reasonerType", reasonerType);
        }
        if (ownerEmail != null) {
            body.put("ownerEmail", ownerEmail);
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    workerUrl + "/api/reasoning/jobs",
                    HttpMethod.POST,
                    new HttpEntity<>(body, internalHeaders()),
                    Map.class);
            Map<String, Object> result = response.getBody() != null ? response.getBody() : Map.of("success", false);

            log.info("[DIAG] submitJob: sentJobType={} projectId={} receivedJobId={} receivedJobType={} atMs={}",
                    jobType, projectId, result.get("jobId"), result.get("jobType"), System.currentTimeMillis());
            return result;
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Worker refused {} job for {}: too many queued jobs", jobType, projectId);
            return Map.of(
                    "success", false,
                    "rateLimited", true,
                    "error", queueFullMessage(e)
            );
        } catch (Exception e) {
            log.error("Failed to submit reasoning job to worker: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "error", ReasoningFriendlyErrors.forUser(e.getMessage())
            );
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
                    new HttpEntity<>(internalHeaders()),
                    Map.class);
            Map<String, Object> result = response.getBody();

            log.info("[DIAG] getJob: requestedJobId={} receivedJobType={} receivedStatus={} atMs={}",
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
                    new HttpEntity<>(internalHeaders()),
                    Map.class);
            Map<?, ?> body = response.getBody();
            boolean cancelled = body != null && Boolean.TRUE.equals(body.get("cancelled"));
            log.info("Cancel requested for worker job {}: cancelled={}", jobId, cancelled);
            return Map.of("success", true, "cancelled", cancelled);
        } catch (HttpClientErrorException.NotFound e) {
            return Map.of("success", true, "cancelled", false);
        } catch (Exception e) {
            log.warn("Failed to cancel worker job {}: {}", jobId, e.getMessage());
            return Map.of(
                    "success", false,
                    "cancelled", false,
                    "error", ReasoningFriendlyErrors.forUser(e.getMessage())
            );
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Ontocode-Internal-Token", internalToken);
        headers.set("Content-Type", "application/json");
        return headers;
    }
}
