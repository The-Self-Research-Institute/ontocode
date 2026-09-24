package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.repository.AssistantApplyOperationRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantApplyOperationServiceTest {

    @TempDir
    Path dataDir;

    private AssistantApplyOperationRepository repository;
    private StorageManager storageManager;
    private AssistantApplyOperationService service;
    private Path exportFile;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(AssistantApplyOperationRepository.class);
        storageManager = mock(StorageManager.class);
        service = new AssistantApplyOperationService(repository, storageManager, dataDir.toString());
        exportFile = dataDir.resolve("ontology.original.owl");
        Files.writeString(exportFile, "<rdf:RDF>pre-apply</rdf:RDF>", StandardCharsets.UTF_8);
        when(storageManager.exportOntology("proj-1", "rdfxml")).thenReturn(exportFile);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AssistantEditGroupDocument group() {
        return AssistantEditGroupDocument.builder().id("g1").sessionId("s1").projectId("proj-1")
                .targetPath("turtle").build();
    }

    @Test
    void prepareKeepsADurableCopyOfThePreApplyExportAndRecordsItAsPrepared() throws Exception {
        AssistantApplyOperationDocument operation = service.prepare(group(), "u@x.com");

        assertEquals(ApplyOperationStatus.PREPARED, operation.getStatus());
        assertEquals("proj-1", operation.getProjectId());
        assertEquals("g1", operation.getGroupId());
        assertEquals("s1", operation.getSessionId());
        assertEquals("u@x.com", operation.getActor());
        Path snapshot = Path.of(operation.getSnapshotPath());
        assertTrue(snapshot.startsWith(service.snapshotDir()));
        assertEquals("<rdf:RDF>pre-apply</rdf:RDF>", Files.readString(snapshot));
        Files.writeString(exportFile, "overwritten by a later export");
        assertEquals("<rdf:RDF>pre-apply</rdf:RDF>", Files.readString(snapshot));
        assertTrue(operation.isUnresolved());
    }

    @Test
    void prepareDeletesTheSnapshotWhenTheRecordCannotBeSaved() throws Exception {
        when(repository.save(any())).thenThrow(new IllegalStateException("mongo down"));

        assertThrows(IllegalStateException.class, () -> service.prepare(group(), "u@x.com"));

        try (var files = Files.list(service.snapshotDir())) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void prepareFailsWhenTheExportProducedNothing() throws Exception {
        when(storageManager.exportOntology("proj-1", "rdfxml")).thenReturn(dataDir.resolve("missing.owl"));

        assertThrows(IOException.class, () -> service.prepare(group(), "u@x.com"));
    }

    @Test
    void committedOperationIsResolvedAndItsSnapshotDeleted() throws Exception {
        AssistantApplyOperationDocument operation = service.prepare(group(), "u@x.com");
        service.markImporting(operation);
        assertEquals(ApplyOperationStatus.GRAPH_IMPORTING, operation.getStatus());

        service.markCommitted(operation);

        assertEquals(ApplyOperationStatus.COMMITTED, operation.getStatus());
        assertNotNull(operation.getResolvedAt());
        assertFalse(operation.isUnresolved());
        assertFalse(Files.exists(Path.of(operation.getSnapshotPath())));
    }

    @Test
    void rolledBackOperationIsResolvedAndItsSnapshotDeleted() throws Exception {
        AssistantApplyOperationDocument operation = service.prepare(group(), "u@x.com");

        service.markRolledBack(operation, "GraphDB timed out", "AUTOMATIC_ROLLBACK");

        assertEquals(ApplyOperationStatus.ROLLED_BACK, operation.getStatus());
        assertEquals("GraphDB timed out", operation.getFailureReason());
        assertEquals("AUTOMATIC_ROLLBACK", operation.getResolution());
        assertFalse(operation.isUnresolved());
        assertFalse(service.snapshotExists(operation));
    }

    @Test
    void failedAwaitingRecoveryKeepsTheSnapshotAndStaysUnresolved() throws Exception {
        AssistantApplyOperationDocument operation = service.prepare(group(), "u@x.com");

        service.markFailedAwaitingRecovery(operation, "rollback failed");

        assertEquals(ApplyOperationStatus.FAILED, operation.getStatus());
        assertNull(operation.getResolvedAt());
        assertTrue(operation.isUnresolved());
        assertTrue(service.snapshotExists(operation));
    }

    @Test
    void clearedOperationIsResolvedAndItsSnapshotDeleted() throws Exception {
        AssistantApplyOperationDocument operation = service.prepare(group(), "u@x.com");
        service.markFailedAwaitingRecovery(operation, "rollback failed");

        service.markClearedByUser(operation);

        assertEquals(ApplyOperationStatus.FAILED, operation.getStatus());
        assertEquals("CLEARED", operation.getResolution());
        assertEquals("rollback failed", operation.getFailureReason());
        assertFalse(operation.isUnresolved());
        assertFalse(service.snapshotExists(operation));
    }

    @Test
    void findUnresolvedForGroupOnlyReturnsTheLatestOperationWhenItIsUnresolved() {
        AssistantApplyOperationDocument committed = AssistantApplyOperationDocument.builder().id("op-2")
                .status(ApplyOperationStatus.COMMITTED).resolvedAt(Instant.now()).build();
        AssistantApplyOperationDocument importing = AssistantApplyOperationDocument.builder().id("op-3")
                .status(ApplyOperationStatus.GRAPH_IMPORTING).build();
        when(repository.findFirstByGroupIdOrderByCreatedAtDesc("g-committed")).thenReturn(Optional.of(committed));
        when(repository.findFirstByGroupIdOrderByCreatedAtDesc("g-importing")).thenReturn(Optional.of(importing));
        when(repository.findFirstByGroupIdOrderByCreatedAtDesc("g-none")).thenReturn(Optional.empty());

        assertTrue(service.findUnresolvedForGroup("g-committed").isEmpty());
        assertEquals("op-3", service.findUnresolvedForGroup("g-importing").orElseThrow().getId());
        assertTrue(service.findUnresolvedForGroup("g-none").isEmpty());
    }

    @Test
    void findUnresolvedForProjectSkipsResolvedFailures() {
        AssistantApplyOperationDocument awaiting = AssistantApplyOperationDocument.builder().id("op-a")
                .status(ApplyOperationStatus.FAILED).build();
        AssistantApplyOperationDocument cleared = AssistantApplyOperationDocument.builder().id("op-b")
                .status(ApplyOperationStatus.FAILED).resolvedAt(Instant.now()).build();
        when(repository.findByProjectIdAndStatusIn(any(), any())).thenReturn(List.of(awaiting, cleared));

        List<AssistantApplyOperationDocument> unresolved = service.findUnresolvedForProject("proj-1");

        assertEquals(1, unresolved.size());
        assertEquals("op-a", unresolved.get(0).getId());
    }

    @Test
    void sweepRemovesOnlySnapshotsThatNoUnresolvedOperationReferences() throws Exception {
        Files.createDirectories(service.snapshotDir());
        Path keep = Files.writeString(service.snapshotDir().resolve("op-keep.owl"), "x");
        Path orphan = Files.writeString(service.snapshotDir().resolve("op-orphan.owl"), "x");
        Path unrelated = Files.writeString(service.snapshotDir().resolve("notes.txt"), "x");

        int removed = service.sweepOrphanSnapshots(Set.of("op-keep"));

        assertEquals(1, removed);
        assertTrue(Files.exists(keep));
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void pruneDeletesOnlyFinishedOperationsPastTheCutoff() {
        when(repository.deleteByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(4L);

        long pruned = service.pruneFinishedOlderThan(java.time.Duration.ofDays(30));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ApplyOperationStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByStatusInAndUpdatedAtBefore(statuses.capture(), cutoff.capture());
        assertEquals(4L, pruned);
        assertEquals(Set.of(ApplyOperationStatus.COMMITTED, ApplyOperationStatus.ROLLED_BACK), Set.copyOf(statuses.getValue()));
        assertTrue(cutoff.getValue().isBefore(Instant.now().minus(java.time.Duration.ofDays(29))));
    }
}
