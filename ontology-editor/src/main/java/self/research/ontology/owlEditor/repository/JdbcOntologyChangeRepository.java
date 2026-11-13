package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.OntologyChange.ChangeType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcOntologyChangeRepository implements OntologyChangeRepository {

    private static final String[] COLUMN_LIST = {"id", "project_id", "user_id", "username", "changed_at", "change_type",
            "change_category", "entity_iri", "entity_label", "old_value", "new_value", "axiom_before", "axiom_after",
            "description", "comment", "metadata", "reverted", "reverted_by", "reverted_at", "session_id", "ip_address"};

    private static final String COLUMNS = String.join(", ", COLUMN_LIST);

    private static final SqlUpsert UPSERT = SqlUpsert.into("ontology_change").key("id").columns(COLUMN_LIST).build();

    private static final String NEWEST_FIRST = " order by changed_at desc nulls last, id desc";

    private static final RowMapper<OntologyChange> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;

    public JdbcOntologyChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OntologyChange save(OntologyChange change) {
        if (change.getId() == null || change.getId().isBlank()) {
            change.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), args(change));
        return change;
    }

    @Override
    public Optional<OntologyChange> findById(String id) {
        return EditorSql.first(jdbc.query(select("id = ?"), MAPPER, id));
    }

    @Override
    public List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId) {
        return jdbc.query(select("project_id = ?") + NEWEST_FIRST, MAPPER, projectId);
    }

    @Override
    public List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId, Pageable pageable) {
        return jdbc.query(select("project_id = ?") + NEWEST_FIRST + page(pageable), MAPPER, projectId);
    }

    @Override
    public List<OntologyChange> findByUserIdOrderByTimestampDesc(String userId) {
        return jdbc.query(select("user_id = ?") + NEWEST_FIRST, MAPPER, userId);
    }

    @Override
    public List<OntologyChange> findByProjectIdAndEntityIRIOrderByTimestampDesc(String projectId, String entityIRI) {
        return jdbc.query(select("project_id = ? and entity_iri = ?") + NEWEST_FIRST, MAPPER, projectId, entityIRI);
    }

    @Override
    public List<OntologyChange> findByProjectIdAndChangeTypeOrderByTimestampDesc(String projectId, ChangeType changeType) {
        return jdbc.query(select("project_id = ? and change_type = ?") + NEWEST_FIRST, MAPPER, projectId,
                changeType == null ? null : changeType.name());
    }

    @Override
    public List<OntologyChange> findByProjectIdAndTimestampBetweenOrderByTimestampDesc(String projectId,
                                                                                       LocalDateTime start,
                                                                                       LocalDateTime end) {
        return jdbc.query(select("project_id = ? and changed_at > ? and changed_at < ?") + NEWEST_FIRST, MAPPER,
                projectId, Instants.toDb(start), Instants.toDb(end));
    }

    @Override
    public List<OntologyChange> findBySessionIdOrderByTimestampDesc(String sessionId) {
        return jdbc.query(select("session_id = ?") + NEWEST_FIRST, MAPPER, sessionId);
    }

    @Override
    public List<OntologyChange> findRecentChanges(String projectId, Pageable pageable) {
        return jdbc.query(select("project_id = ? and reverted = false") + NEWEST_FIRST + page(pageable), MAPPER,
                projectId);
    }

    @Override
    public List<OntologyChange> findByProjectIdAndChangeCategoryOrderByTimestampDesc(String projectId, String category) {
        return jdbc.query(select("project_id = ? and change_category = ?") + NEWEST_FIRST, MAPPER, projectId, category);
    }

    @Override
    public List<OntologyChange> findByProjectIdAndRevertedTrueOrderByTimestampDesc(String projectId) {
        return jdbc.query(select("project_id = ? and reverted = true") + NEWEST_FIRST, MAPPER, projectId);
    }

    @Override
    public long countByProjectId(String projectId) {
        Long count = jdbc.queryForObject("select count(*) from ontology_change where project_id = ?", Long.class, projectId);
        return count == null ? 0 : count;
    }

    @Override
    public long countByProjectIdAndUserId(String projectId, String userId) {
        Long count = jdbc.queryForObject("select count(*) from ontology_change where project_id = ? and user_id = ?",
                Long.class, projectId, userId);
        return count == null ? 0 : count;
    }

    @Override
    public List<OntologyChange> findByProjectIdAndTimestampAfterOrderByTimestampDesc(String projectId, LocalDateTime after) {
        return jdbc.query(select("project_id = ? and changed_at > ?") + NEWEST_FIRST, MAPPER, projectId,
                Instants.toDb(after));
    }

    @Override
    public void deleteByProjectId(String projectId) {
        jdbc.update("delete from ontology_change where project_id = ?", projectId);
    }

    private static String select(String where) {
        return "select " + COLUMNS + " from ontology_change where " + where;
    }

    private static String page(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return "";
        }
        return " limit " + pageable.getPageSize() + " offset " + pageable.getOffset();
    }

    private static Object[] args(OntologyChange c) {
        return new Object[] {c.getId(), c.getProjectId(), c.getUserId(), c.getUsername(), Instants.toDb(c.getTimestamp()),
                c.getChangeType() == null ? null : c.getChangeType().name(), c.getChangeCategory(), c.getEntityIRI(),
                c.getEntityLabel(), c.getOldValue(), c.getNewValue(), c.getAxiomBefore(), c.getAxiomAfter(),
                c.getDescription(), c.getComment(), EditorSql.json(c.getMetadata()), c.isReverted(), c.getRevertedBy(),
                Instants.toDb(c.getRevertedAt()), c.getSessionId(), c.getIpAddress()};
    }

    private static OntologyChange read(ResultSet rs) throws SQLException {
        OntologyChange c = new OntologyChange();
        c.setId(rs.getString("id"));
        c.setProjectId(rs.getString("project_id"));
        c.setUserId(rs.getString("user_id"));
        c.setUsername(rs.getString("username"));
        c.setTimestamp(Instants.readLocal(rs, "changed_at"));
        String type = rs.getString("change_type");
        c.setChangeType(type == null ? null : ChangeType.valueOf(type));
        c.setChangeCategory(rs.getString("change_category"));
        c.setEntityIRI(rs.getString("entity_iri"));
        c.setEntityLabel(rs.getString("entity_label"));
        c.setOldValue(rs.getString("old_value"));
        c.setNewValue(rs.getString("new_value"));
        c.setAxiomBefore(rs.getString("axiom_before"));
        c.setAxiomAfter(rs.getString("axiom_after"));
        c.setDescription(rs.getString("description"));
        c.setComment(rs.getString("comment"));
        Map<String, String> metadata = EditorSql.readStringMap(rs, "metadata");
        c.setMetadata(metadata == null ? new HashMap<>() : new HashMap<>(metadata));
        c.setReverted(rs.getBoolean("reverted"));
        c.setRevertedBy(rs.getString("reverted_by"));
        c.setRevertedAt(Instants.readLocal(rs, "reverted_at"));
        c.setSessionId(rs.getString("session_id"));
        c.setIpAddress(rs.getString("ip_address"));
        return c;
    }
}
