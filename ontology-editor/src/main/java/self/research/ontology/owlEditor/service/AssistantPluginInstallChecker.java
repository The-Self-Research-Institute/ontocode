package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class AssistantPluginInstallChecker {

    private final RestTemplate restTemplate;

    @Value("${ontology.plugin-service.url:http://localhost:8087}")
    private String pluginServiceUrl;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public AssistantPluginInstallChecker() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(5_000);
        this.restTemplate = new RestTemplate(f);
    }

    public boolean isInstalled(String pluginId, String authorizationHeader) {
        if (desktopMode) {
            return true;
        }
        HttpHeaders headers = new HttpHeaders();
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    pluginServiceUrl + "/api/plugins/" + pluginId + "/is-installed",
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<?, ?> body = response.getBody();
            return body != null && Boolean.TRUE.equals(body.get("installed"));
        } catch (RestClientException e) {
            log.warn("[Assistant] Could not check install status for plugin {} (treating as not installed): {}",
                    pluginId, e.getMessage());
            return false;
        }
    }
}
