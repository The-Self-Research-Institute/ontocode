package self.research.ontology.reasoner.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.reasoner.model.ReasoningJobRecord;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

abstract class ReasoningRepositoryBenchmarkBase {

    abstract ReasoningJobRecordRepository jobs();

    abstract JdbcTemplate jdbc();

    abstract String label();

    private static List<ReasoningJobRecord> records(String prefix, int count, Instant updatedAt) {
        List<ReasoningJobRecord> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(ReasoningJobRecord.builder()
                    .jobId(prefix + i).jobType("REASONER_RUN").projectId("p" + (i % 20))
                    .status(new String[]{"QUEUED", "PROCESSING", "COMPLETED", "FAILED"}[i % 4])
                    .queuedAt(updatedAt).startedAt(updatedAt).completedAt(updatedAt).updatedAt(updatedAt)
                    .executionTimeMs(i).error(null).resultJson("{\"n\":" + i + ",\"pad\":\"" + "x".repeat(120) + "\"}")
                    .resultTooLarge(false).build());
        }
        return list;
    }

    private void report(String op, long startNanos, String extra) {
        System.out.println("BENCH " + label() + " | " + op + " | " + (System.nanoTime() - startNanos) / 1_000_000 + " ms" + extra);
    }

    @Test
    void measure() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc().update("delete from reasoning_job");

        List<ReasoningJobRecord> single = records("single-", 2000, t);
        long s = System.nanoTime();
        for (ReasoningJobRecord r : single) {
            jobs().save(r);
        }
        report("2,000 single saves (new rows)", s, "");

        List<ReasoningJobRecord> bulk = records("bulk-", 20000, t);
        s = System.nanoTime();
        jobs().saveAll(bulk);
        report("saveAll 20,000 new rows", s, "");

        s = System.nanoTime();
        jobs().saveAll(bulk);
        report("saveAll 20,000 existing rows (update)", s, "");

        s = System.nanoTime();
        List<ReasoningJobRecord> found = jobs().findByStatusIn(List.of("QUEUED", "PROCESSING"));
        report("findByStatusIn on 22,000 rows", s, " (" + found.size() + " returned)");

        s = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            jobs().findById("bulk-" + i);
        }
        report("2,000 findById", s, "");

        s = System.nanoTime();
        long removed = jobs().deleteByUpdatedAtBefore(t.plusSeconds(1));
        report("deleteByUpdatedAtBefore", s, " (" + removed + " removed)");
    }
}
