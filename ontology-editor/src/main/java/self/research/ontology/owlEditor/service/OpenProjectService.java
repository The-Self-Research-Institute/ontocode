package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class OpenProjectService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final OpenProjectLookupResolver resolver = new OpenProjectLookupResolver(this);

    @Value("${openproject.enabled:false}")
    private boolean enabled;

    @Value("${openproject.host:}")
    private String host;

    @Value("${openproject.api.key:}")
    private String apiKey;

    @Value("${openproject.project.id:}")
    String projectId;

    @Value("${openproject.parent.id:}")
    String configuredParentId;

    @Value("${openproject.status:}")
    String configuredStatus;

    @Value("${openproject.assignee.id:}")
    private String assigneeId;

    @Value("${openproject.default.type:Task}")
    String defaultType;

    @Value("${openproject.timeout.lookup-seconds:10}")
    private long lookupTimeoutSeconds;

    @Value("${openproject.timeout.create-seconds:30}")
    private long createTimeoutSeconds;

    @Value("${openproject.timeout.attachment-seconds:30}")
    private long attachmentTimeoutSeconds;

    @Value("${openproject.retry.max-attempts:2}")
    private long retryMaxAttempts;

    public OpenProjectService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled && !host.isEmpty() && !apiKey.isEmpty() && !projectId.isEmpty();
    }

    public OpenProjectValidationResult validateConnection() {
        if (!isEnabled()) {
            return OpenProjectValidationResult.builder()
                    .success(false)
                    .message("OpenProject integration is disabled or not configured")
                    .build();
        }
        try {
            JsonNode project = fetch(hostUrl() + "/api/v3/projects/" + encode(projectId));
            if (project == null) {
                return OpenProjectValidationResult.builder()
                        .success(false)
                        .message("Could not retrieve project information")
                        .build();
            }
            String projectName = project.path("name").asText();
            return OpenProjectValidationResult.builder()
                    .success(true)
                    .message("Successfully connected to OpenProject project: " + projectName)
                    .projectName(projectName)
                    .build();
        } catch (Exception e) {
            log.error("OpenProject validation failed", e);
            return OpenProjectValidationResult.builder()
                    .success(false)
                    .message(describeError(e))
                    .build();
        }
    }

    public OpenProjectIssueResult createIssue(String summary, String description, String priority, String issueType) {
        if (!isEnabled()) {
            throw new IllegalStateException("OpenProject integration is not enabled");
        }
        try {
            ObjectNode payload = buildIssuePayload(summary, description, priority, issueType);

            log.info("Creating OpenProject work package in project {} with type {}", projectId, issueType);
            log.debug("OpenProject work package payload: {}", payload.toPrettyString());

            JsonNode response = webClient.post()
                    .uri(hostUrl() + "/api/v3/projects/" + encode(projectId) + "/work_packages?notify=false")
                    .headers(this::setAuthHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(createTimeoutSeconds));

            if (response == null) {
                throw new RuntimeException("No response from OpenProject");
            }

            String workPackageId = response.get("id").asText();
            String workPackageUrl = hostUrl() + "/work_packages/" + workPackageId;

            log.info("Created OpenProject work package: {} ({})", workPackageId, workPackageUrl);

            return OpenProjectIssueResult.builder()
                    .success(true)
                    .issueKey(workPackageId)
                    .issueUrl(workPackageUrl)
                    .build();

        } catch (Exception e) {
            String errorMsg = describeError(e);
            log.error(errorMsg, e);
            return OpenProjectIssueResult.builder()
                    .success(false)
                    .errorMessage(errorMsg)
                    .build();
        }
    }

    private ObjectNode buildIssuePayload(String summary, String description, String priority, String issueType) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("subject", summary);

        ObjectNode descriptionNode = objectMapper.createObjectNode();
        descriptionNode.put("format", "markdown");
        descriptionNode.put("raw", description);
        payload.set("description", descriptionNode);

        ObjectNode links = objectMapper.createObjectNode();
        links.set("type", hrefLink(resolver.resolveTypeHref(issueType)));

        if (priority != null && !priority.isBlank()) {
            String priorityHref = resolver.resolvePriorityHref(priority);
            if (priorityHref != null) {
                links.set("priority", hrefLink(priorityHref));
            }
        }

        String parentId = resolver.resolveParentId();
        if (parentId != null) {
            links.set("parent", hrefLink("/api/v3/work_packages/" + parentId));
        }

        String statusHref = resolver.resolveStatusHref();
        if (statusHref != null) {
            links.set("status", hrefLink(statusHref));
        }

        if (assigneeId != null && !assigneeId.isBlank()) {
            links.set("assignee", hrefLink("/api/v3/users/" + assigneeId.trim()));
        }

        payload.set("_links", links);
        return payload;
    }

    private ObjectNode hrefLink(String href) {
        ObjectNode link = objectMapper.createObjectNode();
        link.put("href", href);
        return link;
    }

    public boolean uploadAttachment(String issueKey, String fileName, byte[] fileData) {
        if (!isEnabled()) {
            log.warn("OpenProject not enabled, skipping attachment upload");
            return false;
        }
        try {
            String metadata = objectMapper.writeValueAsString(Map.of("fileName", fileName));

            HttpHeaders metadataHeaders = new HttpHeaders();
            metadataHeaders.setContentType(MediaType.APPLICATION_JSON);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("metadata", new HttpEntity<>(metadata, metadataHeaders));
            body.add("file", new ByteArrayResource(fileData) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });

            webClient.post()
                    .uri(hostUrl() + "/api/v3/work_packages/" + issueKey + "/attachments")
                    .headers(this::setAuthHeaders)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(attachmentTimeoutSeconds));

            log.info("Uploaded attachment {} to work package {}", fileName, issueKey);
            return true;
        } catch (Exception e) {
            log.error("Failed to upload attachment {} to work package {}: {}", fileName, issueKey, e.getMessage(), e);
            return false;
        }
    }

    public static String determinePriority(String summary, String description) {
        String combined = (summary + " " + description).toLowerCase();
        if (combined.contains("crash") || combined.contains("data loss") || combined.contains("critical")) {
            return "Highest";
        } else if (combined.contains("error") || combined.contains("broken") || combined.contains("failure")) {
            return "High";
        } else if (combined.contains("slow") || combined.contains("performance")) {
            return "Medium";
        }
        return "Medium";
    }

    String hostUrl() {
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    private void setAuthHeaders(HttpHeaders headers) {
        String auth = "apikey:" + apiKey;
        headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
        headers.set("Accept", "application/json");
    }

    String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    JsonNode fetch(String url) {
        return webClient.get()
                .uri(url)
                .headers(this::setAuthHeaders)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(retryMaxAttempts, Duration.ofMillis(300)).filter(OpenProjectService::isTransient))
                .block(Duration.ofSeconds(lookupTimeoutSeconds));
    }

    private static boolean isTransient(Throwable e) {
        if (e instanceof WebClientResponseException webEx) {
            int status = webEx.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return false;
    }

    String describeError(Throwable error) {
        if (error instanceof WebClientResponseException webEx) {
            int status = webEx.getStatusCode().value();
            if (status == 404) {
                return "OpenProject returned 404. The API user most likely is not a member of project \"" + projectId
                        + "\", or its role lacks \"View work packages\". Add the API user to the project with a role that grants view + add work packages + add attachments.";
            }
            if (status == 401 || status == 403) {
                return "OpenProject rejected the API token (" + status + "). Check openproject.api.key is a current token for an active user.";
            }
            try {
                JsonNode body = objectMapper.readTree(webEx.getResponseBodyAsString());
                String message = body.path("message").asText(null);
                String errorIdentifier = body.path("errorIdentifier").asText(null);
                if (message != null) {
                    return errorIdentifier != null ? message + " (" + errorIdentifier + ")" : message;
                }
            } catch (Exception ignored) {
            }
            return "HTTP " + status + ": " + webEx.getResponseBodyAsString();
        }
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    @lombok.Data
    @lombok.Builder
    public static class OpenProjectValidationResult {
        private boolean success;
        private String message;
        private String projectName;
    }

    @lombok.Data
    @lombok.Builder
    public static class OpenProjectIssueResult {
        private boolean success;
        private String issueKey;
        private String issueUrl;
        private String errorMessage;
    }
}
