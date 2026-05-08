package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Service for integrating with Jira Cloud REST API v3
 * Handles issue creation, attachment uploads, and connection validation
 */
@Slf4j
@Service
public class JiraService {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    @Value("${jira.cloud.url:}")
    private String jiraCloudUrl;
    
    @Value("${jira.user.email:}")
    private String jiraUserEmail;
    
    @Value("${jira.api.token:}")
    private String jiraApiToken;
    
    @Value("${jira.project.key:}")
    private String jiraProjectKey;
    
    @Value("${jira.epic.key:}")
    private String jiraEpicKey;
    
    @Value("${jira.issue.type:Task}")
    private String jiraIssueType;
    
    @Value("${jira.enabled:false}")
    private boolean jiraEnabled;
    
    public JiraService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }
    
    /**
     * Check if Jira integration is enabled and properly configured
     */
    public boolean isEnabled() {
        boolean enabled = jiraEnabled 
            && jiraCloudUrl != null && !jiraCloudUrl.isEmpty()
            && jiraUserEmail != null && !jiraUserEmail.isEmpty()
            && jiraApiToken != null && !jiraApiToken.isEmpty()
            && jiraProjectKey != null && !jiraProjectKey.isEmpty();
        
        log.debug("Jira isEnabled check: jiraEnabled={}, jiraCloudUrl={}, jiraUserEmail={}, jiraProjectKey={}, result={}", 
            jiraEnabled, 
            jiraCloudUrl != null && !jiraCloudUrl.isEmpty() ? "configured" : "empty",
            jiraUserEmail != null && !jiraUserEmail.isEmpty() ? "configured" : "empty", 
            jiraProjectKey != null && !jiraProjectKey.isEmpty() ? "configured" : "empty",
            enabled);
        
        return enabled;
    }
    
    /**
     * Validate connection to Jira and check access to project and epic
     */
    public JiraValidationResult validateConnection() {
        if (!isEnabled()) {
            return JiraValidationResult.builder()
                .success(false)
                .message("Jira integration is disabled or not configured")
                .build();
        }
        
        try {
            PermissionCheck permissionCheck = checkProjectPermissions();
            if (permissionCheck.checked && (!permissionCheck.canBrowseProject || !permissionCheck.canCreateIssues)) {
                StringBuilder message = new StringBuilder("Jira credentials are valid but missing permissions for project ")
                    .append(jiraProjectKey)
                    .append(": ");

                if (!permissionCheck.canBrowseProject) {
                    message.append("Browse Projects");
                }
                if (!permissionCheck.canBrowseProject && !permissionCheck.canCreateIssues) {
                    message.append(", ");
                }
                if (!permissionCheck.canCreateIssues) {
                    message.append("Create Issues");
                }

                return JiraValidationResult.builder()
                    .success(false)
                    .message(message.toString())
                    .build();
            }

            // Test connection by getting project details
            String projectUrl = jiraCloudUrl + "/rest/api/3/project/" + jiraProjectKey;
            
            JsonNode project = webClient.get()
                .uri(projectUrl)
                .headers(headers -> setAuthHeaders(headers))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));
            
            if (project == null) {
                return JiraValidationResult.builder()
                    .success(false)
                    .message("Could not retrieve project information")
                    .build();
            }
            
            // Validate epic if specified
            if (jiraEpicKey != null && !jiraEpicKey.isEmpty()) {
                String issueUrl = jiraCloudUrl + "/rest/api/3/issue/" + jiraEpicKey;
                JsonNode epic = webClient.get()
                    .uri(issueUrl)
                    .headers(headers -> setAuthHeaders(headers))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10));
                
                if (epic == null) {
                    return JiraValidationResult.builder()
                        .success(false)
                        .message("Epic " + jiraEpicKey + " not found")
                        .build();
                }
            }
            
            return JiraValidationResult.builder()
                .success(true)
                .message("Successfully connected to Jira project: " + project.get("name").asText())
                .projectName(project.get("name").asText())
                .build();
                
        } catch (WebClientResponseException e) {
            log.error("Jira validation failed with HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return JiraValidationResult.builder()
                .success(false)
                .message("Authentication failed: " + e.getMessage())
                .build();
        } catch (Exception e) {
            log.error("Jira validation failed", e);
            return JiraValidationResult.builder()
                .success(false)
                .message("Connection failed: " + e.getMessage())
                .build();
        }
    }

    private PermissionCheck checkProjectPermissions() {
        try {
            String permissionsUrl = jiraCloudUrl
                + "/rest/api/3/mypermissions?projectKey="
                + URLEncoder.encode(jiraProjectKey, StandardCharsets.UTF_8)
                + "&permissions=BROWSE_PROJECTS,CREATE_ISSUES";

            JsonNode response = webClient.get()
                .uri(permissionsUrl)
                .headers(this::setAuthHeaders)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));

            JsonNode permissions = response != null ? response.path("permissions") : null;
            boolean canBrowseProject = permissions != null && permissions.path("BROWSE_PROJECTS").path("havePermission").asBoolean(false);
            boolean canCreateIssues = permissions != null && permissions.path("CREATE_ISSUES").path("havePermission").asBoolean(false);

            return new PermissionCheck(true, canBrowseProject, canCreateIssues);
        } catch (Exception e) {
            log.warn("Could not validate Jira permissions for project {}", jiraProjectKey, e);
            return new PermissionCheck(false, false, false);
        }
    }
    
    /**
     * Create a bug issue in Jira under the configured epic
     */
    public JiraIssueResult createBugIssue(String summary, String description, String priority, String issueType) {
        if (!isEnabled()) {
            throw new IllegalStateException("Jira integration is not enabled");
        }

        try {
            ObjectNode issueData = buildIssuePayload(summary, description, priority, issueType);
            
            log.info("Creating Jira issue in project {} under epic {} with type {}", jiraProjectKey, jiraEpicKey, issueType);
            log.debug("Jira issue payload: {}", issueData.toPrettyString());
            
            String createUrl = jiraCloudUrl + "/rest/api/3/issue";
            
            JsonNode response = webClient.post()
                .uri(createUrl)
                .headers(headers -> setAuthHeaders(headers))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(issueData)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnError(error -> {
                    if (error instanceof WebClientResponseException) {
                        WebClientResponseException webEx = (WebClientResponseException) error;
                        log.error("Jira API error: HTTP {} - {}", webEx.getStatusCode(), webEx.getResponseBodyAsString());
                    } else {
                        log.error("Jira API error: {}", error.getMessage(), error);
                    }
                })
                .block(Duration.ofSeconds(30));
            
            if (response == null) {
                throw new RuntimeException("No response from Jira");
            }
            
            String issueKey = response.get("key").asText();
            String issueId = response.get("id").asText();
            String issueUrl = jiraCloudUrl + "/browse/" + issueKey;
            
            log.info("Created Jira issue: {} ({})", issueKey, issueUrl);
            
            return JiraIssueResult.builder()
                .success(true)
                .issueKey(issueKey)
                .issueId(issueId)
                .issueUrl(issueUrl)
                .build();
                
        } catch (WebClientResponseException e) {
            String errorMsg = "HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
            log.error(errorMsg);
            return JiraIssueResult.builder()
                .success(false)
                .errorMessage(errorMsg)
                .build();
        } catch (Exception e) {
            String errorMsg = "Failed to create Jira issue: " + e.getMessage();
            log.error(errorMsg, e);
            return JiraIssueResult.builder()
                .success(false)
                .errorMessage(errorMsg)
                .build();
        }
    }
    
    /**
     * Upload attachment to an existing Jira issue
     */
    public boolean uploadAttachment(String issueKey, String fileName, byte[] fileData) {
        if (!isEnabled()) {
            log.warn("Jira not enabled, skipping attachment upload");
            return false;
        }
        
        try {
            String attachmentUrl = jiraCloudUrl + "/rest/api/3/issue/" + issueKey + "/attachments";
            
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(fileData) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });
            
            webClient.post()
                .uri(attachmentUrl)
                .headers(headers -> {
                    setAuthHeaders(headers);
                    headers.set("X-Atlassian-Token", "no-check");
                })
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
            
            log.info("Uploaded attachment {} to issue {}", fileName, issueKey);
            return true;
            
        } catch (Exception e) {
            log.error("Failed to upload attachment {} to issue {}", fileName, issueKey, e);
            return false;
        }
    }
    
    /**
     * Build Jira issue creation payload
     */
    private ObjectNode buildIssuePayload(String summary, String description, String priority, String issueType) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode fields = objectMapper.createObjectNode();

        // Project
        ObjectNode project = objectMapper.createObjectNode();
        project.put("key", jiraProjectKey);
        fields.set("project", project);

        // Issue type
        ObjectNode issueTypeNode = objectMapper.createObjectNode();
        issueTypeNode.put("name", issueType);
        fields.set("issuetype", issueTypeNode);

        // Summary and description
        fields.put("summary", summary);

        // Description in Atlassian Document Format (ADF)
        ObjectNode descriptionAdf = buildDescriptionAdf(description);
        fields.set("description", descriptionAdf);

        // Priority
        if (priority != null && !priority.isEmpty()) {
            ObjectNode priorityNode = objectMapper.createObjectNode();
            priorityNode.put("name", priority);
            fields.set("priority", priorityNode);
        }

        // Parent epic (if configured) - Note: Not all projects support parent field
        // If this fails, the epic can be linked manually or via a different mechanism
        if (jiraEpicKey != null && !jiraEpicKey.isEmpty()) {
            try {
                ObjectNode parent = objectMapper.createObjectNode();
                parent.put("key", jiraEpicKey);
                fields.set("parent", parent);
                log.debug("Setting parent epic: {}", jiraEpicKey);
            } catch (Exception e) {
                log.warn("Could not set parent epic {}: {}", jiraEpicKey, e.getMessage());
            }
        }

        payload.set("fields", fields);
        return payload;
    }
    
    /**
     * Build Atlassian Document Format (ADF) for description
     */
    private ObjectNode buildDescriptionAdf(String description) {
        ObjectNode adf = objectMapper.createObjectNode();
        adf.put("version", 1);
        adf.put("type", "doc");
        
        ArrayNode content = objectMapper.createArrayNode();
        
        ObjectNode paragraph = objectMapper.createObjectNode();
        paragraph.put("type", "paragraph");
        
        ArrayNode paragraphContent = objectMapper.createArrayNode();
        ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "text");
        text.put("text", description);
        paragraphContent.add(text);
        
        paragraph.set("content", paragraphContent);
        content.add(paragraph);
        
        adf.set("content", content);
        return adf;
    }
    
    /**
     * Set authentication headers for Jira API
     */
    private void setAuthHeaders(HttpHeaders headers) {
        String auth = jiraUserEmail + ":" + jiraApiToken;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);
        headers.set("Accept", "application/json");
    }
    
    /**
     * Determine priority based on keywords in summary/description
     */
    public static String determinePriority(String summary, String description) {
        String combined = (summary + " " + description).toLowerCase();
        
        if (combined.contains("crash") || combined.contains("data loss") || combined.contains("critical")) {
            return "Highest";
        } else if (combined.contains("error") || combined.contains("broken") || combined.contains("failure")) {
            return "High";
        } else if (combined.contains("slow") || combined.contains("performance")) {
            return "Medium";
        }
        
        return "Medium"; // Default
    }
    
    @lombok.Data
    @lombok.Builder
    public static class JiraValidationResult {
        private boolean success;
        private String message;
        private String projectName;
    }
    
    @lombok.Data
    @lombok.Builder
    public static class JiraIssueResult {
        private boolean success;
        private String issueKey;
        private String issueId;
        private String issueUrl;
        private String errorMessage;
    }

    private record PermissionCheck(boolean checked, boolean canBrowseProject, boolean canCreateIssues) {}
}
