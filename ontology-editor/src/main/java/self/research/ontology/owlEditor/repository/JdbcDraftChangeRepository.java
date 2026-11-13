package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.DraftChange;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcDraftChangeRepository implements DraftChangeRepository {

    static final int BATCH_SIZE = 1000;

    private static final String COLUMNS =
            "id, project_id, user_id, username, operation_type, operation_data, changed_at, session_id, applied";

    private static final SqlUpsert UPSERT = SqlUpsert.into("draft_change")
            .key("id")
            .columns("id", "project_id", "user_id", "username", "operation_type", "operation_data", "changed_at",
                    "session_id", "applied")
            .build();

    private static final RowMapper<DraftChange> MAPPER = (rs, i) -> DraftChangeRows.read(rs);

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcDraftChangeRepository(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public DraftChange save(DraftChange change) {
        assignId(change);
        EditorSql.update(jdbc, UPSERT.sql(), args(change));
        return change;
    }

    @Override
    public List<DraftChange> saveAll(List<DraftChange> changes) {
        if (changes.isEmpty()) {
            return new ArrayList<>();
        }
        List<Object[]> rows = new ArrayList<>(changes.size());
        for (DraftChange change : changes) {
            assignId(change);
            rows.add(args(change));
        }
        transactions.executeWithoutResult(status ->
                SqlLists.inChunks(rows, BATCH_SIZE, chunk -> EditorSql.batch(jdbc, UPSERT.sql(), chunk)));
        return new ArrayList<>(changes);
    }

    @Override
    public Optional<DraftChange> findById(String id) {
        return EditorSql.first(jdbc.query("select " + COLUMNS + " from draft_change where id = ?", MAPPER, id));
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("delete from draft_change where id = ?", id);
    }

    @Override
    public List<DraftChange> findByProjectIdAndAppliedFalseOrderByTimestampAsc(String projectId) {
        return jdbc.query("select " + COLUMNS + " from draft_change where project_id = ? and applied = false "
                + "order by changed_at asc nulls first, id asc", MAPPER, projectId);
    }

    @Override
    public List<DraftChange> findByProjectIdOrderByTimestampDesc(String projectId) {
        return jdbc.query("select " + COLUMNS + " from draft_change where project_id = ? "
                + "order by changed_at desc nulls last, id desc", MAPPER, projectId);
    }

    @Override
    public List<DraftChange> findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(String projectId, String userId) {
        return jdbc.query("select " + COLUMNS + " from draft_change where project_id = ? and user_id = ? "
                + "and applied = false order by changed_at asc nulls first, id asc", MAPPER, projectId, userId);
    }

    @Override
    public List<DraftChange> findByProjectIdAndTimestampBetweenOrderByTimestampAsc(
            String projectId, LocalDateTime start, LocalDateTime end) {
        return jdbc.query("select " + COLUMNS + " from draft_change where project_id = ? and changed_at > ? "
                + "and changed_at < ? order by changed_at asc, id asc", MAPPER, projectId, Instants.toDb(start),
                Instants.toDb(end));
    }

    @Override
    public void deleteByProjectId(String projectId) {
        jdbc.update("delete from draft_change where project_id = ?", projectId);
    }

    @Override
    public void deleteByProjectIdAndAppliedTrue(String projectId) {
        jdbc.update("delete from draft_change where project_id = ? and applied = true", projectId);
    }

    @Override
    public long countByProjectIdAndAppliedFalse(String projectId) {
        Long count = jdbc.queryForObject(
                "select count(*) from draft_change where project_id = ? and applied = false", Long.class, projectId);
        return count == null ? 0 : count;
    }

    @Override
    public long deleteByAppliedFalseAndTimestampBefore(LocalDateTime cutoff) {
        return jdbc.update("delete from draft_change where applied = false and changed_at < ?", Instants.toDb(cutoff));
    }

    @Override
    public int parkPending(String projectId, String userId, String sessionId) {
        return move("draft_change", "draft_change_parked", projectId, userId, sessionId);
    }

    @Override
    public int restoreParked(String projectId, String userId, String sessionId) {
        return move("draft_change_parked", "draft_change", projectId, userId, sessionId);
    }

    @Override
    public int deletePending(String projectId, String userId, String sessionId) {
        return jdbc.update("delete from draft_change where project_id = ? and user_id = ? and session_id = ? "
                + "and applied = false", projectId, userId, sessionId);
    }

    private int move(String from, String to, String projectId, String userId, String sessionId) {
        Integer moved = jdbc.queryForObject("with moved as (delete from " + from
                + " where project_id = ? and user_id = ? and session_id = ? and applied = false returning " + COLUMNS + "), "
                + "inserted as (insert into " + to + " (" + COLUMNS + ") select " + COLUMNS + " from moved "
                + "on conflict (id) do update set project_id = excluded.project_id, user_id = excluded.user_id, "
                + "username = excluded.username, operation_type = excluded.operation_type, "
                + "operation_data = excluded.operation_data, changed_at = excluded.changed_at, "
                + "session_id = excluded.session_id, applied = excluded.applied returning 1) "
                + "select count(*)::int from inserted", Integer.class, projectId, userId, sessionId);
        return moved == null ? 0 : moved;
    }

    private static void assignId(DraftChange change) {
        if (change.getId() == null || change.getId().isBlank()) {
            change.setId(Ids.newObjectId());
        }
    }

    private static Object[] args(DraftChange change) {
        return new Object[] {change.getId(), change.getProjectId(), change.getUserId(), change.getUsername(),
                change.getOperationType(), EditorSql.json(change.getOperationData()),
                Instants.toDb(change.getTimestamp()), change.getSessionId(), change.isApplied()};
    }
}
