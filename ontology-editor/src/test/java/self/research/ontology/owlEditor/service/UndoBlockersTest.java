package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UndoBlockersTest {

    private static final String P = "proj";

    private HistoryChangeRepository history;
    private ProjectImportService imports;
    private UndoBlockers blockers;

    @BeforeEach
    void setUp() {
        history = mock(HistoryChangeRepository.class);
        imports = mock(ProjectImportService.class);
        blockers = new UndoBlockers(history, imports);
    }

    private static HistoryChange change(String id, String op, String label, String user) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setOperationType(op);
        change.setEntityLabel(label);
        change.setUsername(user);
        return change;
    }

    @Test
    void namesTheLaterChangesThatAreStillInEffect() {
        HistoryChange merge = change("m", "ontologyMerge", "Ontology merge", "kim");
        HistoryChange undone = change("u", "createClass", "Ghost", "sam");
        undone.setReverted(true);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                change("i", "projectImported", "File", null),
                change("r", "ROLLBACK_ENTRY", "x", "kim"),
                undone,
                change("e2", "updateClass", "Margherita", "kim"),
                change("e1", "createClass", "Pizza", "sam"),
                merge,
                change("old", "createClass", "Older", "sam")));
        assertEquals(List.of("Margherita (kim)", "Pizza (sam)"), blockers.laterChanges(P, merge));
    }

    @Test
    void keepsTheOriginalWordingWhenNothingLaterIsInEffect() {
        HistoryChange merge = change("m", "ontologyMerge", "Ontology merge", "kim");
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(merge));
        assertEquals("plain", blockers.changedSince(P, merge, "plain"));
    }

    @Test
    void namesAtMostThree() {
        HistoryChange merge = change("m", "ontologyMerge", "Ontology merge", "kim");
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                change("a", "createClass", "A", "x"), change("b", "createClass", "B", "x"),
                change("c", "createClass", "C", "x"), change("d", "createClass", "D", "x"), merge));
        assertEquals(3, blockers.laterChanges(P, merge).size());
        assertTrue(blockers.changedSince(P, merge, "plain").startsWith("Later changes come first."));
    }

    @Test
    void reportsARunningImport() {
        when(imports.isImportActiveOrQueued(P)).thenReturn(true);
        assertTrue(blockers.importRunning(P));
    }
}
