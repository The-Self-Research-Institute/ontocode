package self.research.ontology.reasoner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import self.research.ontology.reasoner.model.ReasoningJobRecord;
import self.research.ontology.reasoner.repository.JdbcReasoningJobRecordRepository;
import self.research.ontology.reasoner.repository.JdbcReasoningSnapshotRecordRepository;
import self.research.ontology.reasoner.repository.ReasoningJobRecordRepository;
import self.research.ontology.reasoner.repository.ReasoningSnapshotRecordRepository;
import self.research.ontology.reasoner.service.ReasoningJobStore;
import self.research.ontology.reasoner.service.ReasoningSnapshotStore;

import javax.sql.DataSource;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = ReasonerWorkerApplication.class)
class DatabaseWiringTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DataSource ds = TestDatabase.dataSource();
        registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl());
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    ReasoningJobRecordRepository jobs;

    @Autowired
    ReasoningSnapshotRecordRepository snapshots;

    @Autowired
    ReasoningJobStore jobStore;

    @Autowired
    ReasoningSnapshotStore snapshotStore;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theApplicationStartsWithTheDatabaseRepositoriesAndStoresWired() {
        assertInstanceOf(JdbcReasoningJobRecordRepository.class, jobs);
        assertInstanceOf(JdbcReasoningSnapshotRecordRepository.class, snapshots);
        assertNotNull(jobStore);
        assertNotNull(snapshotStore);
    }

    @Test
    void theSchemaMigrationRanAndTheTablesWork() {
        Integer applied = jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class);
        assertEquals(true, applied != null && applied >= 2);

        jobs.save(ReasoningJobRecord.builder().jobId("wired").status("COMPLETED").updatedAt(Instant.now()).build());
        assertEquals("COMPLETED", jobs.findById("wired").orElseThrow().getStatus());
    }
}
