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
        change.setUserId(user);
        return change;
    }

    private static HistoryChange draftRow(String id, String op, String label, String userId, boolean draft) {
        HistoryChange change = change(id, op, label, userId);
        change.setDraft(draft);
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
        assertEquals(List.of("Margherita (kim)", "Pizza (sam)"), blockers.laterChanges(P, merge, null));
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
        assertEquals(3, blockers.laterChanges(P, merge, null).size());
        assertTrue(blockers.changedSince(P, merge, "plain").startsWith("Other people's changes are on top of this one:"));
    }

    @Test
    void keepsTheOrderWordingWhenTheLaterChangesAreTheSamePersons() {
        HistoryChange merge = change("m", "ontologyMerge", "Ontology merge", "kim");
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                change("a", "createClass", "A", "kim"), merge));
        assertEquals("Later changes come first. Undo these before this one: A (kim)",
                blockers.changedSince(P, merge, "plain"));
    }

    @Test
    void tellsTheUserToUndoByHandWhenSomeoneElseChangedAfterwards() {
        HistoryChange merge = change("m", "ontologyMerge", "Ontology merge", "kim");
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                change("a", "ontologyMerge", "Ontology merge", "sam"), merge));
        assertEquals("Other people's changes are on top of this one: Ontology merge (sam). Undo this edit by hand instead",
                blockers.changedSince(P, merge, "plain"));
    }

    @Test
    void aDraftUndoIsNotBlockedByPublishedChangesOfOthers() {
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", true);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                draftRow("p2", "ontologyMerge", "Ontology merge", "sam", false),
                draftRow("p1", "ontologyMerge", "Ontology merge", "sam", false), mine));
        assertEquals(List.of(), blockers.laterChanges(P, mine, null));
        assertEquals("plain", blockers.changedSince(P, mine, "plain"));
    }

    @Test
    void aDraftUndoIsBlockedByLaterDraftChangesOfTheSamePerson() {
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", true);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                draftRow("d", "createClass", "Later", "owner-id", true), mine));
        assertEquals("Later changes come first. Undo these before this one: Later (owner-id)",
                blockers.changedSince(P, mine, "plain"));
    }

    @Test
    void aDraftUndoIgnoresLaterDraftChangesOfOtherPeople() {
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", true);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                draftRow("d", "createClass", "Theirs", "sam", true), mine));
        assertEquals(List.of(), blockers.laterChanges(P, mine, null));
    }

    @Test
    void aPublicUndoIgnoresDraftChanges() {
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", false);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                draftRow("d", "createClass", "Private", "sam", true), mine));
        assertEquals(List.of(), blockers.laterChanges(P, mine, null));
    }

    @Test
    void aDraftChangeFromAnotherDraftInstanceDoesNotBlock() {
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", true);
        mine.setDraftSessionId("s2");
        HistoryChange older = draftRow("d", "createClass", "FromOldDraft", "owner-id", true);
        older.setDraftSessionId("s1");
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(older, mine));
        assertEquals(List.of(), blockers.laterChanges(P, mine, null));
    }

    @Test
    void aDraftChangeWithoutAnInstanceIdStillBlocksConservatively() {
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", true);
        mine.setDraftSessionId("s2");
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                draftRow("d", "createClass", "Legacy", "owner-id", true), mine));
        assertEquals(List.of("Legacy (owner-id)"), blockers.laterChanges(P, mine, null));
    }

    @Test
    void discardedDraftChangesNeverBlock() {
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", true);
        HistoryChange discarded = draftRow("d", "createClass", "Old", "owner-id", true);
        discarded.setDraftDiscarded(true);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(discarded, mine));
        assertEquals(List.of(), blockers.laterChanges(P, mine, null));
    }

    @Test
    void thePersonIsRecognisedUnderTheirAccountIdAndTheirEmail() {
        UserUndoService.Identity who = UserUndoService.Identity.of("owner@x.org", "owner-id");
        HistoryChange mine = draftRow("m", "ontologyMerge", "Ontology merge", "owner-id", false);
        when(history.findByProjectId(anyString(), any(Pageable.class))).thenReturn(List.of(
                draftRow("e", "createClass", "ByEmail", "owner@x.org", false), mine));
        assertEquals("Later changes come first. Undo these before this one: ByEmail (owner@x.org)",
                blockers.changedSince(P, mine, "plain", who));
    }

    @Test
    void reportsARunningImport() {
        when(imports.isImportActiveOrQueued(P)).thenReturn(true);
        assertTrue(blockers.importRunning(P));
    }
}
