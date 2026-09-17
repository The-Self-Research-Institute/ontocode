package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class IssueReportClient {

    private static final Logger log = LoggerFactory.getLogger(IssueReportClient.class);

    private final RestTemplate restTemplate;
    private final String editorServiceUrl;
    private final String internalToken;

    public IssueReportClient(@Value("${ONTOLOGY_EDITOR_URL:http://localhost:8083}") String editorServiceUrl,
                              @Value("${ontocode.internal.token:ontocode-internal}") String internalToken) {
        this.editorServiceUrl = editorServiceUrl.endsWith("/")
                ? editorServiceUrl.substring(0, editorServiceUrl.length() - 1)
                : editorServiceUrl;
        this.internalToken = internalToken;
        this.restTemplate = new RestTemplate();
    }

    public void deleteIssueReportsForUser(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            String url = editorServiceUrl + "/internal/users/" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "/issue-reports";
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Ontocode-Internal-Token", internalToken);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
            log.info("Deleted issue reports for {} during account deletion: {}", email, response.getBody());
        } catch (Exception e) {
            log.warn("Failed to delete issue reports for {} during account deletion (continuing): {}", email, e.getMessage());
        }
    }
}
