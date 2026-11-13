package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.IssueReport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcIssueReportRepository implements IssueReportRepository {

    private static final TypeReference<IssueReport.SystemInfo> SYSTEM_INFO = new TypeReference<>() {
    };

    private static final SqlUpsert UPSERT = SqlUpsert.into("issue_report").key("id")
            .columns("id", "title", "description", "steps_to_reproduce", "user_email", "reporter_username",
                    "reporter_email", "project_id", "project_name", "ontology_file_path", "system_info",
                    "attachment_file_names", "error_logs", "issue_type", "priority", "created_at", "issue_key",
                    "issue_url", "status", "failure_reason")
            .build();

    private static final RowMapper<IssueReport> MAPPER = JdbcIssueReportRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcIssueReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IssueReport save(IssueReport r) {
        if (r.getId() == null || r.getId().isBlank()) {
            r.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), r.getId(), r.getTitle(), r.getDescription(), r.getStepsToReproduce(),
                r.getUserEmail(), r.getReporterUsername(), r.getReporterEmail(), r.getProjectId(), r.getProjectName(),
                r.getOntologyFilePath(), EditorSql.json(r.getSystemInfo()), r.getAttachmentFileNames(),
                r.getErrorLogs(), r.getIssueType(), r.getPriority(), Instants.toDb(r.getCreatedAt()), r.getIssueKey(),
                r.getIssueUrl(), r.getStatus() == null ? null : r.getStatus().name(), r.getFailureReason());
        return r;
    }

    @Override
    public Optional<IssueReport> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select * from issue_report where id = ?", MAPPER, id));
    }

    @Override
    public List<IssueReport> findByUserEmailOrderByCreatedAtDesc(String userEmail) {
        return EditorSql.query(jdbc, "select * from issue_report where user_email = ? order by created_at desc nulls last, id desc",
                MAPPER, userEmail);
    }

    @Override
    public List<IssueReport> findByProjectIdOrderByCreatedAtDesc(String projectId) {
        return EditorSql.query(jdbc, "select * from issue_report where project_id = ? order by created_at desc nulls last, id desc",
                MAPPER, projectId);
    }

    @Override
    public List<IssueReport> findByStatusOrderByCreatedAtDesc(IssueReport.IssueStatus status) {
        return EditorSql.query(jdbc, "select * from issue_report where status = ? order by created_at desc nulls last, id desc",
                MAPPER, status == null ? null : status.name());
    }

    @Override
    public long deleteAllByCreatedAtBefore(Instant cutoff) {
        return EditorSql.update(jdbc, "delete from issue_report where created_at < ?", Instants.toDb(cutoff));
    }

    @Override
    public long deleteAllByUserEmailOrReporterEmail(String userEmail, String reporterEmail) {
        return EditorSql.update(jdbc, "delete from issue_report where user_email = ? or reporter_email = ?",
                userEmail, reporterEmail);
    }

    private static IssueReport map(ResultSet rs, int row) throws SQLException {
        String status = rs.getString("status");
        return IssueReport.builder()
                .id(rs.getString("id"))
                .title(rs.getString("title"))
                .description(rs.getString("description"))
                .stepsToReproduce(rs.getString("steps_to_reproduce"))
                .userEmail(rs.getString("user_email"))
                .reporterUsername(rs.getString("reporter_username"))
                .reporterEmail(rs.getString("reporter_email"))
                .projectId(rs.getString("project_id"))
                .projectName(rs.getString("project_name"))
                .ontologyFilePath(rs.getString("ontology_file_path"))
                .systemInfo(EditorSql.readJson(rs, "system_info", SYSTEM_INFO))
                .attachmentFileNames(EditorSql.readList(rs, "attachment_file_names"))
                .errorLogs(rs.getString("error_logs"))
                .issueType(rs.getString("issue_type"))
                .priority(rs.getString("priority"))
                .createdAt(Instants.read(rs, "created_at"))
                .issueKey(rs.getString("issue_key"))
                .issueUrl(rs.getString("issue_url"))
                .status(status == null ? null : IssueReport.IssueStatus.valueOf(status))
                .failureReason(rs.getString("failure_reason"))
                .build();
    }
}
