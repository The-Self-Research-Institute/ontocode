package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcUndoIntentRepository implements UndoIntentRepository {

    private static final String[] COLUMN_LIST = {"id", "project_id", "op_id", "direction", "actor", "draft", "change_ids",
            "audit_id", "undo_audit_id", "scope_key", "stage", "applied_count", "status", "failure_reason", "created_at",
            "updated_at"};

    private static final String COLUMNS = String.join(", ", COLUMN_LIST);

    private static final SqlUpsert UPSERT = SqlUpsert.into("undo_intent").key("id").columns(COLUMN_LIST).build();

    private static final RowMapper<UndoIntentDocument> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;

    public JdbcUndoIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UndoIntentDocument save(UndoIntentDocument intent) {
        if (intent.getId() == null || intent.getId().isBlank()) {
            intent.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), args(intent));
        return intent;
    }

    @Override
    public Optional<UndoIntentDocument> findById(String id) {
        return EditorSql.first(jdbc.query(select("id = ?"), MAPPER, id));
    }

    @Override
    public void deleteByProjectId(String projectId) {
        jdbc.update("delete from undo_intent where project_id = ?", projectId);
    }

    @Override
    public List<UndoIntentDocument> findByProjectIdAndStatusIn(String projectId, Collection<Status> statuses) {
        return EditorSql.query(jdbc, select("project_id = ? and status = any(?)") + " order by created_at, id", MAPPER,
                projectId, names(statuses));
    }

    @Override
    public List<UndoIntentDocument> findByStatusIn(Collection<Status> statuses) {
        return EditorSql.query(jdbc, select("status = any(?)") + " order by created_at, id", MAPPER, names(statuses));
    }

    @Override
    public Optional<UndoIntentDocument> findByProjectIdAndOpId(String projectId, String opId) {
        return EditorSql.first(jdbc.query(select("project_id = ? and op_id = ?"), MAPPER, projectId, opId));
    }

    @Override
    public List<UndoIntentDocument> findByStatusAndCreatedAtBefore(Status status, Instant before) {
        return jdbc.query(select("status = ? and created_at < ?") + " order by created_at, id", MAPPER,
                status == null ? null : status.name(), Instants.toDb(before));
    }

    private static List<String> names(Collection<Status> statuses) {
        return statuses.stream().map(Status::name).toList();
    }

    private static String select(String where) {
        return "select " + COLUMNS + " from undo_intent where " + where;
    }

    private static Object[] args(UndoIntentDocument u) {
        return new Object[] {u.getId(), u.getProjectId(), u.getOpId(), u.getDirection(), u.getActor(), u.isDraft(),
                u.getChangeIds(), u.getAuditId(), u.getUndoAuditId(), u.getScopeKey(),
                u.getStage() == null ? null : u.getStage().name(), u.getAppliedCount(),
                u.getStatus() == null ? null : u.getStatus().name(), u.getFailureReason(), Instants.toDb(u.getCreatedAt()),
                Instants.toDb(u.getUpdatedAt())};
    }

    private static UndoIntentDocument read(ResultSet rs) throws SQLException {
        String stage = rs.getString("stage");
        String status = rs.getString("status");
        return UndoIntentDocument.builder()
                .id(rs.getString("id"))
                .projectId(rs.getString("project_id"))
                .opId(rs.getString("op_id"))
                .direction(rs.getString("direction"))
                .actor(rs.getString("actor"))
                .draft(rs.getBoolean("draft"))
                .changeIds(EditorSql.readList(rs, "change_ids"))
                .auditId(rs.getString("audit_id"))
                .undoAuditId(rs.getString("undo_audit_id"))
                .scopeKey(rs.getString("scope_key"))
                .stage(stage == null ? null : Stage.valueOf(stage))
                .appliedCount(rs.getInt("applied_count"))
                .status(status == null ? null : Status.valueOf(status))
                .failureReason(rs.getString("failure_reason"))
                .createdAt(Instants.read(rs, "created_at"))
                .updatedAt(Instants.read(rs, "updated_at"))
                .build();
    }
}
