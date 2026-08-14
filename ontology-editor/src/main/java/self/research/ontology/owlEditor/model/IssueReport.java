package self.research.ontology.owlEditor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Represents a user-submitted issue/bug report
 * Stored in MongoDB for audit trail and fallback when Jira is unavailable
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "issue_reports")
public class IssueReport {
    
    @Id
    private String id;
    
    private String title;
    
    private String description;
    
    private String stepsToReproduce;
    
    private String userEmail;
    
    private String reporterUsername;
    
    private String reporterEmail;
    
    private String projectId;
    
    private String projectName;
    
    private String ontologyFilePath;
    
    private SystemInfo systemInfo;
    
    private List<String> attachmentFileNames;
    
    private String errorLogs;
    
    private String issueType;
    
    private String priority;
    
    private Instant createdAt;
    
    private String jiraIssueKey;
    
    private String jiraIssueUrl;
    
    private IssueStatus status;
    
    private String failureReason;
    
    public enum IssueStatus {
        PENDING,           // Not yet submitted to Jira
        SUBMITTED,         // Successfully created in Jira
        FAILED,            // Failed to create in Jira
        LOCAL_ONLY         // Jira disabled, stored locally only
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemInfo {
        private String osName;
        private String osVersion;
        private String vsCodeVersion;
        private String extensionVersion;
        private String javaVersion;
        private String timestamp;
    }
}
