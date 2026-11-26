package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UndoStagesTest {

    private static final String P = "proj";
    private static final String BEFORE = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"proj\",\"name\":\"old\"}";
    private static final String AFTER = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"proj\",\"name\":\"new\"}";

    private FakeUndoIntents fake;
    private HistorySyncService historySync;
    private RollbackActionRunner runner;
    private RollbackGraphFacts facts;
    private OntologyMutationService mutations;
    private RollbackAuditRepository audits;
    private ChangeRollbackService service;

    @BeforeEach
    void setUp() {
        fake = new FakeUndoIntents();
        historySync = mock(HistorySyncService.class);
        runner = mock(RollbackActionRunner.class);
        when(runner.holds(anyString(), any(), anyBoolean(), any())).thenReturn(true);
        audits = mock(RollbackAuditRepository.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            audit.setId("audit-1");
            return audit;
        });
        facts = mock(RollbackGraphFacts.class);
        mutations = mock(OntologyMutationService.class);
        service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), facts,
                mutations, audits, mock(OntologyHistoryService.class),
                new ProjectWriteLockRegistry(), mock(DraftTrackingService.class), null);
        ReflectionTestUtils.setField(service, "actionRunner", runner);
        ReflectionTestUtils.setField(service, "operations", new UndoOperations(fake.service, mock(UndoReconciler.class)));
    }

    private HistoryChange rule(String id) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setProjectId(P);
        change.setUserId("u@x.com");
        change.setOperationType("updateFuzzyRule");
        change.setEntityIRI("urn:fuzzyrule:" + id);
        change.setEntityLabel("Rule");
        change.setDocumentStore("fuzzyRule");
        change.setDocumentId("6acf64ae94e5320b04bb880a");
        change.setDocumentBefore(BEFORE);
        change.setDocumentAfter(AFTER);
        when(historySync.getHistoryChange(id)).thenReturn(change);
        return change;
    }

    @Test
    void aChangeAssistantUndoIsATrackedOperationThatReachesTheLastStage() {
        rule("c1");

        Result result = service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com", "op-9"), false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        UndoIntentDocument intent = fake.only().orElseThrow();
        assertEquals("op-9", intent.getOpId());
        assertEquals(Status.COMMITTED, intent.getStatus());
        assertEquals(Stage.HISTORY_MARKED, intent.getStage());
        assertEquals(List.of("c1"), intent.getChangeIds());
        assertEquals("audit-1", intent.getAuditId());
    }

    @Test
    void aStepThatFailsLeavesTheOperationMarkedForRepairInsteadOfLost() {
        rule("c1");
        doThrow(new IllegalStateException("document store offline")).when(runner).run(anyString(), any(), anyBoolean(), any());

        Result result = service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com"), false);

        assertEquals(500, result.status());
        UndoIntentDocument intent = fake.only().orElseThrow();
        assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
        assertEquals(Stage.GRAPH_APPLIED, intent.getStage());
        assertEquals(List.of("c1"), intent.getChangeIds());
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void anUndoWhoseSecondBatchFailsIsMarkedForRepairWithItsProgressKept() {
        Limit.install(Limit.resolve(Limit.Profile.STANDARD, Map.of(Limit.DELTA_BATCH_TRIPLES, 100)));
        try {
            HistoryChange change = new HistoryChange.Builder(P, "import", "u@x.com", "u@x.com").operationType("editClass")
                    .entityIRI("http://ex/A").entityLabel("A").build();
            change.setId("big");
            StringBuilder added = new StringBuilder();
            for (int i = 0; i < 250; i++) {
                added.append("<http://ex/C").append(i).append("> <http://www.w3.org/2000/01/rdf-schema#label> \"c")
                        .append(i).append("\" .\n");
            }
            change.setExactInserted(added.toString());
            when(historySync.getHistoryChange("big")).thenReturn(change);
            when(facts.deltaHolds(anyString(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(2));
            when(facts.entityExists(anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
            doNothing().doThrow(new IllegalStateException("fuseki down"))
                    .when(mutations).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());

            Result result = service.undoEntry(P, "big", new Actor("u@x.com", "u@x.com"), false);

            assertEquals(500, result.status(), result.message());
            UndoIntentDocument intent = fake.only().orElseThrow();
            assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
            assertEquals("big", intent.getBatchChangeId());
            assertEquals(1, intent.getAppliedBatches());
            assertEquals(100, intent.getBatchTriples());
            assertEquals("audit-1", intent.getAuditId());
            verify(audits, never()).deleteById(anyString());
            verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
        } finally {
            Limit.install(Limit.resolve(Limit.Profile.STANDARD, Map.of()));
        }
    }

    @Test
    void anEntryAnInterruptedOperationLeftHalfDoneIsNotUndoneAgainBlindly() {
        rule("c1");
        fake.seed(P, "op-0", ChangeRollbackService.UNDO, Status.NEEDS_ATTENTION, Stage.GRAPH_APPLIED, List.of("c1"), null, null);

        Result result = service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com"), false);

        assertTrue(result.skipped().stream().anyMatch(item -> item.reason().contains("needs repair")));
        verify(runner, never()).run(anyString(), any(), anyBoolean(), any());
    }

    @Test
    void aDryRunLeavesNoOperationRecord() {
        rule("c1");

        service.undoEntry(P, "c1", new Actor("u@x.com", "u@x.com"), true);

        assertTrue(fake.stored.isEmpty());
    }
}
