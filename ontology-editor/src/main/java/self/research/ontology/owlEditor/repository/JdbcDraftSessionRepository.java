package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcDraftSessionRepository implements DraftSessionRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("draft_session")
            .key("id")
            .columns("id", "project_id", "user_id", "baseline_main_revision", "baseline_main_triple_count",
                    "baseline_at", "baseline_snapshot_path", "copy_status", "prefixes")
            .build();

    private static final RowMapper<DraftSession> MAPPER = (rs, i) -> {
        DraftSession session = new DraftSession();
        session.setId(rs.getString("id"));
        session.setProjectId(rs.getString("project_id"));
        session.setUserId(rs.getString("user_id"));
        session.setBaselineMainRevision(rs.getLong("baseline_main_revision"));
        session.setBaselineMainTripleCount(rs.getLong("baseline_main_triple_count"));
        session.setBaselineAt(Instants.readLocal(rs, "baseline_at"));
        session.setBaselineSnapshotPath(rs.getString("baseline_snapshot_path"));
        String status = rs.getString("copy_status");
        session.setCopyStatus(status == null ? null : DraftCopyStatus.valueOf(status));
        session.setPrefixes(EditorSql.readStringMap(rs, "prefixes"));
        return session;
    };

    private final JdbcTemplate jdbc;

    public JdbcDraftSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DraftSession save(DraftSession session) {
        boolean newRow = session.getId() == null || session.getId().isBlank();
        if (newRow) {
            session.setId(Ids.newObjectId());
        }
        String sql = newRow ? plainInsert() : UPSERT.sql();
        EditorSql.update(jdbc, sql, session.getId(), session.getProjectId(), session.getUserId(),
                session.getBaselineMainRevision(), session.getBaselineMainTripleCount(),
                Instants.toDb(session.getBaselineAt()), session.getBaselineSnapshotPath(),
                session.getCopyStatus() == null ? null : session.getCopyStatus().name(),
                EditorSql.json(session.getPrefixes()));
        return session;
    }

    private static String plainInsert() {
        return "insert into draft_session (id, project_id, user_id, baseline_main_revision, "
                + "baseline_main_triple_count, baseline_at, baseline_snapshot_path, copy_status, prefixes) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    @Override
    public Optional<DraftSession> findById(String id) {
        return EditorSql.first(jdbc.query("select * from draft_session where id = ?", MAPPER, id));
    }

    @Override
    public Optional<DraftSession> findByProjectIdAndUserId(String projectId, String userId) {
        return EditorSql.first(jdbc.query("select * from draft_session where project_id = ? and user_id = ?",
                MAPPER, projectId, userId));
    }

    @Override
    public List<DraftSession> findByProjectId(String projectId) {
        return jdbc.query("select * from draft_session where project_id = ? order by id", MAPPER, projectId);
    }

    @Override
    public void deleteByProjectIdAndUserId(String projectId, String userId) {
        jdbc.update("delete from draft_session where project_id = ? and user_id = ?", projectId, userId);
    }

    @Override
    public void delete(DraftSession session) {
        if (session.getId() != null) {
            jdbc.update("delete from draft_session where id = ?", session.getId());
        }
    }

    @Override
    public List<DraftSession> findByUserIdStartingWith(String userIdPrefix) {
        String escaped = userIdPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.query("select * from draft_session where user_id like ? escape '\\' order by id",
                MAPPER, escaped + "%");
    }
}
