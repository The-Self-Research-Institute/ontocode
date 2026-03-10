package self.research.ontology.owlEditor.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.owlEditor.model.IssueReport;
import self.research.ontology.owlEditor.service.IssueReportService;
import self.research.ontology.owlEditor.service.JiraService;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST API controller for issue reporting and Jira integration
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/issues")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class IssueReportController {
    
    private final IssueReportService issueReportService;
    
    public IssueReportController(IssueReportService issueReportService) {
        this.issueReportService = issueReportService;
    }
    
    /**
     * Extract user information from JWT token in Authorization header
     */
    private Map<String, String> extractUserFromToken(HttpServletRequest request) {
        Map<String, String> userInfo = new HashMap<>();
        try {
            String authHeader = request.getHeader("Authorization");
            log.info("Authorization header: {}", authHeader != null ? "Present (Bearer token)" : "Not present");
            
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                String[] parts = token.split("\\.");
                if (parts.length >= 2) {
                    String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                    log.info("JWT payload decoded successfully");
                    
                    ObjectMapper mapper = new ObjectMapper();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> claims = mapper.readValue(payload, Map.class);
                    
                    log.info("JWT claims: {}", claims.keySet());
                    
                    // Extract username (usually in 'sub' claim)
                    if (claims.containsKey("sub")) {
                        userInfo.put("username", claims.get("sub").toString());
                        log.info("Extracted username from 'sub': {}", claims.get("sub"));
                    } else {
                        log.warn("'sub' claim not found in token");
                    }
                    
                    // Extract email if available
                    if (claims.containsKey("email")) {
                        userInfo.put("email", claims.get("email").toString());
                        log.info("Extracted email: {}", claims.get("email"));
                    } else {
                        log.warn("'email' claim not found in token");
                    }
                }
            } else {
                log.warn("No Bearer token found in Authorization header");
            }
        } catch (Exception e) {
            log.error("Failed to extract user info from JWT token", e);
        }
        return userInfo;
    }
    
    /**
     * Submit an issue report
     * Accepts multipart form data with issue details and optional attachments
     */
    @PostMapping(value = "/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> reportIssue(
            HttpServletRequest request,
            @RequestParam("title") String title,
            @RequestParam("description") String description,
            @RequestParam(value = "stepsToReproduce", required = false) String stepsToReproduce,
            @RequestParam(value = "userEmail", required = false) String userEmail,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "projectName", required = false) String projectName,
            @RequestParam(value = "ontologyFilePath", required = false) String ontologyFilePath,
            @RequestParam(value = "errorLogs", required = false) String errorLogs,
            @RequestParam(value = "osName", required = false) String osName,
            @RequestParam(value = "osVersion", required = false) String osVersion,
            @RequestParam(value = "vsCodeVersion", required = false) String vsCodeVersion,
            @RequestParam(value = "extensionVersion", required = false) String extensionVersion,
            @RequestParam(value = "issueType", defaultValue = "Task") String issueType,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments
    ) {
        try {
            log.info("Received issue report: {}", title);
            
            // Extract user info from JWT token
            Map<String, String> userInfo = extractUserFromToken(request);
            String reporterUsername = userInfo.get("username");
            String reporterEmail = userInfo.get("email");
            
            log.info("Reporter: {} ({})", reporterUsername, reporterEmail);
            
            // Build system info
            IssueReport.SystemInfo systemInfo = IssueReport.SystemInfo.builder()
                .osName(osName)
                .osVersion(osVersion)
                .vsCodeVersion(vsCodeVersion)
                .extensionVersion(extensionVersion)
                .build();
            
            // Build issue report
            IssueReport issueReport = IssueReport.builder()
                .title(title)
                .description(description)
                .stepsToReproduce(stepsToReproduce)
                .userEmail(userEmail)
                .reporterUsername(reporterUsername)
                .reporterEmail(reporterEmail)
                .projectId(projectId)
                .projectName(projectName)
                .ontologyFilePath(ontologyFilePath)
                .errorLogs(errorLogs)
                .issueType(issueType)
                .priority(priority)
                .systemInfo(systemInfo)
                .build();
            

            // Submit issue (no file size or count limits)
            IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(issueReport, attachments);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            
            if (result.getIssueReportId() != null) {
                response.put("issueReportId", result.getIssueReportId());
            }
            if (result.getJiraIssueKey() != null) {
                response.put("jiraIssueKey", result.getJiraIssueKey());
            }
            if (result.getJiraIssueUrl() != null) {
                response.put("jiraIssueUrl", result.getJiraIssueUrl());
            }
            
            if (result.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            
        } catch (Exception e) {
            log.error("Failed to process issue report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to process issue report: " + e.getMessage()
                ));
        }
    }
    
    /**
     * Get issue reports for a user
     */
    @GetMapping("/user/{email}")
    public ResponseEntity<List<IssueReport>> getUserIssueReports(@PathVariable String email) {
        try {
            List<IssueReport> reports = issueReportService.getUserIssueReports(email);
            return ResponseEntity.ok(reports);
        } catch (Exception e) {
            log.error("Failed to get user issue reports", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Validate Jira connection (admin endpoint)
     */
    @GetMapping("/jira/validate")
    public ResponseEntity<Map<String, Object>> validateJiraConnection() {
        try {
            JiraService.JiraValidationResult result = issueReportService.validateJiraConnection();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            if (result.getProjectName() != null) {
                response.put("projectName", result.getProjectName());
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to validate Jira connection", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Validation failed: " + e.getMessage()
                ));
        }
    }
}
