package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.reasoner.model.ReasoningSnapshotRecord;
import self.research.ontology.reasoner.repository.ReasoningSnapshotRecordRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasoningSnapshotStoreTest {

    @Test
    void smallSnapshotIsSavedAndReadBack() {
        ReasoningSnapshotRecordRepository repository = mock(ReasoningSnapshotRecordRepository.class);
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(repository, 1_000_000, 72);

        store.save("P1", 3, 900, "HERMIT", Map.of("success", true, "consistent", true, "ontologyRevision", 3L,
                "classTree", List.of(Map.of("id", "owl:Thing"))));

        ArgumentCaptor<ReasoningSnapshotRecord> saved = ArgumentCaptor.forClass(ReasoningSnapshotRecord.class);
        verify(repository, timeout(5_000)).save(saved.capture());
        verify(repository, timeout(5_000)).deleteByProjectIdAndRevisionLessThan("P1", 3);
        assertEquals("P1|3|900|HERMIT", saved.getValue().getId());
        assertEquals(900, saved.getValue().getTripleCount());
        when(repository.findById("P1|3|900|HERMIT")).thenReturn(Optional.of(saved.getValue()));

        Map<String, Object> restored = store.find("P1", 3, 900, "HERMIT").orElseThrow();

        assertEquals(true, restored.get("consistent"));
        assertEquals(3L, restored.get("ontologyRevision"));
        assertEquals(List.of(Map.of("id", "owl:Thing")), restored.get("classTree"));
        store.shutdown();
    }

    @Test
    void snapshotOverTheSizeLimitIsNotSaved() {
        ReasoningSnapshotRecordRepository repository = mock(ReasoningSnapshotRecordRepository.class);
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(repository, 50, 72);

        store.save("P1", 3, 900, "HERMIT", Map.of("success", true, "message", "x".repeat(200)));

        verify(repository, after(300).never()).save(any());
        assertTrue(store.toRecord("P1", 3, 900, "HERMIT", Map.of("message", "x".repeat(200))).isEmpty());
        store.shutdown();
    }

    @Test
    void savedSnapshotOlderThanTheRetentionIsIgnored() {
        ReasoningSnapshotRecordRepository repository = mock(ReasoningSnapshotRecordRepository.class);
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(repository, 1_000_000, 72);
        ReasoningSnapshotRecord record = store.toRecord("P1", 3, 900, "HERMIT", Map.of("consistent", true)).orElseThrow();
        record.setUpdatedAt(Instant.now().minus(Duration.ofHours(73)));
        when(repository.findById("P1|3|900|HERMIT")).thenReturn(Optional.of(record));

        assertTrue(store.find("P1", 3, 900, "HERMIT").isEmpty());
        store.shutdown();
    }

    @Test
    void unreachableMongoIsSkippedForAWhileInsteadOfSlowingEveryLookup() {
        ReasoningSnapshotRecordRepository repository = mock(ReasoningSnapshotRecordRepository.class);
        when(repository.findById(anyString())).thenThrow(new IllegalStateException("Timed out waiting for a server"));
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(repository, 1_000_000, 72);

        assertTrue(store.find("P1", 3, 900, "HERMIT").isEmpty());
        assertTrue(store.find("P1", 3, 900, "HERMIT").isEmpty());
        store.save("P1", 3, 900, "HERMIT", Map.of("consistent", true));

        verify(repository, times(1)).findById(anyString());
        verify(repository, after(300).never()).save(any());
        store.shutdown();
    }

    @Test
    void revisionZeroOrAnUnknownTripleCountIsNeverSavedOrRead() {
        ReasoningSnapshotRecordRepository repository = mock(ReasoningSnapshotRecordRepository.class);
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(repository, 1_000_000, 72);

        store.save("P1", 0, 900, "HERMIT", Map.of("consistent", true));
        store.save("P1", 3, -1, "HERMIT", Map.of("consistent", true));

        assertTrue(store.find("P1", 0, 900, "HERMIT").isEmpty());
        assertTrue(store.find("P1", 3, -1, "HERMIT").isEmpty());
        verify(repository, after(300).never()).save(any());
        verify(repository, never()).findById(anyString());
        store.shutdown();
    }

    @Test
    void disabledStoreDoesNothing() {
        ReasoningSnapshotStore store = ReasoningSnapshotStore.disabled();

        store.save("P1", 3, 900, "HERMIT", Map.of("consistent", true));

        assertTrue(store.find("P1", 3, 900, "HERMIT").isEmpty());
        store.purgeOldSnapshots();
    }

    @Test
    void restartedWorkerServesTheSavedSnapshotWithoutReasoning() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotRecordRepository repository = mock(ReasoningSnapshotRecordRepository.class);
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(repository, 1_000_000, 72);
        ReasoningSnapshotService first = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor), store);
        Map<String, Object> built = first.snapshot("P1", "OPENLLET", null);
        ArgumentCaptor<ReasoningSnapshotRecord> saved = ArgumentCaptor.forClass(ReasoningSnapshotRecord.class);
        verify(repository, timeout(5_000)).save(saved.capture());
        String id = "P1|1|" + editor.tripleCount("P1") + "|OPENLLET";
        assertEquals(id, saved.getValue().getId());
        when(repository.findById(id)).thenReturn(Optional.of(saved.getValue()));

        OntologySessionService restartedSessions = SnapshotTestSupport.sessions(editor);
        ReasoningSnapshotService restarted = SnapshotTestSupport.snapshots(restartedSessions, store);
        Map<String, Object> served = restarted.snapshot("P1", "OPENLLET", null);
        ReasoningJobExecutor executor = new ReasoningJobExecutor(restartedSessions, restarted);
        Map<String, Object> hierarchy = executor.execute(self.research.ontology.reasoner.model.ReasoningJob.builder()
                .jobId("job-1")
                .jobType(self.research.ontology.reasoner.model.ReasoningJob.JobType.REASONER_HIERARCHY)
                .projectId("P1")
                .reasonerType("OPENLLET")
                .build());

        assertEquals(0, restarted.buildCount());
        verify(restartedSessions, never()).openSnapshotSession(any(), any(), any());
        assertEquals(true, served.get("cached"));
        assertEquals(1L, served.get("ontologyRevision"));
        assertEquals(built.get("classTreeVisited"), served.get("classTreeVisited"));
        assertEquals(built.get("totalClasses"), served.get("totalClasses"));
        assertEquals(1L, hierarchy.get("ontologyRevision"));
        assertEquals(built.get("classTreeVisited"), hierarchy.get("totalClasses"));
        verify(repository, times(2)).findById(id);
        store.shutdown();
    }

    @Test
    void workerKeepsWorkingWhenMongoIsDown() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        ReasoningSnapshotRecordRepository repository = mock(ReasoningSnapshotRecordRepository.class);
        when(repository.findById(anyString())).thenThrow(new IllegalStateException("Timed out waiting for a server"));
        when(repository.save(any())).thenThrow(new IllegalStateException("Timed out waiting for a server"));
        when(repository.deleteByProjectIdAndRevisionLessThan(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("Timed out waiting for a server"));
        ReasoningSnapshotStore store = new ReasoningSnapshotStore(repository, 1_000_000, 72);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(SnapshotTestSupport.sessions(editor), store);

        Map<String, Object> built = snapshots.snapshot("P1", "OPENLLET", null);
        Map<String, Object> again = snapshots.snapshot("P1", "OPENLLET", null);

        assertEquals(true, built.get("consistent"));
        assertEquals(true, again.get("cached"));
        assertEquals(1, snapshots.buildCount());
        store.shutdown();
    }
}
