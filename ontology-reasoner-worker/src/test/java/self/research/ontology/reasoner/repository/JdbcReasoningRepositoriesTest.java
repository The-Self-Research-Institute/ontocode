package self.research.ontology.reasoner.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.reasoner.TestDatabase;

class JdbcReasoningRepositoriesTest extends ReasoningRepositoriesContract {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcReasoningJobRecordRepository jobs = new JdbcReasoningJobRecordRepository(jdbc);
    private final JdbcReasoningSnapshotRecordRepository snapshots = new JdbcReasoningSnapshotRecordRepository(jdbc);

    @Override
    ReasoningJobRecordRepository jobs() {
        return jobs;
    }

    @Override
    ReasoningSnapshotRecordRepository snapshots() {
        return snapshots;
    }

    @Override
    JdbcTemplate jdbc() {
        return jdbc;
    }
}
