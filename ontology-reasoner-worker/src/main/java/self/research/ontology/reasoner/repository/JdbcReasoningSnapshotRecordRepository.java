package self.research.ontology.reasoner.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.reasoner.model.ReasoningSnapshotRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcReasoningSnapshotRecordRepository implements ReasoningSnapshotRecordRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("reasoning_snapshot")
            .key("id")
            .columns("id", "project_id", "revision", "triple_count", "reasoner", "rules_fingerprint",
                    "result_json", "created_at", "updated_at")
            .build();

    private static final RowMapper<ReasoningSnapshotRecord> MAPPER = (rs, i) -> ReasoningSnapshotRecord.builder()
            .id(rs.getString("id"))
            .projectId(rs.getString("project_id"))
            .revision(rs.getLong("revision"))
            .tripleCount(rs.getLong("triple_count"))
            .reasoner(rs.getString("reasoner"))
            .rulesFingerprint(rs.getString("rules_fingerprint"))
            .resultJson(rs.getString("result_json"))
            .createdAt(Instants.read(rs, "created_at"))
            .updatedAt(Instants.read(rs, "updated_at"))
            .build();

    private final JdbcTemplate jdbc;

    public JdbcReasoningSnapshotRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReasoningSnapshotRecord save(ReasoningSnapshotRecord r) {
        jdbc.update(UPSERT.sql(), r.getId(), r.getProjectId(), r.getRevision(), r.getTripleCount(), r.getReasoner(),
                r.getRulesFingerprint(), r.getResultJson(), Instants.toDb(r.getCreatedAt()),
                Instants.toDb(r.getUpdatedAt() != null ? r.getUpdatedAt() : Instant.now()));
        return r;
    }

    @Override
    public Optional<ReasoningSnapshotRecord> findById(String id) {
        List<ReasoningSnapshotRecord> rows = jdbc.query("select * from reasoning_snapshot where id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public long deleteByUpdatedAtBefore(Instant cutoff) {
        return jdbc.update("delete from reasoning_snapshot where updated_at < ?", Instants.toDb(cutoff));
    }

    @Override
    public long deleteByProjectIdAndRevisionLessThan(String projectId, long revision) {
        return jdbc.update("delete from reasoning_snapshot where project_id = ? and revision < ?", projectId, revision);
    }
}
