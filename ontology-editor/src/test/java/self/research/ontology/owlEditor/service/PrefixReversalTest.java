package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrefixReversalTest {

    private static final String P = "proj";
    private static final Actor ACTOR = new Actor("u@x.com", "u@x.com");

    private HistorySyncService historySync;
    private RollbackActionRunner runner;
    private ChangeRollbackService service;

    @BeforeEach
    void setUp() {
        historySync = mock(HistorySyncService.class);
        runner = mock(RollbackActionRunner.class);
        when(runner.holds(anyString(), any(), anyBoolean(), any())).thenReturn(true);
        RollbackAuditRepository audits = mock(RollbackAuditRepository.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            audit.setId("audit-1");
            return audit;
        });
        service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), mock(RollbackGraphFacts.class),
                mock(OntologyMutationService.class), audits, mock(OntologyHistoryService.class),
                new ProjectWriteLockRegistry(), mock(DraftTrackingService.class), null);
        ReflectionTestUtils.setField(service, "actionRunner", runner);
    }

    private static HistoryChange prefix(String type, String prefix, String oldPrefix, String oldNs, String newNs) {
        HistoryChange change = new HistoryChange();
        change.setId("c1");
        change.setProjectId(P);
        change.setUserId("u@x.com");
        change.setOperationType(type);
        change.setEntityLabel(prefix);
        change.setOldValue(oldNs);
        change.setNewValue(newNs);
        change.setAnnotationProperty(oldPrefix);
        return change;
    }

    private Result undo(HistoryChange change) {
        when(historySync.getHistoryChange("c1")).thenReturn(change);
        return service.undoEntry(P, "c1", ACTOR, false);
    }

    private static HistoryChange aggregate() {
        HistoryChange change = prefix("prefixesChanged", "3 prefixes", null, null, null);
        change.setSubChanges(new ArrayList<>(List.of(
                part("prefixAdded", "a", null, "http://a/"),
                part("prefixModified", "b", "http://old-b/", "http://new-b/"),
                part("prefixDeleted", "c", "http://c/", null))));
        return change;
    }

    private static HistoryChange.SubChange part(String type, String prefix, String oldNs, String newNs) {
        HistoryChange.SubChange part = new HistoryChange.SubChange(prefix, oldNs, newNs, type, newNs != null);
        part.setId("sc-" + prefix);
        return part;
    }

    @Test
    void undoingSeveralPrefixesSavedTogetherReversesEachOneNewestFirst() {
        Result result = undo(aggregate());

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(runner);
        order.verify(runner).run(eq(P), eq(RollbackAction.setPrefix("c", "http://c/", null, null, true)), eq(false), any());
        order.verify(runner).run(eq(P), eq(RollbackAction.setPrefix("b", "http://old-b/", null, "http://new-b/", false)),
                eq(false), any());
        order.verify(runner).run(eq(P), eq(RollbackAction.deletePrefix("a", "http://a/")), eq(false), any());
        verify(historySync).setSubChangesReverted(eq("c1"), org.mockito.ArgumentMatchers.argThat(
                ids -> ids.size() == 3 && ids.containsAll(List.of("sc-a", "sc-b", "sc-c"))), eq(true), eq("audit-1"));
        assertEquals(3, result.applied().size());
    }

    @Test
    void onePrefixOfAGroupSavedTogetherCannotBeUndoneOnItsOwn() {
        when(historySync.getHistoryChange("c1")).thenReturn(aggregate());

        Result result = service.undoSubChange(P, "c1", "sc-a", ACTOR, false);

        assertFalse(result.ok());
        verify(runner, never()).run(anyString(), any(), anyBoolean(), any());
    }

    @Test
    void undoingAnAddedPrefixDeletesIt() {
        Result result = undo(prefix("prefixAdded", "ex", null, null, "http://ex/"));
        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.deletePrefix("ex", "http://ex/")), eq(false), eq("u@x.com"));
        verify(historySync).setEntryReverted("c1", true, "audit-1");
    }

    @Test
    void undoingADeletedPrefixPutsTheNamespaceBack() {
        Result result = undo(prefix("prefixDeleted", "ex", null, "http://ex/", null));
        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.setPrefix("ex", "http://ex/", null, null, true)), eq(false), any());
    }

    @Test
    void undoingANamespaceChangeRestoresTheOldNamespace() {
        Result result = undo(prefix("prefixModified", "ex", null, "http://old/", "http://new/"));
        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.setPrefix("ex", "http://old/", null, "http://new/", false)),
                eq(false), any());
    }

    @Test
    void undoingARenameRenamesItBack() {
        Result result = undo(prefix("prefixModified", "new", "old", "http://ex/", "http://ex/"));
        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.setPrefix("old", "http://ex/", "new", "http://ex/", false)),
                eq(false), any());
    }

    @Test
    void redoingARenameAppliesItAgain() {
        HistoryChange change = prefix("prefixModified", "new", "old", "http://ex/", "http://ex/");
        change.setReverted(true);
        change.setRevertedAuditId("undo-1");
        RollbackAudit undo = new RollbackAudit();
        undo.setId("undo-1");
        undo.setProjectId(P);
        undo.setDirection("UNDO");
        undo.setHistoryChangeId("c1");
        undo.setChangeIds(List.of("c1"));
        RollbackAuditRepository audits = mock(RollbackAuditRepository.class);
        when(audits.findById("undo-1")).thenReturn(Optional.of(undo));
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            audit.setId("audit-2");
            return audit;
        });
        ChangeRollbackService redoService = new ChangeRollbackService(historySync, new RollbackMutationPlanner(),
                mock(RollbackGraphFacts.class), mock(OntologyMutationService.class), audits,
                mock(OntologyHistoryService.class), new ProjectWriteLockRegistry(), mock(DraftTrackingService.class), null);
        ReflectionTestUtils.setField(redoService, "actionRunner", runner);
        when(historySync.getHistoryChange("c1")).thenReturn(change);

        Result result = redoService.redoUndo(P, "undo-1", ACTOR, false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.setPrefix("new", "http://ex/", "old", "http://ex/", false)), eq(false), any());
        verify(historySync).setEntryReverted("c1", false, null);
    }

    @Test
    void aPrefixChangedByAnotherEditIsNotOverwritten() {
        when(runner.holds(anyString(), any(), anyBoolean(), any())).thenReturn(false);
        Result result = undo(prefix("prefixModified", "ex", null, "http://old/", "http://new/"));
        assertFalse(result.ok());
        assertEquals("It was changed since this edit", result.skipped().get(0).reason());
        verify(runner, never()).run(anyString(), any(), anyBoolean(), any());
    }

    @Test
    void aModificationWithoutTheOldNamespaceCannotBeReversed() {
        Result result = undo(prefix("prefixModified", "ex", null, null, "http://new/"));
        assertFalse(result.ok());
        verify(runner, never()).run(anyString(), any(), anyBoolean(), any());
    }

    @Test
    void withoutARunnerTheUndoFailsCleanly() {
        ReflectionTestUtils.setField(service, "actionRunner", null);
        Result result = undo(prefix("prefixAdded", "ex", null, null, "http://ex/"));
        assertFalse(result.ok());
        assertTrue(result.message() == null || !result.message().isBlank());
    }

    @Test
    void theRunnerSuppressesHistoryWhileItWritesAndCallsTheRightMethod() {
        OntologyMetadataService metadata = mock(OntologyMetadataService.class);
        List<Boolean> seen = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            seen.add(PrefixRollbackActions.historySuppressed());
            return null;
        }).when(metadata).updatePrefix(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any());
        PrefixRollbackActions actions = new PrefixRollbackActions(metadata);

        actions.run(P, RollbackAction.setPrefix("ex", "http://ex/", "old", null, false), false, "u@x.com");

        assertEquals(List.of(true), seen);
        assertFalse(PrefixRollbackActions.historySuppressed());
        verify(metadata).updatePrefix(P, "ex", "http://ex/", "old", false, "u@x.com", "u@x.com", "u@x.com");
    }

    @Test
    void theRunnerComparesTheCurrentNamespaceBeforeChanging() {
        OntologyMetadataService metadata = mock(OntologyMetadataService.class);
        when(metadata.getPrefixes(P)).thenReturn(List.of(Map.of("prefix", "ex", "namespace", "http://new/")));
        PrefixRollbackActions actions = new PrefixRollbackActions(metadata);

        assertTrue(actions.holds(P, RollbackAction.setPrefix("ex", "http://old/", null, "http://new/", false), false, null));
        assertFalse(actions.holds(P, RollbackAction.setPrefix("ex", "http://old/", null, "http://other/", false), false, null));
        assertFalse(actions.holds(P, RollbackAction.setPrefix("ex", "http://x/", null, null, true), false, null));
        assertTrue(actions.holds(P, RollbackAction.setPrefix("zz", "http://x/", null, null, true), false, null));
        assertTrue(actions.holds(P, RollbackAction.deletePrefix("ex", "http://new/"), false, null));
        assertTrue(actions.holds(P, RollbackAction.deletePrefix("ex", "http://new/"), true, "u"));
    }

    @Test
    void theRunnerAlsoComparesDraftPrefixesAsTheDraftOwnerSeesThem() {
        OntologyMetadataService metadata = mock(OntologyMetadataService.class);
        List<String> seenUsers = new java.util.ArrayList<>();
        when(metadata.getPrefixes(P)).thenAnswer(inv -> {
            seenUsers.add(SparqlQueryContext.getUserId());
            return List.of(Map.of("prefix", "ex", "namespace", "http://new/"));
        });
        PrefixRollbackActions actions = new PrefixRollbackActions(metadata);
        SparqlQueryContext.setUserId("someone-else");

        assertTrue(actions.holds(P, RollbackAction.setPrefix("ex", "http://old/", null, "http://new/", false), true, "u"));
        assertFalse(actions.holds(P, RollbackAction.setPrefix("ex", "http://old/", null, "http://other/", false), true, "u"));
        assertEquals(List.of("u", "u"), seenUsers);
        assertEquals("someone-else", SparqlQueryContext.getUserId());
        SparqlQueryContext.setUserId(null);
    }
}
