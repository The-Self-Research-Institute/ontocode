package self.research.ontology.owlEditor.controller;

import self.research.ontology.owlEditor.config.CallerEmail;
import self.research.ontology.owlEditor.config.CallerIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.owlEditor.model.IssueReport;
import self.research.ontology.owlEditor.service.IssueReportService;
import self.research.ontology.owlEditor.service.OpenProjectService;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/v1/issues")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class IssueReportController {

    private static final int MAX_TEXT_CHARS = 20_000;
    private static final int MAX_LOG_CHARS = 200_000;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final IssueReportService issueReportService;

    public IssueReportController(IssueReportService issueReportService) {
        this.issueReportService = issueReportService;
    }

    private static String limited(String value, int maxChars) {
        return value == null || value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

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

            CallerIdentity caller = CallerIdentity.of(request);
            String reporterUsername = caller.userId();
            String reporterEmail = caller.email();

            boolean signedIn = reporterEmail != null && !reporterEmail.isBlank();
            boolean validContactEmail = userEmail != null && EMAIL_PATTERN.matcher(userEmail.trim()).matches();
            if (!signedIn && !validContactEmail) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "message", "An email address is required so we can reply to you."
                ));
            }

            IssueReport.SystemInfo systemInfo = IssueReport.SystemInfo.builder()
                .osName(osName)
                .osVersion(osVersion)
                .vsCodeVersion(vsCodeVersion)
                .extensionVersion(extensionVersion)
                .build();

            IssueReport issueReport = IssueReport.builder()
                .title(title)
                .description(limited(description, MAX_TEXT_CHARS))
                .stepsToReproduce(limited(stepsToReproduce, MAX_TEXT_CHARS))
                .userEmail(userEmail)
                .reporterUsername(reporterUsername)
                .reporterEmail(reporterEmail)
                .projectId(projectId)
                .projectName(projectName)
                .ontologyFilePath(ontologyFilePath)
                .errorLogs(limited(errorLogs, MAX_LOG_CHARS))
                .issueType(issueType)
                .priority(priority)
                .systemInfo(systemInfo)
                .build();

            IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(issueReport, attachments);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());

            if (result.getIssueReportId() != null) {
                response.put("issueReportId", result.getIssueReportId());
            }
            if (result.getIssueKey() != null) {
                response.put("issueKey", result.getIssueKey());
            }
            if (result.getIssueUrl() != null) {
                response.put("issueUrl", result.getIssueUrl());
            }
            if (result.getTrackerFailureReason() != null) {
                response.put("trackerFailureReason", result.getTrackerFailureReason());
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

    @GetMapping("/user/{email}")
    public ResponseEntity<List<IssueReport>> getUserIssueReports(@PathVariable String email,
                                                                 @CallerEmail String callerEmail) {
        if (!callerEmail.equalsIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            List<IssueReport> reports = issueReportService.getUserIssueReports(email);
            return ResponseEntity.ok(reports);
        } catch (Exception e) {
            log.error("Failed to get user issue reports", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/openproject/validate")
    public ResponseEntity<Map<String, Object>> validateOpenProjectConnection() {
        try {
            OpenProjectService.OpenProjectValidationResult result = issueReportService.validateOpenProjectConnection();

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            if (result.getProjectName() != null) {
                response.put("projectName", result.getProjectName());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to validate OpenProject connection", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Validation failed: " + e.getMessage()
                ));
        }
    }
}
