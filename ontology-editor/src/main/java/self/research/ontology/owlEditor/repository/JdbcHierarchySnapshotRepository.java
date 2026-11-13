package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;

import java.util.Date;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcHierarchySnapshotRepository implements HierarchySnapshotRepository {

    private static final String LIVE = "(expires_at is null or expires_at > now())";

    private static final String META_COLUMNS = "project_id, status, algorithm_version, revision, built_at, "
            + "error_message, top_level_total, top_level_computed_limit, meta, expires_at";

    private static final String UPSERT = "insert into hierarchy_snapshot (project_id, status, algorithm_version, "
            + "revision, built_at, error_message, top_level_total, top_level_computed_limit, meta, payload, "
            + "expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "on conflict (project_id) do update set status = excluded.status, "
            + "algorithm_version = excluded.algorithm_version, revision = excluded.revision, "
            + "built_at = excluded.built_at, error_message = excluded.error_message, "
            + "top_level_total = excluded.top_level_total, "
            + "top_level_computed_limit = excluded.top_level_computed_limit, meta = excluded.meta, "
            + "payload = coalesce(excluded.payload, hierarchy_snapshot.payload), "
            + "expires_at = excluded.expires_at";

    private static final RowMapper<HierarchySnapshotDoc> MAPPER = (rs, i) -> {
        HierarchySnapshotDoc doc = new HierarchySnapshotDoc();
        doc.setProjectId(rs.getString("project_id"));
        doc.setStatus(HierarchySnapshotDoc.Status.valueOf(rs.getString("status")));
        doc.setAlgorithmVersion(rs.getString("algorithm_version"));
        doc.setRevision(rs.getString("revision"));
        doc.setBuiltAt(rs.getLong("built_at"));
        doc.setErrorMessage(rs.getString("error_message"));
        doc.setTopLevelTotal(rs.getInt("top_level_total"));
        doc.setTopLevelComputedLimit(rs.getInt("top_level_computed_limit"));
        Map<String, Object> meta = EditorSql.readMap(rs, "meta");
        doc.setMeta(meta);
        doc.setExpiresAt(Instants.read(rs, "expires_at") == null ? null : Date.from(Instants.read(rs, "expires_at")));
        return doc;
    };

    private final JdbcTemplate jdbc;

    public JdbcHierarchySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<HierarchySnapshotDoc> findById(String projectId) {
        return EditorSql.first(EditorSql.query(jdbc,
                "select " + META_COLUMNS + " from hierarchy_snapshot where project_id = ? and " + LIVE,
                MAPPER, projectId));
    }

    @Override
    public Optional<Stored> findWithPayload(String projectId) {
        return EditorSql.first(EditorSql.query(jdbc,
                "select * from hierarchy_snapshot where project_id = ? and " + LIVE,
                (rs, i) -> new Stored(MAPPER.mapRow(rs, i), rs.getString("payload")), projectId));
    }

    @Override
    public void save(HierarchySnapshotDoc doc, String payloadJson) {
        EditorSql.update(jdbc, UPSERT, doc.getProjectId(), doc.getStatus().name(), doc.getAlgorithmVersion(),
                doc.getRevision(), doc.getBuiltAt(), doc.getErrorMessage(), doc.getTopLevelTotal(),
                doc.getTopLevelComputedLimit(), EditorSql.json(doc.getMeta()), payloadJson,
                doc.getExpiresAt() == null ? null : Instants.toDb(doc.getExpiresAt().toInstant()));
    }

    @Override
    public void deleteById(String projectId) {
        jdbc.update("delete from hierarchy_snapshot where project_id = ?", projectId);
    }
}
