package self.research.ontology.plugins.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningFriendlyErrors;

import java.util.HashMap;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "ontocode.reasoner-worker.enabled", havingValue = "true")
public class ReasonerWorkerClient {

    private final org.springframework.web.client.RestTemplate restTemplate;
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
    }

    public Map<String, Object> submit(String jobType, String projectId, String reasonerType) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobType", jobType);
        body.put("projectId", projectId);
        body.put("reasonerType", reasonerType);
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
        } catch (Exception e) {
            return Map.of("success", false, "error", ReasoningFriendlyErrors.forUser(e.getMessage()));
        }
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

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Ontocode-Internal-Token", internalToken);
        headers.set("Content-Type", "application/json");
        return headers;
    }
}
