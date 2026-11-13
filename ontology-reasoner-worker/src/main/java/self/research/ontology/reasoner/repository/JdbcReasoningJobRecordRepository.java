package self.research.ontology.reasoner.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.reasoner.model.ReasoningJobRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcReasoningJobRecordRepository implements ReasoningJobRecordRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("reasoning_job")
            .key("job_id")
            .columns("job_id", "job_type", "project_id", "status", "queued_at", "started_at", "completed_at",
                    "updated_at", "execution_time_ms", "error", "result_json", "result_too_large")
            .build();

    private static final RowMapper<ReasoningJobRecord> MAPPER = (rs, i) -> ReasoningJobRecord.builder()
            .jobId(rs.getString("job_id"))
            .jobType(rs.getString("job_type"))
            .projectId(rs.getString("project_id"))
            .status(rs.getString("status"))
            .queuedAt(Instants.read(rs, "queued_at"))
            .startedAt(Instants.read(rs, "started_at"))
            .completedAt(Instants.read(rs, "completed_at"))
            .updatedAt(Instants.read(rs, "updated_at"))
            .executionTimeMs(rs.getLong("execution_time_ms"))
            .error(rs.getString("error"))
            .resultJson(rs.getString("result_json"))
            .resultTooLarge(rs.getBoolean("result_too_large"))
            .build();

    private final JdbcTemplate jdbc;

    public JdbcReasoningJobRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReasoningJobRecord save(ReasoningJobRecord record) {
        jdbc.update(UPSERT.sql(), params(record));
        return record;
    }

    @Override
    public List<ReasoningJobRecord> saveAll(Iterable<ReasoningJobRecord> records) {
        List<ReasoningJobRecord> saved = new ArrayList<>();
        List<Object[]> batch = new ArrayList<>();
        for (ReasoningJobRecord record : records) {
            saved.add(record);
            batch.add(params(record));
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(UPSERT.sql(), batch);
        }
        return saved;
    }

    @Override
    public Optional<ReasoningJobRecord> findById(String jobId) {
        List<ReasoningJobRecord> rows = jdbc.query("select * from reasoning_job where job_id = ?", MAPPER, jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ReasoningJobRecord> findByStatusIn(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from reasoning_job where status in (" + SqlLists.placeholders(statuses.size()) + ")",
                MAPPER, statuses.toArray());
    }

    @Override
    public long deleteByUpdatedAtBefore(Instant cutoff) {
        return jdbc.update("delete from reasoning_job where updated_at < ?", Instants.toDb(cutoff));
    }

    private static Object[] params(ReasoningJobRecord r) {
        return new Object[]{
                r.getJobId(), r.getJobType(), r.getProjectId(), r.getStatus(),
                Instants.toDb(r.getQueuedAt()), Instants.toDb(r.getStartedAt()), Instants.toDb(r.getCompletedAt()),
                Instants.toDb(r.getUpdatedAt() != null ? r.getUpdatedAt() : Instant.now()),
                r.getExecutionTimeMs(), r.getError(), r.getResultJson(), r.isResultTooLarge()
        };
    }
}
