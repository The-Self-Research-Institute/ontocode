package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetImportService;

import java.util.List;
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

class ChangeRollbackServiceImportTest {

    private static final String P = "proj";
    private static final String URN = SpreadsheetImportService.ENTITY_IRI_PREFIX + "import-1";
    private static final Actor ACTOR = new Actor("u@x.com", "u@x.com");
    private static final String DELETE = "DELETE DATA {\n<http://ex.org/S001> <http://ex.org/p> \"1\" .\n}";
    private static final String INSERT = "INSERT DATA {\n<http://ex.org/S001> <http://ex.org/p> \"1\" .\n}";

    private HistorySyncService historySync;
    private RollbackGraphFacts facts;
    private OntologyMutationService mutations;
    private RollbackAuditRepository audits;
    private OntologyHistoryService history;
    private SpreadsheetImportService imports;
    private ChangeRollbackService service;

    @BeforeEach
    void setUp() {
        historySync = mock(HistorySyncService.class);
        facts = mock(RollbackGraphFacts.class);
        mutations = mock(OntologyMutationService.class);
        audits = mock(RollbackAuditRepository.class);
        history = mock(OntologyHistoryService.class);
        imports = mock(SpreadsheetImportService.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            if (audit.getId() == null) {
                audit.setId("audit-1");
            }
            return audit;
        });
        service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), facts, mutations, audits, history,
                new ProjectWriteLockRegistry(), mock(DraftTrackingService.class), null);
        service.setSpreadsheetImports(imports);
    }

    private static HistoryChange importEntry(boolean draft) {
        HistoryChange change = new HistoryChange.Builder(P, "edit-1", "u@x.com", "u@x.com")
                .operationType(SpreadsheetImportService.OPERATION_TYPE).entityIRI(URN)
                .entityLabel("sensor-readings.xlsx").draft(draft).build();
        change.setId("c1");
        return change;
    }

    @Test
    void undoRemovesExactlyTheReceiptTriplesInOneStep() {
        when(historySync.getHistoryChange("c1")).thenReturn(importEntry(false));
        when(imports.rollbackUpdates(P, URN, true)).thenReturn(List.of(DELETE));

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertTrue(result.ok(), result.message());
        assertEquals(1, result.applied().size());
        verify(mutations).applyRawUpdate(P, DELETE, false, "u@x.com");
        verify(historySync).setEntryReverted("c1", true, "audit-1");
        verify(imports).markUndone(P, URN, true, "u@x.com");
        verify(history).recordEdit(eq(P), eq("u@x.com"), eq("u@x.com"), eq("ROLLBACK_ENTRY"), eq(URN), any(),
                any(), any(), anyString(), any(), any(), eq(false), any(ChangeOrigin.class));
        verify(facts, never()).entityExists(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void onDesktopTheImportIsUndoneInTheInMemoryOntology() {
        when(historySync.getHistoryChange("c1")).thenReturn(importEntry(false));
        when(imports.rollbackUpdates(P, URN, true)).thenReturn(List.of(DELETE));
        when(imports.applyOnDesktop(P, List.of(DELETE))).thenReturn(true);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertTrue(result.ok(), result.message());
        verify(imports).applyOnDesktop(P, List.of(DELETE));
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(imports).markUndone(P, URN, true, "u@x.com");
    }

    @Test
    void redoPutsTheSameTriplesBack() {
        HistoryChange undone = importEntry(false);
        undone.setReverted(true);
        undone.setRevertedAuditId("audit-undo");
        RollbackAudit undoAudit = new RollbackAudit();
        undoAudit.setId("audit-undo");
        undoAudit.setProjectId(P);
        undoAudit.setDirection(ChangeRollbackService.UNDO);
        undoAudit.setHistoryChangeId("c1");
        undoAudit.setChangeIds(List.of("c1"));
        when(audits.findById("audit-undo")).thenReturn(Optional.of(undoAudit));
        when(historySync.getHistoryChange("c1")).thenReturn(undone);
        when(imports.rollbackUpdates(P, URN, false)).thenReturn(List.of(INSERT));

        Result result = service.redoUndo(P, "audit-undo", ACTOR, false);

        assertTrue(result.ok(), result.message());
        verify(mutations).applyRawUpdate(P, INSERT, false, "u@x.com");
        verify(historySync).setEntryReverted("c1", false, null);
        verify(imports).markUndone(P, URN, false, "u@x.com");
    }

    @Test
    void draftImportsAreUndoneInTheAuthorsDraft() {
        when(historySync.getHistoryChange("c1")).thenReturn(importEntry(true));
        when(imports.rollbackUpdates(P, URN, true)).thenReturn(List.of(DELETE));

        assertTrue(service.undoEntry(P, "c1", ACTOR, false).ok());
        verify(mutations).applyRawUpdate(P, DELETE, true, "u@x.com");
    }

    @Test
    void anAlreadyUndoneImportIsSkipped() {
        HistoryChange undone = importEntry(false);
        undone.setReverted(true);
        when(historySync.getHistoryChange("c1")).thenReturn(undone);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertFalse(result.ok());
        assertEquals("Already undone", result.skipped().get(0).reason());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(imports, never()).markUndone(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void aMissingReceiptIsReportedInsteadOfGuessing() {
        when(historySync.getHistoryChange("c1")).thenReturn(importEntry(false));
        when(imports.rollbackUpdates(P, URN, true)).thenReturn(null);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertFalse(result.ok());
        assertEquals("The record of this import was not found", result.skipped().get(0).reason());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void aDryRunPreviewsWithoutWriting() {
        when(historySync.getHistoryChange("c1")).thenReturn(importEntry(false));
        when(imports.rollbackUpdates(P, URN, true)).thenReturn(List.of(DELETE));

        Result result = service.undoEntry(P, "c1", ACTOR, true);

        assertTrue(result.dryRun());
        assertEquals(1, result.applied().size());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(imports, never()).markUndone(anyString(), anyString(), anyBoolean(), any());
    }
}
