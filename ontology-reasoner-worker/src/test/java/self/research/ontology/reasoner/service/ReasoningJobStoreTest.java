package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.model.ReasoningJobRecord;
import self.research.ontology.reasoner.repository.ReasoningJobRecordRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasoningJobStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void startupMarksJobsLeftQueuedOrRunningAsFailed() {
        ReasoningJobRecordRepository repository = mock(ReasoningJobRecordRepository.class);
        ReasoningJobRecord queued = record("job-1", "QUEUED", Instant.now().minusSeconds(60));
        ReasoningJobRecord running = record("job-2", "PROCESSING", Instant.now().minusSeconds(30));
        when(repository.findByStatusIn(any())).thenReturn(List.of(queued, running));
        ReasoningJobStore store = new ReasoningJobStore(repository, 1_000_000, 72);

        int recovered = store.recoverInterruptedJobs();

        assertEquals(2, recovered);
        ArgumentCaptor<Iterable<ReasoningJobRecord>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(saved.capture());
        for (ReasoningJobRecord record : saved.getValue()) {
            assertEquals("FAILED", record.getStatus());
            assertEquals(ReasoningJobStore.RESTART_MESSAGE, record.getError());
            assertTrue(record.getCompletedAt() != null);
        }
    }

    @Test
    void startupLeavesJobsQueuedByThisRunAlone() {
        ReasoningJobRecordRepository repository = mock(ReasoningJobRecordRepository.class);
        ReasoningJobStore store = new ReasoningJobStore(repository, 1_000_000, 72);
        when(repository.findByStatusIn(any())).thenReturn(List.of(
                record("job-new", "QUEUED", Instant.now().plusSeconds(5))));

        assertEquals(0, store.recoverInterruptedJobs());
        verify(repository, never()).saveAll(anyIterable());
    }

    @Test
    void unreachableMongoDoesNotBreakStartupOrLookups() {
        ReasoningJobRecordRepository repository = mock(ReasoningJobRecordRepository.class);
        when(repository.findByStatusIn(any())).thenThrow(new IllegalStateException("Timed out waiting for a server"));
        ReasoningJobStore store = new ReasoningJobStore(repository, 1_000_000, 72);

        assertEquals(0, store.recoverInterruptedJobs());
        assertTrue(store.find("job-1").isEmpty());
        verify(repository, never()).findById(any());
    }

    @Test
    void storedJobComesBackWithItsResult() {
        ReasoningJobRecordRepository repository = mock(ReasoningJobRecordRepository.class);
        ReasoningJobStore store = new ReasoningJobStore(repository, 1_000_000, 72);
        ReasoningJobRecord record = store.toRecord(completedJob(Map.of("success", true, "totalClasses", 12)));
        when(repository.findById("job-1")).thenReturn(Optional.of(record));

        ReasoningJob job = store.find("job-1").orElseThrow();

        assertEquals(ReasoningJob.Status.COMPLETED, job.getStatus());
        assertEquals(ReasoningJob.JobType.REASONER_CLASSIFY, job.getJobType());
        assertEquals(12, job.getResult().get("totalClasses"));
    }

    @Test
    void oversizedResultIsReplacedByAMarkerAndReportedAsUnavailable() {
        ReasoningJobRecordRepository repository = mock(ReasoningJobRecordRepository.class);
        ReasoningJobStore store = new ReasoningJobStore(repository, 50, 72);

        ReasoningJobRecord record = store.toRecord(completedJob(Map.of("success", true, "message", "x".repeat(200))));

        assertTrue(record.isResultTooLarge());
        assertNull(record.getResultJson());
        ReasoningJob restored = store.toJob(record);
        assertEquals(ReasoningJob.Status.FAILED, restored.getStatus());
        assertEquals(ReasoningJobStore.RESULT_TOO_LARGE_MESSAGE, restored.getError());
        assertEquals(ReasoningJobStore.RESULT_TOO_LARGE_MESSAGE, ReasoningFriendlyErrors.forUser(restored.getError()));
        assertEquals(ReasoningJobStore.RESTART_MESSAGE, ReasoningFriendlyErrors.forUser(ReasoningJobStore.RESTART_MESSAGE));
    }

    @Test
    void disabledStoreDoesNothing() {
        ReasoningJobStore store = ReasoningJobStore.disabled();

        store.save(completedJob(Map.of("success", true)));

        assertEquals(0, store.recoverInterruptedJobs());
        assertFalse(store.find("job-1").isPresent());
    }

    private static ReasoningJobRecord record(String jobId, String status, Instant queuedAt) {
        return ReasoningJobRecord.builder()
                .jobId(jobId)
                .jobType("REASONER_CLASSIFY")
                .projectId("P1")
                .status(status)
                .queuedAt(queuedAt)
                .build();
    }

    private static ReasoningJob completedJob(Map<String, Object> result) {
        return ReasoningJob.builder()
                .jobId("job-1")
                .jobType(ReasoningJob.JobType.REASONER_CLASSIFY)
                .projectId("P1")
                .status(ReasoningJob.Status.COMPLETED)
                .queuedAt(Instant.now())
                .completedAt(Instant.now())
                .result(result)
                .build();
    }
}
