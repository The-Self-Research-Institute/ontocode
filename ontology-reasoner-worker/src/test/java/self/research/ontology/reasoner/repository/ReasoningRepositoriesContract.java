package self.research.ontology.reasoner.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.reasoner.model.ReasoningJobRecord;
import self.research.ontology.reasoner.model.ReasoningSnapshotRecord;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class ReasoningRepositoriesContract {

    abstract ReasoningJobRecordRepository jobs();

    abstract ReasoningSnapshotRecordRepository snapshots();

    abstract JdbcTemplate jdbc();

    @BeforeEach
    void clean() {
        jdbc().update("delete from reasoning_job");
        jdbc().update("delete from reasoning_snapshot");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static ReasoningJobRecord job(String id, String status, Instant updatedAt) {
        return ReasoningJobRecord.builder()
                .jobId(id).jobType("REASONER").projectId("p1").status(status)
                .queuedAt(updatedAt.minusSeconds(10)).startedAt(updatedAt.minusSeconds(5)).completedAt(updatedAt)
                .updatedAt(updatedAt).executionTimeMs(1234).error("e").resultJson("{\"ok\":true}").resultTooLarge(false)
                .build();
    }

    @Test
    void aJobRoundTripsEveryField() {
        Instant t = now();
        ReasoningJobRecord saved = job("j1", "COMPLETED", t);
        jobs().save(saved);

        ReasoningJobRecord loaded = jobs().findById("j1").orElseThrow();

        assertEquals(saved, loaded);
        assertEquals(Optional.empty(), jobs().findById("missing"));
    }

    @Test
    void savingTheSameJobAgainReplacesTheWholeRecordIncludingFieldsSetBackToNull() {
        Instant t = now();
        jobs().save(job("j1", "COMPLETED", t));

        ReasoningJobRecord second = ReasoningJobRecord.builder()
                .jobId("j1").jobType("DL_QUERY").projectId("p2").status("FAILED").updatedAt(t.plusSeconds(1))
                .executionTimeMs(5).resultTooLarge(true).build();
        jobs().save(second);

        ReasoningJobRecord loaded = jobs().findById("j1").orElseThrow();
        assertEquals(second, loaded);
        assertNull(loaded.getError());
        assertNull(loaded.getResultJson());
        assertNull(loaded.getQueuedAt());
        assertEquals(1, jdbc().queryForObject("select count(*) from reasoning_job", Integer.class));
    }

    @Test
    void saveAllWritesEveryRecordAndHandlesAnEmptyList() {
        Instant t = now();
        assertEquals(List.of(), jobs().saveAll(List.of()));

        jobs().saveAll(List.of(job("a", "QUEUED", t), job("b", "PROCESSING", t), job("c", "COMPLETED", t)));

        assertEquals(3, jdbc().queryForObject("select count(*) from reasoning_job", Integer.class));
    }

    @Test
    void findByStatusInReturnsOnlyMatchingRecordsAndNothingForEmptyInput() {
        Instant t = now();
        jobs().saveAll(List.of(job("a", "QUEUED", t), job("b", "PROCESSING", t), job("c", "COMPLETED", t), job("d", "FAILED", t)));

        List<String> ids = jobs().findByStatusIn(List.of("QUEUED", "PROCESSING")).stream().map(ReasoningJobRecord::getJobId).sorted().toList();

        assertEquals(List.of("a", "b"), ids);
        assertEquals(List.of(), jobs().findByStatusIn(List.of()));
        assertEquals(List.of(), jobs().findByStatusIn(List.of("NO_SUCH_STATUS")));
    }

    @Test
    void deleteByUpdatedAtBeforeRemovesOnlyOlderRecordsAndReturnsTheCount() {
        Instant t = now();
        jobs().saveAll(List.of(job("old1", "COMPLETED", t.minusSeconds(7200)), job("old2", "FAILED", t.minusSeconds(7300)),
                job("new", "COMPLETED", t)));

        long removed = jobs().deleteByUpdatedAtBefore(t.minusSeconds(3600));

        assertEquals(2, removed);
        assertTrue(jobs().findById("new").isPresent());
        assertFalse(jobs().findById("old1").isPresent());
    }

    @Test
    void aMegabyteOfResultTextIsStoredAndReadBackIntact() {
        String big = "x".repeat(1_000_000);
        ReasoningJobRecord record = job("big", "COMPLETED", now());
        record.setResultJson(big);
        jobs().save(record);

        assertEquals(big, jobs().findById("big").orElseThrow().getResultJson());
    }

    @Test
    void manyThreadsSavingTheSameJobNeverFailAndLeaveOneRow() throws Exception {
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                for (int k = 0; k < 20; k++) {
                    jobs().save(job("same", "PROCESSING", now().plusMillis(n * 100L + k)));
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, jdbc().queryForObject("select count(*) from reasoning_job where job_id = 'same'", Integer.class));
    }

    private static ReasoningSnapshotRecord snapshot(String project, long revision, Instant updatedAt) {
        return ReasoningSnapshotRecord.builder()
                .id(project + "|" + revision + "|900|HERMIT|none").projectId(project).revision(revision).tripleCount(900)
                .reasoner("HERMIT").rulesFingerprint("none").resultJson("{\"consistent\":true}")
                .createdAt(updatedAt).updatedAt(updatedAt).build();
    }

    @Test
    void aSnapshotRoundTripsAndUsesTheCompositeTextIdUnchanged() {
        ReasoningSnapshotRecord saved = snapshot("P1", 3, now());
        snapshots().save(saved);

        assertEquals(saved, snapshots().findById("P1|3|900|HERMIT|none").orElseThrow());
        assertEquals(Optional.empty(), snapshots().findById("P1|4|900|HERMIT|none"));
    }

    @Test
    void savingTheSameSnapshotTwiceKeepsOneRowWithTheLatestContent() {
        Instant t = now();
        snapshots().save(snapshot("P1", 3, t));
        ReasoningSnapshotRecord changed = snapshot("P1", 3, t.plusSeconds(5));
        changed.setResultJson("{\"consistent\":false}");
        snapshots().save(changed);

        assertEquals(1, jdbc().queryForObject("select count(*) from reasoning_snapshot", Integer.class));
        assertEquals("{\"consistent\":false}", snapshots().findById(changed.getId()).orElseThrow().getResultJson());
    }

    @Test
    void deleteByProjectIdAndRevisionLessThanKeepsNewerRevisionsAndOtherProjects() {
        Instant t = now();
        snapshots().save(snapshot("P1", 1, t));
        snapshots().save(snapshot("P1", 2, t));
        snapshots().save(snapshot("P1", 3, t));
        snapshots().save(snapshot("P2", 1, t));

        long removed = snapshots().deleteByProjectIdAndRevisionLessThan("P1", 3);

        assertEquals(2, removed);
        assertTrue(snapshots().findById("P1|3|900|HERMIT|none").isPresent());
        assertTrue(snapshots().findById("P2|1|900|HERMIT|none").isPresent());
    }

    @Test
    void deleteSnapshotsByUpdatedAtBeforeReturnsTheCount() {
        Instant t = now();
        snapshots().save(snapshot("P1", 1, t.minusSeconds(9000)));
        snapshots().save(snapshot("P2", 1, t));

        assertEquals(1, snapshots().deleteByUpdatedAtBefore(t.minusSeconds(3600)));
        assertEquals(1, jdbc().queryForObject("select count(*) from reasoning_snapshot", Integer.class));
    }
}
