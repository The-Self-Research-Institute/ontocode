package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckRecord;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckState;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAssistantEditGroupRepository implements AssistantEditGroupRepository {

    private static final String[] COLUMN_LIST = {"id", "session_id", "project_id", "user_email", "client_group_id",
            "target_path", "draft", "draft_user_id", "edits", "status", "public_graph_version_at_propose",
            "positions_verified_at_version", "applied_revision", "stale_reason", "created_at", "updated_at",
            "expires_at", "applied_at", "check_job_id", "check_state", "check_detail", "check_resolved_at"};

    private static final String COLUMNS = String.join(", ", COLUMN_LIST);

    private static final SqlUpsert UPSERT = SqlUpsert.into("assistant_edit_group").key("id").columns(COLUMN_LIST)
            .build();

    private static final String LIVE = " and (expires_at is null or expires_at > now())";

    private static final TypeReference<List<EditEntry>> EDITS = new TypeReference<>() {
    };

    private static final RowMapper<AssistantEditGroupDocument> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;

    public JdbcAssistantEditGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AssistantEditGroupDocument save(AssistantEditGroupDocument g) {
        if (g.getId() == null || g.getId().isBlank()) {
            g.setId(Ids.newObjectId());
        }
        ConsistencyCheckRecord check = g.getConsistencyCheck();
        EditorSql.update(jdbc, UPSERT.sql(), g.getId(), g.getSessionId(), g.getProjectId(), g.getUserEmail(),
                g.getClientGroupId(), g.getTargetPath(), g.isDraft(), g.getDraftUserId(),
                EditorSql.json(g.getEdits()), g.getStatus() == null ? null : g.getStatus().name(),
                g.getPublicGraphVersionAtPropose(), g.getPositionsVerifiedAtVersion(), g.getAppliedRevision(),
                g.getStaleReason(), Instants.toDb(g.getCreatedAt()), Instants.toDb(g.getUpdatedAt()),
                Instants.toDb(g.getExpiresAt()), Instants.toDb(g.getAppliedAt()),
                check == null ? null : check.getJobId(),
                check == null || check.getState() == null ? null : check.getState().name(),
                check == null ? null : check.getDetail(),
                check == null ? null : Instants.toDb(check.getResolvedAt()));
        return g;
    }

    @Override
    public List<AssistantEditGroupDocument> saveAll(Iterable<AssistantEditGroupDocument> groups) {
        List<AssistantEditGroupDocument> saved = new ArrayList<>();
        groups.forEach(g -> saved.add(save(g)));
        return saved;
    }

    @Override
    public Optional<AssistantEditGroupDocument> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, select("id = ?" + LIVE), MAPPER, id));
    }

    @Override
    public List<AssistantEditGroupDocument> findAllById(Iterable<String> ids) {
        List<String> list = new ArrayList<>();
        ids.forEach(list::add);
        return EditorSql.query(jdbc, select("id = any(?)" + LIVE), MAPPER, list);
    }

    @Override
    public Optional<AssistantEditGroupDocument> findByIdAndUserEmail(String id, String userEmail) {
        return EditorSql.first(EditorSql.query(jdbc, select("id = ? and user_email = ?" + LIVE), MAPPER, id,
                userEmail));
    }

    @Override
    public List<AssistantEditGroupDocument> findByProjectIdAndTargetPathAndStatus(String projectId, String targetPath,
                                                                                 AssistantEditGroupStatus status) {
        return EditorSql.query(jdbc, select("project_id = ? and target_path = ? and status = ?" + LIVE)
                + " order by created_at asc nulls first, id", MAPPER, projectId, targetPath,
                status == null ? null : status.name());
    }

    @Override
    public boolean resolveConsistencyCheck(String id, String jobId, ConsistencyCheckState state, String detail,
                                           Instant resolvedAt) {
        return EditorSql.update(jdbc, "update assistant_edit_group set check_job_id = ?, check_state = ?, "
                + "check_detail = ?, check_resolved_at = ? where id = ? "
                + "and (check_state is null or check_state = 'PENDING')", jobId, state.name(), detail,
                Instants.toDb(resolvedAt), id) > 0;
    }

    private static String select(String where) {
        return "select " + COLUMNS + " from assistant_edit_group where " + where;
    }

    private static AssistantEditGroupDocument read(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        String checkState = rs.getString("check_state");
        String checkJob = rs.getString("check_job_id");
        ConsistencyCheckRecord check = null;
        if (checkState != null || checkJob != null) {
            check = ConsistencyCheckRecord.builder()
                    .jobId(checkJob)
                    .state(checkState == null ? null : ConsistencyCheckState.valueOf(checkState))
                    .detail(rs.getString("check_detail"))
                    .resolvedAt(Instants.read(rs, "check_resolved_at"))
                    .build();
        }
        return AssistantEditGroupDocument.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .projectId(rs.getString("project_id"))
                .userEmail(rs.getString("user_email"))
                .clientGroupId(rs.getString("client_group_id"))
                .targetPath(rs.getString("target_path"))
                .draft(rs.getBoolean("draft"))
                .draftUserId(rs.getString("draft_user_id"))
                .edits(EditorSql.readJson(rs, "edits", EDITS))
                .status(status == null ? null : AssistantEditGroupStatus.valueOf(status))
                .publicGraphVersionAtPropose(EditorSql.readLong(rs, "public_graph_version_at_propose"))
                .positionsVerifiedAtVersion(EditorSql.readLong(rs, "positions_verified_at_version"))
                .appliedRevision(EditorSql.readLong(rs, "applied_revision"))
                .staleReason(rs.getString("stale_reason"))
                .createdAt(Instants.read(rs, "created_at"))
                .updatedAt(Instants.read(rs, "updated_at"))
                .expiresAt(Instants.read(rs, "expires_at"))
                .appliedAt(Instants.read(rs, "applied_at"))
                .consistencyCheck(check)
                .build();
    }
}
