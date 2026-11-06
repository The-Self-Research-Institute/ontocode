package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftPrChange;

import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcDraftPrChangeRepository implements DraftPrChangeRepository {

    private static final String INSERT = "insert into draft_pr_change (id, pr_id, project_id, snapshot_key, "
            + "change_id, change_user_id, change_username, change_type, change_data, change_at, change_session_id, "
            + "change_applied, has_change) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "on conflict (id) do update set pr_id = excluded.pr_id, project_id = excluded.project_id, "
            + "snapshot_key = excluded.snapshot_key, change_id = excluded.change_id, "
            + "change_user_id = excluded.change_user_id, change_username = excluded.change_username, "
            + "change_type = excluded.change_type, change_data = excluded.change_data, "
            + "change_at = excluded.change_at, change_session_id = excluded.change_session_id, "
            + "change_applied = excluded.change_applied, has_change = excluded.has_change";

    private static final RowMapper<DraftPrChange> MAPPER = (rs, i) -> {
        DraftChange change = null;
        if (rs.getBoolean("has_change")) {
            change = DraftChangeRows.read(rs, "change_id", "change_user_id", "change_username", "change_type",
                    "change_data", "change_at", "change_session_id", "change_applied");
            change.setProjectId(rs.getString("project_id"));
        }
        DraftPrChange row = new DraftPrChange(rs.getString("project_id"), rs.getString("pr_id"),
                rs.getString("snapshot_key"), change);
        row.setId(rs.getString("id"));
        return row;
    };

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcDraftPrChangeRepository(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<DraftPrChange> saveAll(List<DraftPrChange> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (DraftPrChange row : rows) {
            if (row.getId() == null || row.getId().isBlank()) {
                row.setId(Ids.newObjectId());
            }
            DraftChange change = row.getChange();
            args.add(new Object[] {row.getId(), row.getPrId(), row.getProjectId(), row.getSnapshotKey(),
                    change == null ? null : change.getId(), change == null ? null : change.getUserId(),
                    change == null ? null : change.getUsername(), change == null ? null : change.getOperationType(),
                    EditorSql.json(change == null ? null : change.getOperationData()),
                    change == null ? null : Instants.toDb(change.getTimestamp()),
                    change == null ? null : change.getSessionId(), change != null && change.isApplied(),
                    change != null});
        }
        transactions.executeWithoutResult(status ->
                SqlLists.inChunks(args, JdbcDraftChangeRepository.BATCH_SIZE, chunk -> EditorSql.batch(jdbc, INSERT, chunk)));
        return new ArrayList<>(rows);
    }

    @Override
    public List<DraftPrChange> findByPrId(String prId) {
        return jdbc.query("select * from draft_pr_change where pr_id = ? order by seq", MAPPER, prId);
    }

    @Override
    public List<DraftPrChange> findByPrIdAndSnapshotKey(String prId, String snapshotKey) {
        return jdbc.query("select * from draft_pr_change where pr_id = ? and snapshot_key is not distinct from ? "
                + "order by seq", MAPPER, prId, snapshotKey);
    }

    @Override
    public void deleteByPrIdAndSnapshotKey(String prId, String snapshotKey) {
        jdbc.update("delete from draft_pr_change where pr_id = ? and snapshot_key is not distinct from ?",
                prId, snapshotKey);
    }

    @Override
    public void deleteByPrId(String prId) {
        jdbc.update("delete from draft_pr_change where pr_id = ?", prId);
    }
}
