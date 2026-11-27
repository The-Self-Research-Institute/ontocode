package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MergeCheckpointTest {

    private static final String P = "proj";
    private static final String ID = "11111111-2222";

    @TempDir
    Path dir;

    private StorageManager storage;
    private ProjectImportService imports;
    private HistoryChangeRepository history;
    private MergeCheckpointRollbackActions actions;

    @BeforeEach
    void setUp() {
        storage = mock(StorageManager.class);
        when(storage.projectDir(P)).thenReturn(dir);
        imports = mock(ProjectImportService.class);
        history = mock(HistoryChangeRepository.class);
        actions = new MergeCheckpointRollbackActions(storage, mock(ProjectMetadataService.class),
                mock(OntologyMergeService.class), imports, history);
    }

    private static HistoryChange entry(String id, String op) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setOperationType(op);
        return change;
    }

    private static HistoryChange merge() {
        HistoryChange change = entry("m1", OntologyMergeService.MERGE_CHECKPOINT_OPERATION);
        change.setEntityIRI(OntologyMergeService.MERGE_IRI_PREFIX + ID);
        return change;
    }

    @Test
    void keepsBothSidesAndRestoresTheRequestedOne() throws Exception {
        MergeCheckpointStore store = new MergeCheckpointStore(storage);
        Path before = Files.writeString(dir.resolve("before.owl"), "BEFORE");
        Path after = Files.writeString(dir.resolve("after.owl"), "AFTER");
        store.saveBefore(P, ID, before);
        store.saveAfter(P, ID, after);
        Path target = dir.resolve("current.owl");
        store.restoreTo(P, ID, true, target);
        assertEquals("BEFORE", Files.readString(target));
        store.restoreTo(P, ID, false, target);
        assertEquals("AFTER", Files.readString(target));
    }

    @Test
    void aRestoreWaitsForAProgramThatHasTheProjectFileOpenThenFinishes() throws Exception {
        MergeCheckpointStore store = new MergeCheckpointStore(storage);
        store.saveBefore(P, ID, Files.writeString(dir.resolve("before.owl"), "BEFORE"));
        Path target = Files.writeString(dir.resolve("current.owl"), "CURRENT");
        FileInputStream lock = new FileInputStream(target.toFile());
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300);
                lock.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        store.restoreTo(P, ID, true, target);
        release.get(5, TimeUnit.SECONDS);

        assertEquals("BEFORE", Files.readString(target));
        assertFalse(Files.exists(dir.resolve("current.owl.restore")));
    }

    @Test
    void someoneReadingASavedCopyNeverSeesItHalfRewritten() throws Exception {
        MergeCheckpointStore store = new MergeCheckpointStore(storage);
        String old = "OLD".repeat(50_000);
        store.saveBefore(P, ID, Files.writeString(dir.resolve("first.owl"), old));
        Path checkpoints = dir.resolve("merge-checkpoints");
        Path saved;
        try (var files = Files.list(checkpoints)) {
            saved = files.findFirst().orElseThrow();
        }

        try (InputStream reader = Files.newInputStream(saved)) {
            store.saveBefore(P, ID, Files.writeString(dir.resolve("second.owl"), "NEW"));
            assertEquals(old, new String(new GZIPInputStream(reader).readAllBytes(), StandardCharsets.UTF_8));
        }
        Path target = dir.resolve("current.owl");
        store.restoreTo(P, ID, true, target);
        assertEquals("NEW", Files.readString(target));
        try (var files = Files.list(checkpoints)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void reportsAMissingCopy() {
        assertFalse(new MergeCheckpointStore(storage).exists(P, ID, true));
    }

    @Test
    void allowsRestoreOnlyWhenNothingElseChangedSinceTheMerge() throws Exception {
        new MergeCheckpointStore(storage).saveBefore(P, ID, Files.writeString(dir.resolve("b.owl"), "B"));
        RollbackAction undo = RollbackAction.restoreMergeFile(merge(), true);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                entry("imp", "projectImported"), entry("rb", "ROLLBACK_x"), merge()));
        assertTrue(actions.holds(P, undo, false, null));

        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                entry("edit", "createClass"), merge()));
        assertFalse(actions.holds(P, undo, false, null));
    }

    @Test
    void allowsRestoreOnceTheLaterEditsHaveBeenUndone() throws Exception {
        new MergeCheckpointStore(storage).saveBefore(P, ID, Files.writeString(dir.resolve("b.owl"), "B"));
        HistoryChange later = entry("edit", "createClass");
        later.setReverted(true);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                entry("rb", "ROLLBACK_x"), later, merge()));
        assertTrue(actions.holds(P, RollbackAction.restoreMergeFile(merge(), true), false, null));
    }

    @Test
    void refusesWhileTheImportAfterTheMergeIsStillRunning() throws Exception {
        new MergeCheckpointStore(storage).saveBefore(P, ID, Files.writeString(dir.resolve("b.owl"), "B"));
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(merge()));
        when(imports.isImportActiveOrQueued(P)).thenReturn(true);
        assertFalse(actions.holds(P, RollbackAction.restoreMergeFile(merge(), true), false, null));
    }

    @Test
    void aMergeCheckpointEntryCanBeUndone() {
        assertTrue(UndoAbility.of(merge()).undoable());
        assertEquals(Optional.of(RollbackMutationPlanner.Kind.MERGE), Optional.of(RollbackMutationPlanner.kindOf(merge())));
    }
}
