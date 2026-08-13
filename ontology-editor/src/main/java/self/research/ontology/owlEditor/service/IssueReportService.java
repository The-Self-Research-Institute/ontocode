package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.owlEditor.model.IssueReport;
import self.research.ontology.owlEditor.repository.IssueReportRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class IssueReportService {

    private final IssueReportRepository issueReportRepository;
    private final JiraService jiraService;

    public IssueReportService(IssueReportRepository issueReportRepository, JiraService jiraService) {
        this.issueReportRepository = issueReportRepository;
        this.jiraService = jiraService;
    }

    public IssueReportResult submitIssueReport(IssueReport issueReport, List<MultipartFile> attachments) {
        try {

            issueReport.setCreatedAt(Instant.now());

            if (issueReport.getTitle() == null || issueReport.getTitle().trim().isEmpty()) {
                return IssueReportResult.builder()
                    .success(false)
                    .message("Title is required")
                    .build();
            }

            if (issueReport.getDescription() == null || issueReport.getDescription().trim().isEmpty()) {
                return IssueReportResult.builder()
                    .success(false)
                    .message("Description is required")
                    .build();
            }

            String fullDescription = buildJiraDescription(issueReport);

            String priority = (issueReport.getPriority() != null && !issueReport.getPriority().isEmpty())
                ? issueReport.getPriority()
                : JiraService.determinePriority(issueReport.getTitle(), issueReport.getDescription());

            String issueType = (issueReport.getIssueType() != null && !issueReport.getIssueType().isEmpty())
                ? issueReport.getIssueType()
                : "Task";

            if (jiraService.isEnabled()) {
                try {
                    JiraService.JiraIssueResult jiraResult = jiraService.createBugIssue(
                        issueReport.getTitle(),
                        fullDescription,
                        priority,
                        issueType
                    );

                    if (jiraResult.isSuccess()) {
                        issueReport.setJiraIssueKey(jiraResult.getIssueKey());
                        issueReport.setJiraIssueUrl(jiraResult.getIssueUrl());
                        issueReport.setStatus(IssueReport.IssueStatus.SUBMITTED);

                        if (attachments != null && !attachments.isEmpty()) {
                            List<String> attachmentNames = new ArrayList<>();
                            for (MultipartFile file : attachments) {
                                try {
                                    boolean uploaded = jiraService.uploadAttachment(
                                        jiraResult.getIssueKey(),
                                        file.getOriginalFilename(),
                                        file.getBytes()
                                    );
                                    if (uploaded) {
                                        attachmentNames.add(file.getOriginalFilename());
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to upload attachment {}", file.getOriginalFilename(), e);
                                }
                            }
                            issueReport.setAttachmentFileNames(attachmentNames);
                        }

                        IssueReport saved = issueReportRepository.save(issueReport);

                        log.info("Issue report submitted successfully: {} -> {}", saved.getId(), jiraResult.getIssueKey());

                        return IssueReportResult.builder()
                            .success(true)
                            .message("Issue reported successfully")
                            .issueReportId(saved.getId())
                            .jiraIssueKey(jiraResult.getIssueKey())
                            .jiraIssueUrl(jiraResult.getIssueUrl())
                            .build();
                    } else {

                        issueReport.setStatus(IssueReport.IssueStatus.FAILED);
                        issueReport.setFailureReason(jiraResult.getErrorMessage());
                        IssueReport saved = issueReportRepository.save(issueReport);

                        log.warn("Jira issue creation failed, saved locally: {}", saved.getId());

                        return IssueReportResult.builder()
                            .success(true)
                            .message("Issue saved locally but failed to create Jira ticket. Our team has been notified.")
                            .issueReportId(saved.getId())
                            .jiraFailureReason(jiraResult.getErrorMessage())
                            .build();
                    }

                } catch (Exception e) {
                    log.error("Exception while creating Jira issue", e);
                    issueReport.setStatus(IssueReport.IssueStatus.FAILED);
                    issueReport.setFailureReason(e.getMessage());
                    IssueReport saved = issueReportRepository.save(issueReport);

                    return IssueReportResult.builder()
                        .success(true)
                        .message("Issue saved locally but failed to create Jira ticket: " + e.getMessage())
                        .issueReportId(saved.getId())
                        .jiraFailureReason(e.getMessage())
                        .build();
                }
            } else {

                issueReport.setStatus(IssueReport.IssueStatus.LOCAL_ONLY);
                IssueReport saved = issueReportRepository.save(issueReport);

                log.info("Issue report saved locally (Jira disabled): {}", saved.getId());

                return IssueReportResult.builder()
                    .success(true)
                    .message("Issue logged locally. Please contact support@ontocode.com for assistance.")
                    .issueReportId(saved.getId())
                    .build();
            }

        } catch (Exception e) {
            log.error("Failed to submit issue report", e);
            return IssueReportResult.builder()
                .success(false)
                .message("Failed to submit issue report: " + e.getMessage())
                .build();
        }
    }

    private String buildJiraDescription(IssueReport issueReport) {
        StringBuilder sb = new StringBuilder();

        String reporterDisplayEmail = issueReport.getReporterEmail() != null
            ? issueReport.getReporterEmail()
            : issueReport.getUserEmail();

        if (issueReport.getReporterUsername() != null || reporterDisplayEmail != null) {
            sb.append("*Reported By:* ");
            if (issueReport.getReporterUsername() != null) {
                sb.append(issueReport.getReporterUsername());
                if (reporterDisplayEmail != null) {
                    sb.append(" (").append(reporterDisplayEmail).append(")");
                }
            } else if (reporterDisplayEmail != null) {
                sb.append(reporterDisplayEmail);
            }
            sb.append("\n\n");
        }

        sb.append(issueReport.getDescription()).append("\n\n");

        if (issueReport.getStepsToReproduce() != null && !issueReport.getStepsToReproduce().trim().isEmpty()) {
            sb.append("Steps to Reproduce:\n");
            sb.append(issueReport.getStepsToReproduce()).append("\n\n");
        }

        if (issueReport.getSystemInfo() != null) {
            IssueReport.SystemInfo sys = issueReport.getSystemInfo();
            sb.append("System Information:\n");
            if (sys.getOsName() != null) {
                sb.append("OS: ").append(sys.getOsName());
                if (sys.getOsVersion() != null && !sys.getOsVersion().equals(sys.getOsName())) {
                    sb.append(" (").append(sys.getOsVersion()).append(")");
                }
                sb.append("\n");
            }
            if (sys.getVsCodeVersion() != null && !"Unknown".equals(sys.getVsCodeVersion())) {
                sb.append("VS Code: ").append(sys.getVsCodeVersion()).append("\n");
            }
            if (sys.getExtensionVersion() != null && !"Unknown".equals(sys.getExtensionVersion())) {
                sb.append("OntoCode Extension: ").append(sys.getExtensionVersion()).append("\n");
            }
            sb.append("\n");
        }

        if (issueReport.getErrorLogs() != null && !issueReport.getErrorLogs().trim().isEmpty()) {
            sb.append("Error Logs:\n");
            sb.append("{code}\n");
            sb.append(issueReport.getErrorLogs());
            sb.append("\n{code}\n");
        }

        return sb.toString();
    }

    public List<IssueReport> getUserIssueReports(String userEmail) {
        return issueReportRepository.findByUserEmailOrderByCreatedAtDesc(userEmail);
    }

    public JiraService.JiraValidationResult validateJiraConnection() {
        return jiraService.validateConnection();
    }

    @lombok.Data
    @lombok.Builder
    public static class IssueReportResult {
        private boolean success;
        private String message;
        private String issueReportId;
        private String jiraIssueKey;
        private String jiraIssueUrl;
        private String jiraFailureReason;
    }
}
