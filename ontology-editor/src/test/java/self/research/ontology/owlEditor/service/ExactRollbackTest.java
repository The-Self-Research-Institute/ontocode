package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExactRollbackTest {

    private static final String P = "proj";
    private static final String A = "http://ex/A";
    private static final String B = "http://ex/B";
    private static final String DISJOINT = "http://www.w3.org/2002/07/owl#disjointWith";
    private static final Actor ACTOR = new Actor("u@x.com", "u@x.com");

    private HistorySyncService historySync;
    private RollbackGraphFacts facts;
    private OntologyMutationService mutations;
    private RollbackAuditRepository audits;
    private ChangeRollbackService service;

    @BeforeEach
    void setUp() {
        historySync = mock(HistorySyncService.class);
        facts = mock(RollbackGraphFacts.class);
        mutations = mock(OntologyMutationService.class);
        audits = mock(RollbackAuditRepository.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            audit.setId("audit-1");
            return audit;
        });
        when(facts.entityExists(anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
        when(facts.deltaHolds(anyString(), any(), anyBoolean())).thenReturn(true);
        service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), facts, mutations, audits,
                mock(OntologyHistoryService.class), new ProjectWriteLockRegistry(), mock(DraftTrackingService.class), null);
    }

    private static Model model(String subject, String object) {
        Model model = new LinkedHashModel();
        var vf = SimpleValueFactory.getInstance();
        model.add(vf.createIRI(subject), vf.createIRI(DISJOINT), vf.createIRI(object));
        return model;
    }

    private static HistoryChange entry(String id, String iri) {
        HistoryChange change = new HistoryChange.Builder(P, "edit-" + id, "u@x.com", "u@x.com")
                .operationType("addDisjointWith").entityIRI(iri).entityLabel(iri).changeSetId("set-1")
                .source(ChangeOrigin.MANUAL).subChanges(new ArrayList<>()).build();
        change.setId(id);
        return change;
    }

    private static HistoryChange carrier(String id, String iri, int covers) {
        HistoryChange change = entry(id, iri);
        ExactDelta delta = new ExactDelta(new LinkedHashModel(), model(A, B));
        change.setExactInserted(ExactDelta.write(delta.inserted()));
        change.setExactCovers(covers);
        return change;
    }

    @Test
    void undoingASetAppliesTheStoredDeltaOnceAndMarksEveryCoveredEntry() {
        HistoryChange carrier = carrier("c1", A, 1);
        HistoryChange covered = entry("c2", B);
        covered.setCoveredBy("c1");
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(covered, carrier));

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(mutations).applyRawUpdate(eq(P), argThat(sql -> sql.contains("DELETE DATA") && sql.contains(DISJOINT)),
                eq(false), eq("u@x.com"));
        verify(historySync).setEntryReverted("c1", true, "audit-1");
        verify(historySync).setEntryReverted("c2", true, "audit-1");
    }

    @Test
    void undoingOneEntryOfASharedDeltaIsRefused() {
        HistoryChange carrier = carrier("c1", A, 1);
        when(historySync.getHistoryChange("c1")).thenReturn(carrier);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertFalse(result.ok());
        assertEquals("These edits were saved together; undo them together", result.skipped().get(0).reason());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void thePreviewCountsEverySubChangeOfTheCarrierAndOfTheEntriesItCovers() {
        HistoryChange carrier = carrier("c1", A, 1);
        carrier.getSubChanges().add(new HistoryChange.SubChange("p1", null, "x", null, true));
        carrier.getSubChanges().add(new HistoryChange.SubChange("p2", null, "y", null, true));
        HistoryChange covered = entry("c2", B);
        covered.setCoveredBy("c1");
        covered.getSubChanges().add(new HistoryChange.SubChange("p3", null, "z", null, true));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(covered, carrier));

        Result result = service.undoChangeSet(P, "set-1", ACTOR, true);

        assertEquals(3, result.applied().size());
    }

    @Test
    void aRenameIsRefusedWhenLaterEditsUseTheNewName() {
        HistoryChange rename = carrier("c1", A, 0);
        rename.setOperationType("renameEntity");
        when(historySync.getHistoryChange("c1")).thenReturn(rename);
        when(facts.laterUsesNewName(P, rename)).thenReturn(true);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertFalse(result.ok());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void aSingleEntryDeltaCanBeUndoneOnItsOwn() {
        HistoryChange carrier = carrier("c1", A, 0);
        when(historySync.getHistoryChange("c1")).thenReturn(carrier);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(mutations).applyRawUpdate(eq(P), anyString(), eq(false), eq("u@x.com"));
    }

    @Test
    void anUndoIsSkippedWhenTheGraphChangedSinceTheEdit() {
        HistoryChange carrier = carrier("c1", A, 0);
        when(historySync.getHistoryChange("c1")).thenReturn(carrier);
        when(facts.deltaHolds(anyString(), any(), eq(true))).thenReturn(false);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertFalse(result.ok());
        assertEquals("It was changed since this edit", result.skipped().get(0).reason());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void redoAppliesTheDeltaForward() {
        HistoryChange carrier = carrier("c1", A, 0);
        carrier.setReverted(true);
        carrier.setRevertedAuditId("undo-1");
        RollbackAudit undo = new RollbackAudit();
        undo.setId("undo-1");
        undo.setProjectId(P);
        undo.setDirection("UNDO");
        undo.setHistoryChangeId("c1");
        undo.setChangeIds(List.of("c1"));
        when(audits.findById("undo-1")).thenReturn(java.util.Optional.of(undo));
        when(historySync.getHistoryChange("c1")).thenReturn(carrier);

        Result result = service.redoUndo(P, "undo-1", ACTOR, false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(mutations).applyRawUpdate(eq(P), argThat(sql -> sql.contains("INSERT DATA") && sql.contains(DISJOINT)),
                eq(false), eq("u@x.com"));
        verify(historySync).setEntryReverted("c1", false, null);
    }

    @Test
    void aCoveredEntryWithoutAnyDeltaOfItsOwnIsStillMarkedWhenItsSetIsUndone() {
        HistoryChange carrier = carrier("c1", A, 2);
        HistoryChange second = entry("c2", B);
        second.setCoveredBy("c1");
        HistoryChange third = entry("c3", "http://ex/C");
        third.setCoveredBy("c1");
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(third, second, carrier));

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok(), String.valueOf(result.skipped()));
        verify(historySync).setEntryReverted("c2", true, "audit-1");
        verify(historySync).setEntryReverted("c3", true, "audit-1");
    }
}
