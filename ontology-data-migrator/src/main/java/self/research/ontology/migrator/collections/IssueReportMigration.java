package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class IssueReportMigration implements CollectionMigration {

    @Override
    public String name() {
        return "issue_reports";
    }

    @Override
    public String sourceCollection() {
        return "issue_reports";
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("issue_report", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("title", Values.text(d, "title"))
                .put("description", Values.text(d, "description"))
                .put("steps_to_reproduce", Values.text(d, "stepsToReproduce"))
                .put("user_email", Values.text(d, "userEmail"))
                .put("reporter_username", Values.text(d, "reporterUsername"))
                .put("reporter_email", Values.text(d, "reporterEmail"))
                .put("project_id", Values.text(d, "projectId"))
                .put("project_name", Values.text(d, "projectName"))
                .put("ontology_file_path", Values.text(d, "ontologyFilePath"))
                .put("system_info", Values.json(d.get("systemInfo")))
                .put("attachment_file_names", Values.list(d, "attachmentFileNames"))
                .put("error_logs", Values.text(d, "errorLogs"))
                .put("issue_type", Values.text(d, "issueType"))
                .put("priority", Values.text(d, "priority"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("issue_key", Values.text(d, "issueKey"))
                .put("issue_url", Values.text(d, "issueUrl"))
                .put("status", Values.text(d, "status"))
                .put("failure_reason", Values.text(d, "failureReason"))
                .build());
    }
}
