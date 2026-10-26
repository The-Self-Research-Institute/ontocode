package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;
import self.research.ontology.owlEditor.service.UserUndoService.Next;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrefixUndoFlowTest {

    private static final String P = "proj";
    private static final String MONGO_ID = "66f0c0ffee";
    private static final String EMAIL = "me@x.com";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 16, 10, 0);

    private final Map<String, String> prefixes = new LinkedHashMap<>();
    private final Map<String, RollbackAudit> auditStore = new HashMap<>();
    private final List<HistoryChange> rows = new ArrayList<>();
    private final List<String> writes = new ArrayList<>();
    private UserUndoService undoService;
    private UndoPreconditions preconditions;
    private ChangeRollbackService rollback;

    @BeforeEach
    void setUp() {
        HistoryChangeRepository history = mock(HistoryChangeRepository.class);
        when(history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(anyString(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    Collection<String> users = inv.getArgument(2);
                    boolean draft = inv.getArgument(1);
                    return rows.stream().filter(r -> r.isDraft() == draft && users.contains(r.getUserId()))
                            .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp())).toList();
                });
        when(history.findByProjectIdAndDraftOrderByTimestampDesc(anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    boolean draft = inv.getArgument(1);
                    return rows.stream().filter(r -> r.isDraft() == draft)
                            .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp())).toList();
                });
        RollbackAuditRepository audits = mock(RollbackAuditRepository.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            if (audit.getId() == null) {
                audit.setId("audit-" + (auditStore.size() + 1));
            }
            auditStore.put(audit.getId(), audit);
            return audit;
        });
        when(audits.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(auditStore.get(inv.<String>getArgument(0))));
        when(audits.findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    Collection<String> users = inv.getArgument(2);
                    return auditStore.values().stream()
                            .filter(a -> inv.getArgument(1).equals(a.getDirection()) && users.contains(a.getRevertedByUserId()))
                            .sorted((a, b) -> b.getRevertedAt().compareTo(a.getRevertedAt())).toList();
                });

        HistorySyncService historySync = mock(HistorySyncService.class);
        when(historySync.getHistoryChange(anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
        doAnswer(inv -> {
            HistoryChange row = historySync.getHistoryChange(inv.getArgument(0));
            row.setReverted(inv.getArgument(1));
            row.setRevertedAuditId(inv.getArgument(2));
            return null;
        }).when(historySync).setEntryReverted(anyString(), anyBoolean(), any());

        OntologyMetadataService metadata = mock(OntologyMetadataService.class);
        when(metadata.getPrefixes(P)).thenAnswer(inv -> {
            List<Map<String, String>> list = new ArrayList<>();
            prefixes.forEach((p, ns) -> list.add(Map.of("prefix", p, "namespace", ns)));
            return list;
        });
        doAnswer(inv -> {
            String oldPrefix = inv.getArgument(3);
            if (oldPrefix != null && !oldPrefix.equals(inv.getArgument(1))) {
                prefixes.remove(oldPrefix);
            }
            prefixes.put(inv.getArgument(1), inv.getArgument(2));
            writes.add("set");
            return null;
        }).when(metadata).updatePrefix(anyString(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any());
        doAnswer(inv -> {
            prefixes.remove(inv.<String>getArgument(1));
            writes.add("delete");
            return null;
        }).when(metadata).deletePrefix(anyString(), anyString(), anyBoolean(), any(), any(), any());

        RollbackMutationPlanner planner = new RollbackMutationPlanner();
        RollbackGraphFacts facts = mock(RollbackGraphFacts.class);
        rollback = new ChangeRollbackService(historySync, planner, facts, mock(OntologyMutationService.class), audits,
                mock(OntologyHistoryService.class), new ProjectWriteLockRegistry(), mock(DraftTrackingService.class), null);
        ReflectionTestUtils.setField(rollback, "actionRunner", new PrefixRollbackActions(metadata));
        preconditions = new UndoPreconditions(planner, facts);
        undoService = new UserUndoService(history, audits, rollback);
    }

    private HistoryChange recorded(String id, String type, String prefix, String oldPrefix, String oldNs, String newNs,
                                   int minute) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setProjectId(P);
        change.setUserId(MONGO_ID);
        change.setUsername("Me");
        change.setOperationType(type);
        change.setEntityLabel(prefix);
        change.setOldValue(oldNs);
        change.setNewValue(newNs);
        change.setAnnotationProperty(oldPrefix);
        change.setSource(ChangeOrigin.MANUAL);
        change.setTimestamp(T0.plusMinutes(minute));
        rows.add(change);
        return change;
    }

    private Result undo(Identity who) {
        Next next = undoService.nextUndo(P, false, who);
        assertTrue(next.available(), next.reason());
        HistoryChange target = rows.stream().filter(r -> r.getId().equals(next.changeId())).findFirst().orElseThrow();
        assertEquals(Optional.empty(), preconditions.violation(P, List.of(target)));
        return rollback.undoEntry(P, next.changeId(), new Actor(who.primary(), who.primary()), false);
    }

    private Result redo(Identity who) {
        Next next = undoService.nextRedo(P, false, who);
        assertTrue(next.available(), next.reason());
        return rollback.redoUndo(P, next.auditId(), new Actor(who.primary(), who.primary()), false);
    }

    @Test
    void anAddedPrefixIsSetAsideByUndoAndRestoredByRedo() {
        Identity who = Identity.of(EMAIL, MONGO_ID);
        prefixes.put("ex", "http://ex/");
        recorded("c1", "prefixAdded", "ex", null, null, "http://ex/", 1);

        Result undone = undo(who);
        assertTrue(undone.ok(), String.valueOf(undone.skipped()));
        assertFalse(prefixes.containsKey("ex"));
        assertEquals(List.of("delete"), writes);

        Result redone = redo(who);
        assertTrue(redone.ok(), String.valueOf(redone.skipped()));
        assertEquals("http://ex/", prefixes.get("ex"));
        assertEquals(List.of("delete", "set"), writes);
        assertFalse(undoService.nextRedo(P, false, who).available());
        assertTrue(undoService.nextUndo(P, false, who).available());
    }

    @Test
    void aChangedPrefixIsRestoredByUndoAndReappliedByRedo() {
        Identity who = Identity.of(EMAIL, MONGO_ID);
        prefixes.put("ex", "http://new/");
        recorded("c1", "prefixModified", "ex", null, "http://old/", "http://new/", 1);

        assertTrue(undo(who).ok());
        assertEquals("http://old/", prefixes.get("ex"));
        assertTrue(redo(who).ok());
        assertEquals("http://new/", prefixes.get("ex"));
    }

    @Test
    void aRenamedPrefixIsRenamedBackAndForward() {
        Identity who = Identity.of(EMAIL, MONGO_ID);
        prefixes.put("new", "http://ex/");
        recorded("c1", "prefixModified", "new", "old", "http://ex/", "http://ex/", 1);

        assertTrue(undo(who).ok());
        assertEquals(Map.of("old", "http://ex/"), prefixes);
        assertTrue(redo(who).ok());
        assertEquals(Map.of("new", "http://ex/"), prefixes);
    }

    @Test
    void aDeletedPrefixComesBackOnUndoAndGoesAgainOnRedo() {
        Identity who = Identity.of(EMAIL, MONGO_ID);
        recorded("c1", "prefixDeleted", "ex", null, "http://ex/", null, 1);

        assertTrue(undo(who).ok());
        assertEquals("http://ex/", prefixes.get("ex"));
        assertTrue(redo(who).ok());
        assertFalse(prefixes.containsKey("ex"));
    }

    @Test
    void undoIsRefusedWhenTheNamespaceWasChangedByAnotherEdit() {
        Identity who = Identity.of(EMAIL, MONGO_ID);
        prefixes.put("ex", "http://someone-else/");
        recorded("c1", "prefixAdded", "ex", null, null, "http://ex/", 1);

        Next next = undoService.nextUndo(P, false, who);
        assertTrue(next.available());
        Result result = rollback.undoEntry(P, next.changeId(), new Actor(EMAIL, EMAIL), false);
        assertFalse(result.ok());
        assertEquals("http://someone-else/", prefixes.get("ex"));
    }

    @Test
    void aTokenCarryingOnlyTheEmailDoesNotOwnARowStoredUnderTheDatabaseIdSoItAsksFirst() {
        HistoryChange row = recorded("c1", "prefixAdded", "ex", null, null, "http://ex/", 1);

        Next next = undoService.nextUndo(P, false, Identity.of(EMAIL));

        assertTrue(next.available());
        assertEquals("c1", next.changeId());
        assertFalse(Identity.of(EMAIL).owns(row.getUserId()));
    }

    @Test
    void aRowStoredUnderTheEmailIsFoundByAnEmailOnlyIdentity() {
        HistoryChange row = recorded("c1", "prefixAdded", "ex", null, null, "http://ex/", 1);
        row.setUserId(EMAIL);
        prefixes.put("ex", "http://ex/");

        assertTrue(undo(Identity.of(EMAIL)).ok());
        assertFalse(prefixes.containsKey("ex"));
    }
}
