package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.reasoner.TestDatabase;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.model.ReasoningJobRecord;
import self.research.ontology.reasoner.model.ReasoningSnapshotRecord;
import self.research.ontology.reasoner.repository.JdbcReasoningJobRecordRepository;
import self.research.ontology.reasoner.repository.JdbcReasoningSnapshotRecordRepository;
import self.research.ontology.reasoner.repository.ReasoningSnapshotRecordRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoresOnRealDatabaseTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcReasoningJobRecordRepository jobs = new JdbcReasoningJobRecordRepository(jdbc);
    private final JdbcReasoningSnapshotRecordRepository snapshots = new JdbcReasoningSnapshotRecordRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from reasoning_job");
        jdbc.update("delete from reasoning_snapshot");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 10 s");
    }

    @Test
    void jobsLeftUnfinishedByAnEarlierRunAreMarkedFailedOnStartup() {
        Instant earlier = Instant.now().minusSeconds(3600);
        jobs.save(ReasoningJobRecord.builder().jobId("stuck").status("PROCESSING").queuedAt(earlier).updatedAt(earlier).build());
        jobs.save(ReasoningJobRecord.builder().jobId("done").status("COMPLETED").queuedAt(earlier).updatedAt(earlier).build());

        ReasoningJobStore store = new ReasoningJobStore(jobs, 1_000_000, 72);

        assertEquals(1, store.recoverInterruptedJobs());
        assertEquals("FAILED", jobs.findById("stuck").orElseThrow().getStatus());
        assertEquals(ReasoningJobStore.RESTART_MESSAGE, jobs.findById("stuck").orElseThrow().getError());
        assertEquals("COMPLETED", jobs.findById("done").orElseThrow().getStatus());
        store.shutdown();
    }

    @Test
    void aSavedJobIsReadBackWithItsResult() throws Exception {
        ReasoningJobStore store = new ReasoningJobStore(jobs, 1_000_000, 72);
        ReasoningJob job = ReasoningJob.builder().jobId("j1").jobType(ReasoningJob.JobType.REASONER_RUN).projectId("p1")
                .status(ReasoningJob.Status.COMPLETED).queuedAt(Instant.now()).result(Map.of("success", true)).build();

        store.save(job);
        waitUntil(() -> store.find("j1").isPresent());

        ReasoningJob loaded = store.find("j1").orElseThrow();
        assertEquals(ReasoningJob.Status.COMPLETED, loaded.getStatus());
        assertEquals(true, loaded.getResult().get("success"));
        store.shutdown();
    }

    @Test
    void savingANewerSnapshotRemovesOlderRevisionsOfTheSameProject() throws Exception {
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(snapshots, TestDatabase.transactions(), 1_000_000, 72);

        store.save("P1", 3, 900, "HERMIT", "none", Map.of("consistent", true));
        waitUntil(() -> store.find("P1", 3, 900, "HERMIT", "none").isPresent());
        store.save("P1", 4, 900, "HERMIT", "none", Map.of("consistent", true));
        waitUntil(() -> store.find("P1", 4, 900, "HERMIT", "none").isPresent());

        assertEquals(Optional.empty(), snapshots.findById("P1|3|900|HERMIT|none"));
        assertTrue(snapshots.findById("P1|4|900|HERMIT|none").isPresent());
        store.shutdown();
    }

    @Test
    void whenPruningFailsTheNewSnapshotIsNotKeptEither() throws Exception {
        ReasoningSnapshotRecordRepository failingPrune = new ReasoningSnapshotRecordRepository() {
            @Override
            public ReasoningSnapshotRecord save(ReasoningSnapshotRecord record) {
                return snapshots.save(record);
            }

            @Override
            public Optional<ReasoningSnapshotRecord> findById(String id) {
                return snapshots.findById(id);
            }

            @Override
            public long deleteByUpdatedAtBefore(Instant cutoff) {
                return snapshots.deleteByUpdatedAtBefore(cutoff);
            }

            @Override
            public long deleteByProjectIdAndRevisionLessThan(String projectId, long revision) {
                throw new IllegalStateException("boom");
            }
        };
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(failingPrune, TestDatabase.transactions(), 1_000_000, 72);

        store.save("P1", 5, 900, "HERMIT", "none", Map.of("consistent", true));
        Thread.sleep(700);

        assertEquals(0, jdbc.queryForObject("select count(*) from reasoning_snapshot", Integer.class));
        store.shutdown();
    }
}
