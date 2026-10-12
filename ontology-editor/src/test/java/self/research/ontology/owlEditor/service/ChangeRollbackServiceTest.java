package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

class ChangeRollbackServiceTest {

    private static final String P = "proj";
    private static final String E = "http://example.org/Pizzanew";
    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String DISJOINT = "http://www.w3.org/2002/07/owl#disjointWith";
    private static final String COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    private static final Actor ACTOR = new Actor("u@x.com", "u@x.com");

    private HistorySyncService historySync;
    private RollbackGraphFacts facts;
    private OntologyMutationService mutations;
    private RollbackAuditRepository audits;
    private OntologyHistoryService history;
    private DraftTrackingService draftTracking;
    private ChangeRollbackService service;

    @BeforeEach
    void setUp() {
        historySync = mock(HistorySyncService.class);
        facts = mock(RollbackGraphFacts.class);
        mutations = mock(OntologyMutationService.class);
        audits = mock(RollbackAuditRepository.class);
        history = mock(OntologyHistoryService.class);
        draftTracking = mock(DraftTrackingService.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            audit.setId("audit-1");
            return audit;
        });
        when(facts.entityExists(anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
        service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), facts, mutations, audits, history,
                new ProjectWriteLockRegistry(), draftTracking, null);
    }

    private static HistoryChange.SubChange sub(String id, String predicate, String value, boolean addition) {
        HistoryChange.SubChange sc = new HistoryChange.SubChange(predicate, addition ? null : value, addition ? value : null,
                LABEL.equals(predicate) || COMMENT.equals(predicate) ? predicate : null, addition);
        sc.setId(id);
        return sc;
    }

    private static HistoryChange entry(String id, String op, HistoryChange.SubChange... subs) {
        HistoryChange change = new HistoryChange.Builder(P, "edit-" + id, "u@x.com", "u@x.com")
                .operationType(op).entityIRI(E).entityLabel("Pizzanew").changeSetId("set-1").source(ChangeOrigin.AI)
                .subChanges(new ArrayList<>(List.of(subs))).build();
        change.setId(id);
        return change;
    }

    private void presentUnlessChanged(Set<String> changedValues) {
        when(facts.statementPresent(anyString(), anyString(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    String value = inv.getArgument(3);
                    return !changedValues.contains(value);
                });
    }

    @Test
    void undoingAnAiChangeSetRevertsEveryEntryAndRecordsOneAudit() {
        HistoryChange created = entry("c1", "createClass", sub("s1", LABEL, "Pizzanew", true));
        HistoryChange modified = entry("c2", "addStatement", sub("s2", DISJOINT, "http://example.org/Other", true));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(modified, created));
        presentUnlessChanged(Set.of());

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok());
        assertEquals("audit-1", result.auditId());
        assertTrue(result.skipped().isEmpty());
        verify(mutations).applyRawUpdate(eq(P), anyString(), eq(false), eq("u@x.com"));
        verify(mutations).applyForRollback(eq(P), any());
        verify(historySync).setEntryReverted("c1", true, "audit-1");
        verify(historySync).setEntryReverted("c2", true, "audit-1");
        verify(history).recordEdit(eq(P), eq("u@x.com"), eq("u@x.com"), eq("ROLLBACK_CHANGESET"), any(), any(),
                any(), any(), anyString(), any(), any(), eq(false), any(ChangeOrigin.class));
    }

    @Test
    void aFailureMidSetKeepsWhatWasUndoneRecordedAndReportsTheError() {
        HistoryChange created = entry("c1", "createClass", sub("s1", LABEL, "Pizzanew", true));
        HistoryChange modified = entry("c2", "addStatement", sub("s2", DISJOINT, "http://example.org/Other", true));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(modified, created));
        presentUnlessChanged(Set.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("store offline")).when(mutations).applyForRollback(eq(P), any());

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertEquals(500, result.status());
        assertFalse(result.ok());
        assertEquals("audit-1", result.auditId());
        assertEquals(1, result.applied().size());
        assertTrue(result.message().contains("store offline"));
        verify(historySync).setEntryReverted("c2", true, "audit-1");
        verify(historySync, never()).setEntryReverted(eq("c1"), anyBoolean(), any());
        verify(history).recordEdit(eq(P), anyString(), anyString(), eq("ROLLBACK_CHANGESET"), any(), any(),
                any(), any(), anyString(), any(), any(), eq(false), any(ChangeOrigin.class));
    }

    @Test
    void laterStepsOfASetSeeWhatEarlierUndoStepsChanged() {
        HistoryChange created = entry("c1", "createClass");
        HistoryChange deleted = entry("c2", "deleteClass");
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(deleted, created));
        java.util.concurrent.atomic.AtomicBoolean exists = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(facts.entityExists(anyString(), anyString(), anyBoolean(), any())).thenAnswer(inv -> exists.get());
        org.mockito.Mockito.doAnswer(inv -> {
            exists.set(true);
            return null;
        }).when(mutations).applyForRollback(eq(P), any());
        presentUnlessChanged(Set.of());

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok());
        assertTrue(result.skipped().isEmpty());
        assertEquals(2, result.applied().size());
        verify(historySync).setEntryReverted("c1", true, "audit-1");
        verify(historySync).setEntryReverted("c2", true, "audit-1");
    }

    @Test
    void redoWalksBackToAnEarlierUndoOnceTheLatestOneIsRedone() {
        HistoryChange.SubChange first = sub("s1", DISJOINT, "http://example.org/A", true);
        HistoryChange.SubChange second = sub("s2", COMMENT, "note", true);
        second.setReverted(true);
        second.setRevertedAuditId("undo-1");
        HistoryChange modified = entry("c1", "addStatement", first, second);
        RollbackAudit newest = new RollbackAudit();
        newest.setId("undo-2");
        RollbackAudit older = new RollbackAudit();
        older.setId("undo-1");
        when(audits.findByChangeSetIdAndDirectionOrderByRevertedAtDesc("set-1", "UNDO")).thenReturn(List.of(newest, older));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(modified));
        when(facts.statementPresent(anyString(), anyString(), anyString(), anyString(), anyBoolean(), any())).thenReturn(false);

        Result result = service.redoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok());
        ArgumentCaptor<java.util.Collection<String>> handled = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(historySync).setSubChangesReverted(eq("c1"), handled.capture(), eq(false), eq(null));
        assertEquals(Set.of("s2"), Set.copyOf(handled.getValue()));
    }

    @Test
    void anEntryWithNothingLeftToUndoIsStillMarkedUndoneWhenItComesAfterAPlannedOne() {
        HistoryChange created = entry("c1", "createClass", sub("s1", LABEL, "Pizzanew", true));
        HistoryChange.SubChange gone = sub("s2", DISJOINT, "http://example.org/Other", true);
        gone.setReverted(true);
        HistoryChange leftover = entry("c2", "addStatement", gone);
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(created, leftover));
        presentUnlessChanged(Set.of());

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok());
        verify(historySync).setEntryReverted("c1", true, "audit-1");
        verify(historySync).setEntryReverted("c2", true, "audit-1");
    }

    @Test
    void aFailureOnTheFirstStepLeavesNoAuditBehind() {
        HistoryChange created = entry("c1", "createClass", sub("s1", LABEL, "Pizzanew", true));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(created));
        presentUnlessChanged(Set.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("store offline")).when(mutations).applyForRollback(eq(P), any());

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertEquals(500, result.status());
        assertEquals(null, result.auditId());
        verify(audits).deleteById("audit-1");
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any(), anyBoolean(), any(ChangeOrigin.class));
    }

    @Test
    void aDryRunReportsWhatWouldHappenWithoutWritingAnything() {
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(entry("c1", "createClass")));

        Result result = service.undoChangeSet(P, "set-1", ACTOR, true);

        assertTrue(result.dryRun());
        assertEquals(1, result.applied().size());
        verify(mutations, never()).applyForRollback(anyString(), any());
        verify(audits, never()).save(any());
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void aPropertyChangedSinceTheAiEditIsSkippedAndTheEntryStaysActive() {
        HistoryChange modified = entry("c2", "addStatement",
                sub("keep", DISJOINT, "http://example.org/Other", true),
                sub("stale", LABEL, "Edited later", true));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(modified));
        presentUnlessChanged(Set.of("Edited later"));

        Result result = service.undoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok());
        assertEquals(1, result.skipped().size());
        assertEquals("It was changed since this edit", result.skipped().get(0).reason());
        ArgumentCaptor<java.util.Collection<String>> handled = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(historySync).setSubChangesReverted(eq("c2"), handled.capture(), eq(true), eq("audit-1"));
        assertEquals(Set.of("keep"), Set.copyOf(handled.getValue()));
        verify(historySync, never()).setEntryReverted(eq("c2"), anyBoolean(), any());
    }

    @Test
    void entryRollbackAfterAGranularOneSkipsTheAlreadyUndoneSubChange() {
        HistoryChange.SubChange undone = sub("s-undone", DISJOINT, "http://example.org/Other", true);
        undone.setReverted(true);
        HistoryChange modified = entry("c2", "addStatement", undone, sub("s-live", LABEL, "Pizzanew", true));
        when(historySync.getHistoryChange("c2")).thenReturn(modified);
        presentUnlessChanged(Set.of());

        Result result = service.undoEntry(P, "c2", ACTOR, false);

        assertTrue(result.ok());
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
        verify(mutations).applyForRollback(eq(P), any());
        verify(historySync).setEntryReverted("c2", true, "audit-1");
    }

    @Test
    void aSubChangeOfADeletedEntityWaitsForTheWholeDeletionToBeUndone() {
        HistoryChange deleted = entry("c3", "deleteClass", sub("s", LABEL, "Pizzanew", false));
        when(historySync.getHistoryChange("c3")).thenReturn(deleted);
        when(facts.entityExists(anyString(), anyString(), anyBoolean(), any())).thenReturn(false);

        Result result = service.undoSubChange(P, "c3", "s", ACTOR, false);

        assertFalse(result.ok());
        assertEquals("Undo the whole deletion first to bring this entity back", result.skipped().get(0).reason());
        verify(mutations, never()).applyForRollback(anyString(), any());
    }

    @Test
    void anAlreadyUndoneEntryIsANoOp() {
        HistoryChange created = entry("c1", "createClass");
        created.setReverted(true);
        when(historySync.getHistoryChange("c1")).thenReturn(created);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertFalse(result.ok());
        assertEquals("Already undone", result.skipped().get(0).reason());
        verify(audits, never()).save(any());
    }

    @Test
    void undoEntriesThemselvesCannotBeRolledBack() {
        HistoryChange undoEntry = entry("r1", "ROLLBACK_CHANGESET");
        undoEntry.setSource(ChangeOrigin.ROLLBACK);
        when(historySync.getHistoryChange("r1")).thenReturn(undoEntry);

        Result result = service.undoEntry(P, "r1", ACTOR, false);

        assertFalse(result.ok());
        assertTrue(result.skipped().get(0).reason().startsWith("This is itself an undo or redo"));
    }

    @Test
    void redoOnlyRestoresWhatTheLastUndoOfTheSetRemoved() {
        HistoryChange.SubChange byUndo = sub("by-undo", LABEL, "Pizzanew", true);
        byUndo.setReverted(true);
        byUndo.setRevertedAuditId("undo-9");
        HistoryChange.SubChange earlier = sub("earlier", DISJOINT, "http://example.org/Other", true);
        earlier.setReverted(true);
        earlier.setRevertedAuditId("granular-3");
        HistoryChange modified = entry("c2", "addStatement", byUndo, earlier);
        RollbackAudit last = new RollbackAudit();
        last.setId("undo-9");
        when(audits.findByChangeSetIdAndDirectionOrderByRevertedAtDesc("set-1", "UNDO")).thenReturn(List.of(last));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(modified));
        when(facts.statementPresent(anyString(), anyString(), anyString(), anyString(), anyBoolean(), any())).thenReturn(false);

        Result result = service.redoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok());
        ArgumentCaptor<java.util.Collection<String>> handled = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(historySync).setSubChangesReverted(eq("c2"), handled.capture(), eq(false), eq(null));
        assertEquals(Set.of("by-undo"), Set.copyOf(handled.getValue()));
        verify(mutations, never()).applyRawUpdate(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void draftEntriesAreUndoneInTheOwnersDraftGraph() {
        HistoryChange created = entry("c1", "createClass");
        created.setDraft(true);
        when(historySync.getHistoryChange("c1")).thenReturn(created);

        Result result = service.undoEntry(P, "c1", ACTOR, false);

        assertTrue(result.ok());
        verify(mutations).applyDraftForRollback(eq(P), eq("u@x.com"), any());
        verify(mutations, never()).applyForRollback(anyString(), any());
    }

    @Test
    void aMissingChangeSetIsNotFound() {
        when(historySync.getChangeSet(P, "nope")).thenReturn(List.of());
        assertEquals(404, service.undoChangeSet(P, "nope", ACTOR, false).status());
    }

    private static RollbackAudit undoAudit(String id, String changeSetId, String changeId, String subChangeId) {
        RollbackAudit audit = new RollbackAudit();
        audit.setId(id);
        audit.setProjectId(P);
        audit.setDirection("UNDO");
        audit.setChangeSetId(changeSetId);
        audit.setHistoryChangeId(changeId);
        audit.setSubChangeId(subChangeId);
        audit.setChangeIds(List.of(changeId));
        return audit;
    }

    @Test
    void redoingAPropertyUndoPutsTheValueBackAndClearsItsFlag() {
        HistoryChange.SubChange comment = sub("s-comment", COMMENT, "Food allergens", true);
        comment.setReverted(true);
        comment.setRevertedAuditId("undo-1");
        HistoryChange created = entry("c1", "createClass", sub("s-label", LABEL, "Pizzanew", true), comment);
        when(audits.findById("undo-1")).thenReturn(Optional.of(undoAudit("undo-1", null, "c1", "s-comment")));
        when(historySync.getHistoryChange("c1")).thenReturn(created);
        when(facts.statementPresent(anyString(), anyString(), anyString(), anyString(), anyBoolean(), any())).thenReturn(false);

        Result result = service.redoUndo(P, "undo-1", ACTOR, false);

        assertTrue(result.ok());
        assertEquals(1, result.applied().size());
        verify(mutations).applyForRollback(eq(P), any());
        ArgumentCaptor<java.util.Collection<String>> handled = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(historySync).setSubChangesReverted(eq("c1"), handled.capture(), eq(false), eq(null));
        assertEquals(Set.of("s-comment"), Set.copyOf(handled.getValue()));
        verify(historySync, never()).setEntryReverted(anyString(), anyBoolean(), any());
        verify(history).recordEdit(eq(P), eq("u@x.com"), eq("u@x.com"), eq("REDO_SUBCHANGE"), eq(E), eq("Pizzanew"),
                any(), any(), eq("Redid comment on Pizzanew"), any(), any(), eq(false), any(ChangeOrigin.class));
    }

    @Test
    void anUndoFromAnotherProjectOrAlreadyARedoIsNotFound() {
        RollbackAudit other = undoAudit("undo-2", null, "c1", "s");
        other.setProjectId("someone-else");
        RollbackAudit redo = undoAudit("redo-3", null, "c1", "s");
        redo.setDirection("REDO");
        when(audits.findById("undo-2")).thenReturn(Optional.of(other));
        when(audits.findById("redo-3")).thenReturn(Optional.of(redo));
        when(audits.findById("missing")).thenReturn(Optional.empty());

        assertEquals(404, service.redoUndo(P, "undo-2", ACTOR, false).status());
        assertEquals(404, service.redoUndo(P, "redo-3", ACTOR, false).status());
        assertEquals(404, service.redoUndo(P, "missing", ACTOR, false).status());
        verify(mutations, never()).applyForRollback(anyString(), any());
    }

    @Test
    void redoingASetAfterAPartialUndoOfACreatedEntityRestoresTheUndoneProperty() {
        HistoryChange.SubChange label = sub("s-label", LABEL, "Pizzanew", true);
        label.setReverted(true);
        label.setRevertedAuditId("undo-9");
        HistoryChange created = entry("c1", "createClass", label, sub("s-kept", DISJOINT, "http://example.org/Other", true));
        RollbackAudit last = new RollbackAudit();
        last.setId("undo-9");
        when(audits.findByChangeSetIdAndDirectionOrderByRevertedAtDesc("set-1", "UNDO")).thenReturn(List.of(last));
        when(historySync.getChangeSet(P, "set-1")).thenReturn(List.of(created));
        when(facts.statementPresent(anyString(), anyString(), anyString(), anyString(), anyBoolean(), any())).thenReturn(false);

        Result result = service.redoChangeSet(P, "set-1", ACTOR, false);

        assertTrue(result.ok());
        assertTrue(result.skipped().isEmpty());
        verify(historySync).setSubChangesReverted(eq("c1"), any(), eq(false), eq(null));
    }

    @Test
    void rollbackRowsSayWhatWasUndone() {
        HistoryChange allergen = entry("c", "createClass");
        allergen.setEntityLabel("Allergen");
        HistoryChange edited = entry("m", "addStatement");
        edited.setEntityLabel("Allergen");
        ChangeRollbackService.Item comment = new ChangeRollbackService.Item("c", "s", E, "Allergen", COMMENT, null);
        ChangeRollbackService.Item label = new ChangeRollbackService.Item("c", "s2", E, "Allergen", LABEL, null);
        ChangeRollbackService.Item parent = new ChangeRollbackService.Item("c", "s3", E, "Allergen",
                "http://www.w3.org/2000/01/rdf-schema#subClassOf", null);
        ChangeRollbackService.Item whole = new ChangeRollbackService.Item("c", null, E, "Allergen", null, null);
        String sub = ChangeRollbackService.SUBCHANGE;
        String ent = ChangeRollbackService.ENTRY;

        assertEquals("Undid comment on Allergen", RollbackDescriptions.describe("UNDO", sub, List.of(comment), List.of(allergen)));
        assertEquals("Undid creating Allergen", RollbackDescriptions.describe("UNDO", ent, List.of(whole), List.of(allergen)));
        assertEquals("Redid creating Allergen", RollbackDescriptions.describe("REDO", ent, List.of(whole), List.of(allergen)));
        assertEquals("Undid change to Allergen", RollbackDescriptions.describe("UNDO", ent, List.of(whole), List.of(edited)));
        assertEquals("Undid label and parent class on Allergen",
                RollbackDescriptions.describe("UNDO", ent, List.of(label, parent), List.of(edited)));
        assertEquals("Undid label, parent class and 1 more on Allergen",
                RollbackDescriptions.describe("UNDO", ent, List.of(label, parent, comment), List.of(edited)));
        assertEquals("Undid change set (2 changes)",
                RollbackDescriptions.describe("UNDO", ChangeRollbackService.CHANGESET, List.of(comment, label), List.of()));
        assertEquals("has topping", RollbackDescriptions.predicateName("http://example.org/pizza#hasTopping"));
    }
}
