package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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

    public InputStream openOntologyStream(String projectId, String userId) {
        // N-Triples: no prefix resolution — OWLAPI parses large ontologies ~30% faster than Turtle
        String url = editorUrl + "/internal/reasoning/" + projectId + "/export.nt";
        if (userId != null && !userId.isBlank()) {
            try {
                url += "?userId=" + java.net.URLEncoder.encode(userId, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        ResponseEntity<org.springframework.core.io.Resource> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                internalEntity(),
                org.springframework.core.io.Resource.class);
        try {
            return response.getBody().getInputStream();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read ontology export stream: " + e.getMessage(), e);
        }
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

    public int getActiveImportCount() {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    editorUrl + "/internal/import-queue/stats",
                    HttpMethod.GET,
                    internalEntity(),
                    Map.class);
            if (response.getBody() != null && response.getBody().get("activeImports") instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception e) {
            log.debug("Import stats unavailable: {}", e.getMessage());
        }
        return 0;
    }

    public void publishJobEvent(ReasoningJobNotifier.JobSnapshot snapshot) {
        try {
            restTemplate.postForEntity(
                    editorUrl + "/internal/reasoning/job-events",
                    new HttpEntity<>(snapshot, internalHeaders()),
                    Void.class);
        } catch (Exception e) {
            log.warn("Failed to notify editor for job {}: {}", snapshot.jobId(), e.getMessage());
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
