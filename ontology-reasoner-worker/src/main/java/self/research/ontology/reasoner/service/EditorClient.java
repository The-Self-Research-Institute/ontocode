package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.Map;

@Slf4j
@Service
public class EditorClient {

    private final RestTemplate restTemplate;
    private final String editorUrl;
    private final String internalToken;

    public EditorClient(@Value("${ontocode.editor.url}") String editorUrl,
                        @Value("${ontocode.internal.token}") String internalToken) {
        this.editorUrl = editorUrl.endsWith("/") ? editorUrl.substring(0, editorUrl.length() - 1) : editorUrl;
        this.internalToken = internalToken;
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(7_200_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public long getTripleCount(String projectId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    editorUrl + "/internal/reasoning/" + projectId + "/triple-count",
                    HttpMethod.GET,
                    internalEntity(),
                    Map.class);
            if (response.getBody() != null && response.getBody().get("tripleCount") instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception e) {
            log.warn("Could not read triple count from editor for {}: {}", projectId, e.getMessage());
        }
        return -1;
    }

    public <T> T loadOntologyStream(String projectId, String userId, java.util.function.Function<InputStream, T> reader) {
        String url = editorUrl + "/internal/reasoning/" + projectId + "/export.nt";
        if (userId != null && !userId.isBlank()) {
            try {
                url += "?userId=" + java.net.URLEncoder.encode(userId, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        return restTemplate.execute(
                url,
                HttpMethod.GET,
                request -> request.getHeaders().addAll(internalHeaders()),
                response -> reader.apply(response.getBody()));
    }

    public long getRevision(String projectId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    editorUrl + "/internal/reasoning/" + projectId + "/revision",
                    HttpMethod.GET,
                    internalEntity(),
                    Map.class);
            if (response.getBody() != null && response.getBody().get("revision") instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception e) {
            log.warn("Could not read revision from editor for {}: {}", projectId, e.getMessage());
        }
        return -1;
    }

    private volatile int cachedActiveImports = 0;

    public int getActiveImportCount() {
        return cachedActiveImports;
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoning.active-imports-poll-ms:2000}")
    public void refreshActiveImportCount() {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    editorUrl + "/internal/import-queue/stats",
                    HttpMethod.GET,
                    internalEntity(),
                    Map.class);
            if (response.getBody() != null && response.getBody().get("activeImports") instanceof Number n) {
                cachedActiveImports = n.intValue();
            }
        } catch (Exception e) {
            log.debug("Import stats unavailable: {}", e.getMessage());
        }
    }

    public void publishJobEvent(ReasoningJobNotifier.JobSnapshot snapshot) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restTemplate.postForEntity(
                        editorUrl + "/internal/reasoning/job-events",
                        new HttpEntity<>(snapshot, internalHeaders()),
                        Void.class);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.warn("Failed to notify editor for job {} after {} attempts: {}",
                            snapshot.jobId(), maxAttempts, e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private HttpEntity<Void> internalEntity() {
        return new HttpEntity<>(internalHeaders());
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Ontocode-Internal-Token", internalToken);
        return headers;
    }
}
