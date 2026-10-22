package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;

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

class DocumentUndoServiceTest {

    private static final String P = "proj";
    private static final Actor ACTOR = new Actor("u@x.com", "u@x.com");
    private static final String BEFORE = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"name\":\"old\"}";
    private static final String AFTER = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"name\":\"new\"}";

    private HistorySyncService historySync;
    private RollbackActionRunner runner;
    private ChangeRollbackService service;
    private RollbackAuditRepository audits;

    @BeforeEach
    void setUp() {
        historySync = mock(HistorySyncService.class);
        runner = mock(RollbackActionRunner.class);
        when(runner.holds(anyString(), any(), anyBoolean(), any())).thenReturn(true);
        audits = mock(RollbackAuditRepository.class);
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

    private static HistoryChange rule(String operation, String before, String after) {
        HistoryChange change = new HistoryChange();
        change.setId("c1");
        change.setProjectId(P);
        change.setUserId("u@x.com");
        change.setOperationType(operation);
        change.setEntityIRI("urn:swrlrule:6acf64ae94e5320b04bb880a");
        change.setEntityLabel("Rule");
        change.setDocumentStore("swrlRule");
        change.setDocumentId("6acf64ae94e5320b04bb880a");
        change.setDocumentBefore(before);
        change.setDocumentAfter(after);
        return change;
    }

    private Result undo(HistoryChange change) {
        when(historySync.getHistoryChange("c1")).thenReturn(change);
        return service.undoEntry(P, "c1", ACTOR, false);
    }

    @Test
    void undoingARuleEditRestoresTheOldDocument() {
        Result result = undo(rule("updateSwrlRule", BEFORE, AFTER));

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.restoreDocument("swrlRule", "6acf64ae94e5320b04bb880a", BEFORE, AFTER)),
                eq(false), any());
        verify(historySync).setEntryReverted("c1", true, "audit-1");
    }

    @Test
    void undoingARuleCreationDeletesTheDocument() {
        Result result = undo(rule("createSwrlRule", null, AFTER));

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.restoreDocument("swrlRule", "6acf64ae94e5320b04bb880a", null, AFTER)),
                eq(false), any());
    }

    @Test
    void anEditedRuleIsNotOverwrittenBySomeonesUndo() {
        when(runner.holds(anyString(), any(), anyBoolean(), any())).thenReturn(false);

        Result result = undo(rule("updateSwrlRule", BEFORE, AFTER));

        assertFalse(result.skipped().isEmpty());
        assertTrue(result.skipped().get(0).reason().contains("changed since"));
        verify(runner, never()).run(anyString(), any(), anyBoolean(), any());
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), anyString());
    }

    @Test
    void redoPutsTheEditedDocumentBack() {
        HistoryChange change = rule("updateSwrlRule", BEFORE, AFTER);
        change.setReverted(true);
        change.setRevertedAuditId("audit-0");
        when(historySync.getHistoryChange("c1")).thenReturn(change);
        RollbackAudit undone = new RollbackAudit();
        undone.setId("audit-0");
        undone.setProjectId(P);
        undone.setDirection(ChangeRollbackService.UNDO);
        undone.setHistoryChangeId("c1");
        when(audits.findById("audit-0")).thenReturn(java.util.Optional.of(undone));
        org.mockito.Mockito.doAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            audit.setId("audit-2");
            return audit;
        }).when(audits).save(any());

        Result result = service.redoUndo(P, "audit-0", ACTOR, false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(runner).run(eq(P), eq(RollbackAction.restoreDocument("swrlRule", "6acf64ae94e5320b04bb880a", AFTER, BEFORE)),
                eq(false), any());
    }
}
