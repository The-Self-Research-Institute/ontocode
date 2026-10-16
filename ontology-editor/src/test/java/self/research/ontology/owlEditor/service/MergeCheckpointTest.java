package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
