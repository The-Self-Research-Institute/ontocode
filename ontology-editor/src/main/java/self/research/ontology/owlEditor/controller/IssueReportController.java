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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for issue reporting and Jira integration
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/issues")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class IssueReportController {
    
    private final IssueReportService issueReportService;
    
    public IssueReportController(IssueReportService issueReportService) {
        this.issueReportService = issueReportService;
    }
    
    /**
     * Submit an issue report
     * Accepts multipart form data with issue details and optional attachments
     */
    @PostMapping(value = "/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> reportIssue(
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
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments
    ) {
        try {
            log.info("Received issue report: {}", title);
            
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
                .projectId(projectId)
                .projectName(projectName)
                .ontologyFilePath(ontologyFilePath)
                .errorLogs(errorLogs)
                .issueType(issueType)
                .systemInfo(systemInfo)
                .build();
            
            // Validate attachment sizes
            if (attachments != null) {
                for (MultipartFile file : attachments) {
                    if (file.getSize() > 10 * 1024 * 1024) { // 10MB limit
                        return ResponseEntity.badRequest()
                            .body(Map.of(
                                "success", false,
                                "message", "Attachment " + file.getOriginalFilename() + " exceeds 10MB limit"
                            ));
                    }
                }
                if (attachments.size() > 5) {
                    return ResponseEntity.badRequest()
                        .body(Map.of(
                            "success", false,
                            "message", "Maximum 5 attachments allowed"
                        ));
                }
            }
            
            // Submit issue
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
