package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAssistantApplyOperationRepository implements AssistantApplyOperationRepository {

    private static final String[] COLUMN_LIST = {"id", "project_id", "group_id", "session_id", "actor", "target_path",
            "status", "snapshot_path", "failure_reason", "resolution", "created_at", "updated_at", "resolved_at"};

    private static final String COLUMNS = String.join(", ", COLUMN_LIST);

    private static final SqlUpsert UPSERT = SqlUpsert.into("assistant_apply_operation").key("id")
            .columns(COLUMN_LIST).build();

    private static final RowMapper<AssistantApplyOperationDocument> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;

    public JdbcAssistantApplyOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AssistantApplyOperationDocument save(AssistantApplyOperationDocument o) {
        if (o.getId() == null || o.getId().isBlank()) {
            o.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), o.getId(), o.getProjectId(), o.getGroupId(), o.getSessionId(),
                o.getActor(), o.getTargetPath(), o.getStatus() == null ? null : o.getStatus().name(),
                o.getSnapshotPath(), o.getFailureReason(), o.getResolution(), Instants.toDb(o.getCreatedAt()),
                Instants.toDb(o.getUpdatedAt()), Instants.toDb(o.getResolvedAt()));
        return o;
    }

    @Override
    public Optional<AssistantApplyOperationDocument> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, select("id = ?"), MAPPER, id));
    }

    @Override
    public Optional<AssistantApplyOperationDocument> findFirstByGroupIdOrderByCreatedAtDesc(String groupId) {
        return EditorSql.first(EditorSql.query(jdbc,
                select("group_id = ?") + " order by created_at desc nulls last, id desc limit 1", MAPPER, groupId));
    }

    @Override
    public List<AssistantApplyOperationDocument> findByStatusIn(Collection<ApplyOperationStatus> statuses) {
        return EditorSql.query(jdbc, select("status = any(?)") + " order by created_at asc nulls first, id", MAPPER,
                names(statuses));
    }

    @Override
    public List<AssistantApplyOperationDocument> findByProjectIdAndStatusIn(String projectId,
                                                                           Collection<ApplyOperationStatus> statuses) {
        return EditorSql.query(jdbc, select("project_id = ? and status = any(?)")
                + " order by created_at asc nulls first, id", MAPPER, projectId, names(statuses));
    }

    @Override
    public long deleteByStatusInAndUpdatedAtBefore(Collection<ApplyOperationStatus> statuses, Instant cutoff) {
        return EditorSql.update(jdbc, "delete from assistant_apply_operation where status = any(?) and updated_at < ?",
                names(statuses), Instants.toDb(cutoff));
    }

    private static List<String> names(Collection<ApplyOperationStatus> statuses) {
        return statuses.stream().map(ApplyOperationStatus::name).toList();
    }

    private static String select(String where) {
        return "select " + COLUMNS + " from assistant_apply_operation where " + where;
    }

    private static AssistantApplyOperationDocument read(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        return AssistantApplyOperationDocument.builder()
                .id(rs.getString("id"))
                .projectId(rs.getString("project_id"))
                .groupId(rs.getString("group_id"))
                .sessionId(rs.getString("session_id"))
                .actor(rs.getString("actor"))
                .targetPath(rs.getString("target_path"))
                .status(status == null ? null : ApplyOperationStatus.valueOf(status))
                .snapshotPath(rs.getString("snapshot_path"))
                .failureReason(rs.getString("failure_reason"))
                .resolution(rs.getString("resolution"))
                .createdAt(Instants.read(rs, "created_at"))
                .updatedAt(Instants.read(rs, "updated_at"))
                .resolvedAt(Instants.read(rs, "resolved_at"))
                .build();
    }
}
