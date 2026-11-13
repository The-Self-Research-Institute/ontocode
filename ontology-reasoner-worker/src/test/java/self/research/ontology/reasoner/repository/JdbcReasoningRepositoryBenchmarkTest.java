package self.research.ontology.reasoner.repository;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.reasoner.TestDatabase;

@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class JdbcReasoningRepositoryBenchmarkTest extends ReasoningRepositoryBenchmarkBase {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcReasoningJobRecordRepository jobs = new JdbcReasoningJobRecordRepository(jdbc);

    @Override
    ReasoningJobRecordRepository jobs() {
        return jobs;
    }

    @Override
    JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    String label() {
        return "plain SQL";
    }
}
