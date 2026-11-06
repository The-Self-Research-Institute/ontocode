package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.RollbackAudit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcRollbackAuditRepository implements RollbackAuditRepository {

    private static final String[] COLUMN_LIST = {"id", "project_id", "history_change_id", "sub_change_id", "entity_iri",
            "entity_label", "predicate", "action", "reverted_by_user_id", "reverted_by_username", "reverted_at", "cascaded",
            "cascaded_sub_change_ids", "change_set_id", "direction", "change_ids", "skipped_count", "draft_records",
            "format"};

    private static final String COLUMNS = String.join(", ", COLUMN_LIST);

    private static final SqlUpsert UPSERT = SqlUpsert.into("rollback_audit").key("id").columns(COLUMN_LIST).build();

    private static final String NEWEST_FIRST = " order by reverted_at desc nulls last, id desc";

    private static final TypeReference<List<DraftChange>> DRAFT_RECORDS = new TypeReference<>() {
    };

    private static final RowMapper<RollbackAudit> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;

    public JdbcRollbackAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RollbackAudit save(RollbackAudit audit) {
        if (audit.getId() == null || audit.getId().isBlank()) {
            audit.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), args(audit));
        return audit;
    }

    @Override
    public Optional<RollbackAudit> findById(String id) {
        return EditorSql.first(jdbc.query(select("id = ?"), MAPPER, id));
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("delete from rollback_audit where id = ?", id);
    }

    @Override
    public void deleteByProjectId(String projectId) {
        jdbc.update("delete from rollback_audit where project_id = ?", projectId);
    }

    @Override
    public List<RollbackAudit> findByHistoryChangeId(String historyChangeId) {
        return jdbc.query(select("history_change_id = ?") + NEWEST_FIRST, MAPPER, historyChangeId);
    }

    @Override
    public Optional<RollbackAudit> findFirstByChangeSetIdAndDirectionOrderByRevertedAtDesc(String changeSetId,
                                                                                           String direction) {
        return EditorSql.first(jdbc.query(select("change_set_id = ? and direction = ?") + NEWEST_FIRST + " limit 1",
                MAPPER, changeSetId, direction));
    }

    @Override
    public List<RollbackAudit> findByChangeSetIdAndDirectionOrderByRevertedAtDesc(String changeSetId, String direction) {
        return jdbc.query(select("change_set_id = ? and direction = ?") + NEWEST_FIRST, MAPPER, changeSetId, direction);
    }

    @Override
    public Optional<RollbackAudit> findByHistoryChangeIdAndSubChangeId(String historyChangeId, String subChangeId) {
        return EditorSql.first(jdbc.query(select("history_change_id = ? and sub_change_id = ?") + NEWEST_FIRST
                + " limit 1", MAPPER, historyChangeId, subChangeId));
    }

    @Override
    public List<RollbackAudit> findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(
            String projectId, String direction, Collection<String> userIds, Pageable pageable) {
        String page = pageable == null || pageable.isUnpaged() ? ""
                : " limit " + pageable.getPageSize() + " offset " + pageable.getOffset();
        return EditorSql.query(jdbc, select("project_id = ? and direction = ? and reverted_by_user_id = any(?)")
                + NEWEST_FIRST + page, MAPPER, projectId, direction, userIds);
    }

    private static String select(String where) {
        return "select " + COLUMNS + " from rollback_audit where " + where;
    }

    private static Object[] args(RollbackAudit a) {
        return new Object[] {a.getId(), a.getProjectId(), a.getHistoryChangeId(), a.getSubChangeId(), a.getEntityIRI(),
                a.getEntityLabel(), a.getPredicate(), a.getAction(), a.getRevertedByUserId(), a.getRevertedByUsername(),
                Instants.toDb(a.getRevertedAt()), a.isCascaded(), a.getCascadedSubChangeIds(), a.getChangeSetId(),
                a.getDirection(), a.getChangeIds(), a.getSkippedCount(), EditorSql.json(a.getDraftRecords()),
                a.getFormat()};
    }

    private static RollbackAudit read(ResultSet rs) throws SQLException {
        RollbackAudit a = new RollbackAudit();
        a.setId(rs.getString("id"));
        a.setProjectId(rs.getString("project_id"));
        a.setHistoryChangeId(rs.getString("history_change_id"));
        a.setSubChangeId(rs.getString("sub_change_id"));
        a.setEntityIRI(rs.getString("entity_iri"));
        a.setEntityLabel(rs.getString("entity_label"));
        a.setPredicate(rs.getString("predicate"));
        a.setAction(rs.getString("action"));
        a.setRevertedByUserId(rs.getString("reverted_by_user_id"));
        a.setRevertedByUsername(rs.getString("reverted_by_username"));
        a.setRevertedAt(Instants.readLocal(rs, "reverted_at"));
        a.setCascaded(rs.getBoolean("cascaded"));
        a.setCascadedSubChangeIds(EditorSql.readList(rs, "cascaded_sub_change_ids"));
        a.setChangeSetId(rs.getString("change_set_id"));
        a.setDirection(rs.getString("direction"));
        a.setChangeIds(EditorSql.readList(rs, "change_ids"));
        a.setSkippedCount(rs.getInt("skipped_count"));
        a.setFormat(rs.getString("format"));
        List<DraftChange> records = "json".equals(a.getFormat()) ? EditorSql.readJson(rs, "draft_records", DRAFT_RECORDS) : null;
        a.setDraftRecords(records == null ? null : new ArrayList<>(records));
        return a;
    }
}
