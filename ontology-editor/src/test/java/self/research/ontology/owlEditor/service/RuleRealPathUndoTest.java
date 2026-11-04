package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.rules.OntologyRulesFingerprint;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.RuleService.RuleInput;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RuleRealPathUndoTest {

    static final String NAME = "Students are people";

    RuleHistoryHarness h;

    @BeforeEach
    void setUp() throws Exception {
        h = new RuleHistoryHarness();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    static boolean same(Model a, Model b) {
        return Models.isomorphic(a, b);
    }

    void undoAndRedoRestoreExactly(boolean draft, Model before, Model after, String expectedText) {
        HistoryChange row = h.lastRow();
        String changeSet = row.getChangeSetId();
        assertEquals(1, h.lastSet().size(), "the rule change must be one history entry");
        assertEquals(CodeViewHistoryRecorder.RULE_CHANGE, row.getOperationType());
        assertEquals(expectedText, row.getDescription());
        assertEquals(draft, row.isDraft());
        assertTrue(row.carriesExactDelta());
        UndoAbility.Result ability = UndoAbility.of(row);
        assertTrue(ability.undoable(), String.valueOf(ability.reason()));

        Result undone = h.undo(changeSet, draft);
        assertTrue(undone.ok(), String.valueOf(undone.skipped()));
        assertTrue(same(h.graph(draft), before), "undo did not bring the graph back");
        assertTrue(row.isReverted());

        Result redone = h.redo(changeSet, draft);
        assertTrue(redone.ok(), String.valueOf(redone.skipped()));
        assertTrue(same(h.graph(draft), after), "redo did not reapply the change");
        assertFalse(row.isReverted());
    }

    void createRoundTrip(boolean draft) throws Exception {
        Model before = h.graph(draft);
        h.create(draft, NAME, "Student>Person");
        undoAndRedoRestoreExactly(draft, before, h.graph(draft), "Rule \"" + NAME + "\" added");
    }

    void editRoundTrip(boolean draft) throws Exception {
        String id = h.create(draft, NAME, "Student>Person");
        Model before = h.graph(draft);
        h.update(draft, id, new RuleInput("Pupils are people", "Student>Parent", "now documented", null, null));
        undoAndRedoRestoreExactly(draft, before, h.graph(draft),
                "Rule \"" + NAME + "\" renamed to \"Pupils are people\"");
    }

    void toggleRoundTrip(boolean draft) throws Exception {
        String id = h.create(draft, NAME, "Student>Person");
        Model enabled = h.graph(draft);
        h.update(draft, id, new RuleInput(null, null, null, null, false));
        Model disabled = h.graph(draft);
        undoAndRedoRestoreExactly(draft, enabled, disabled, "Rule \"" + NAME + "\" turned off");
        h.update(draft, id, new RuleInput(null, null, null, null, true));
        undoAndRedoRestoreExactly(draft, disabled, h.graph(draft), "Rule \"" + NAME + "\" turned on");
    }

    void deleteRoundTrip(boolean draft) throws Exception {
        String id = h.create(draft, NAME, "Student>Person");
        h.create(draft, "Teachers are people", "Teacher>Person");
        Model before = h.graph(draft);
        h.delete(draft, id);
        undoAndRedoRestoreExactly(draft, before, h.graph(draft), "Rule \"" + NAME + "\" deleted");
    }

    @Test
    void publicCreateUndoRedoThroughTheRealHistoryPath() throws Exception {
        createRoundTrip(false);
    }

    @Test
    void draftCreateUndoRedoThroughTheRealHistoryPath() throws Exception {
        createRoundTrip(true);
    }

    @Test
    void publicEditIsOneUndoableChangeSet() throws Exception {
        editRoundTrip(false);
    }

    @Test
    void draftEditIsOneUndoableChangeSet() throws Exception {
        editRoundTrip(true);
    }

    @Test
    void publicToggleUndoRedoAndWording() throws Exception {
        toggleRoundTrip(false);
    }

    @Test
    void draftToggleUndoRedoAndWording() throws Exception {
        toggleRoundTrip(true);
    }

    @Test
    void publicDeleteUndoRedoThroughTheRealHistoryPath() throws Exception {
        deleteRoundTrip(false);
    }

    @Test
    void draftDeleteUndoRedoThroughTheRealHistoryPath() throws Exception {
        deleteRoundTrip(true);
    }

    @Test
    void variableTypingTriplesDoNotAddHistoryEntries() throws Exception {
        h.create(true, NAME, "Student>Person");

        assertEquals(1, h.rows.size());
        assertNull(h.rows.get(0).getEntityIRI());
        assertEquals(0, h.rows.get(0).getExactCovers());
        assertNull(h.rows.get(0).getCoveredBy());
    }

    @Test
    void aCodeViewSaveOfARuleRecordsNoPerVariableEntries() throws Exception {
        Model empty = h.graph(false);
        h.create(false, NAME, "Student>Person");
        Model withRule = h.graph(false);
        OntologyHistoryService recorded = mock(OntologyHistoryService.class);

        new CodeViewHistoryRecorder(recorded, null).record("p", "u", "U", empty, withRule, false);

        ArgumentCaptor<String> operations = ArgumentCaptor.forClass(String.class);
        verify(recorded, atLeastOnce()).recordEdit(anyString(), anyString(), anyString(), operations.capture(), any(), any(),
                any(), any(), any(), any(), any(), anyBoolean(), any());
        assertEquals(List.of("createAnnotationProperty", "codeViewStructuralEdit"), operations.getAllValues());
    }

    @Test
    void ruleChangesKeepTheirExactDeltaWhateverTheSizeOfTheGraph() throws Exception {
        try {
            Limit.install(Limit.resolve(Limit.Profile.STANDARD, Map.of(Limit.WHOLE_GRAPH_MAX_TRIPLES, 1000)));
            StringBuilder insert = new StringBuilder("INSERT DATA {\n");
            for (int i = 0; i < 1500; i++) {
                insert.append("<http://ex.org/o#Big").append(i).append("> a <http://www.w3.org/2002/07/owl#Class> .\n");
            }
            insert.append("}");
            h.store.update(RuleHistoryHarness.MAIN, insert.toString());
            h.store.update(RuleHistoryHarness.DRAFT, insert.toString());

            for (boolean draft : new boolean[] {false, true}) {
                Model before = h.graph(draft);
                h.create(draft, NAME + draft, "Student>Person");
                Model after = h.graph(draft);
                assertTrue(before.size() > 1000);
                assertTrue(h.lastRow().carriesExactDelta(), "a rule change must always carry its exact change");
                undoAndRedoRestoreExactly(draft, before, after, "Rule \"" + NAME + draft + "\" added");
            }
        } finally {
            Limit.install(Limit.resolve(Limit.Profile.STANDARD, Map.of()));
        }
    }

    @Test
    void aDraftWithoutAUserIsRefusedInsteadOfSilentlyNotRecording() {
        Model before = h.graph(true);

        assertThrows(IllegalArgumentException.class, () -> h.rules.create("p", true, "", "T",
                new RuleInput(NAME, "Student>Person", null, null, null), ChangeOrigin.manual()));
        assertThrows(IllegalArgumentException.class, () -> h.rules.create("p", true, null, "T",
                new RuleInput(NAME, "Student>Person", null, null, null), ChangeOrigin.manual()));
        assertTrue(h.rows.isEmpty());
        assertTrue(same(h.graph(true), before));
    }

    @Test
    void theHistoryFeedTextNamesTheRule() throws Exception {
        String id = h.create(false, NAME, "Student>Person");
        assertEquals("Rule \"" + NAME + "\" added", ChangeSetBroadcaster.describe(h.lastSet(), ChangeOrigin.MANUAL));
        h.update(false, id, new RuleInput(null, "Student>Parent", null, null, null));
        assertEquals("Rule \"" + NAME + "\" changed", ChangeSetBroadcaster.describe(h.lastSet(), ChangeOrigin.MANUAL));
        h.update(false, id, new RuleInput(null, null, null, null, false));
        assertEquals("Rule \"" + NAME + "\" turned off", ChangeSetBroadcaster.describe(h.lastSet(), ChangeOrigin.MANUAL));
        h.update(false, id, new RuleInput(null, null, null, null, true));
        assertEquals("Rule \"" + NAME + "\" turned on", ChangeSetBroadcaster.describe(h.lastSet(), ChangeOrigin.MANUAL));
        h.delete(false, id);
        assertEquals("Rule \"" + NAME + "\" deleted", ChangeSetBroadcaster.describe(h.lastSet(), ChangeOrigin.MANUAL));
        assertEquals(NAME, h.lastRow().getEntityLabel());
    }

    @Test
    void undoingAnEarlierChangeAfterALaterEditNamesTheRule() throws Exception {
        boolean draft = true;
        String id = h.create(draft, NAME, "Student>Person");
        String created = h.lastSetId();
        h.update(draft, id, new RuleInput(null, "Student>Parent", null, null, null));
        String edited = h.lastSetId();
        Model afterEdit = h.graph(draft);

        Result blocked = h.undo(created, draft);

        assertFalse(blocked.ok());
        String reason = blocked.skipped().get(0).reason();
        assertTrue(reason.contains("rule \"" + NAME + "\""), reason);
        assertFalse(reason.contains("codeViewStructuralEdit"), reason);
        assertTrue(same(h.graph(draft), afterEdit));

        assertTrue(h.undo(edited, draft).ok());
        assertTrue(h.undo(created, draft).ok());
        assertTrue(h.rulesIn(draft).isEmpty());
    }

    @Test
    void undoingADeleteIsRefusedWhenAnotherRuleTookTheName() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            String id = h.create(draft, NAME, "Student>Person");
            h.delete(draft, id);
            String deleted = h.lastSetId();
            String taker = h.create(draft, NAME, "Teacher>Parent");
            Model withTaker = h.graph(draft);

            Result refused = h.undo(deleted, draft);

            assertFalse(refused.ok());
            String reason = refused.skipped().get(0).reason();
            assertTrue(reason.contains("Another rule is already named \"" + NAME + "\""), reason);
            assertTrue(same(h.graph(draft), withTaker));

            h.delete(draft, taker);
            Result allowed = h.undo(deleted, draft);
            assertTrue(allowed.ok(), String.valueOf(allowed.skipped()));
            assertEquals(1, h.rulesIn(draft).size());
            h.undo(h.lastSetId(), draft);
        }
    }

    @Test
    void redoingACreateIsRefusedWhenAnotherRuleTookTheName() throws Exception {
        boolean draft = false;
        h.create(draft, NAME, "Student>Person");
        String created = h.lastSetId();
        assertTrue(h.undo(created, draft).ok());
        h.create(draft, NAME, "Teacher>Parent");
        Model withTaker = h.graph(draft);

        Result refused = h.redo(created, draft);

        assertFalse(refused.ok());
        assertTrue(refused.skipped().get(0).reason().contains("Another rule is already named"), String.valueOf(refused.skipped()));
        assertTrue(same(h.graph(draft), withTaker));
    }

    @Test
    void undoingTheAdoptionOfAnIdlessRuleBringsTheIdlessRuleBack() throws Exception {
        SWRLRule plain = RuleTestSupport.plainRule("Student", "Person");
        h.store.update(RuleHistoryHarness.MAIN, OwlAxiomSparqlWriter.toInsertData(Set.of(plain)));
        Model before = h.graph(false);
        String hashId = UnannotatedRules.hashId(h.rulesIn(false).iterator().next());

        h.update(false, hashId, new RuleInput("Adopted", "Student>Person", "now documented", null, null));
        assertTrue(SwrlRuleKeys.ruleId(h.rulesIn(false).iterator().next()).isPresent());
        Model after = h.graph(false);

        undoAndRedoRestoreExactly(false, before, after, "Rule \"Adopted\" changed");
    }

    @Test
    void afterUndoingTheAdoptionNoRuleIdAnnotationIsLeft() throws Exception {
        SWRLRule plain = RuleTestSupport.plainRule("Student", "Person");
        h.store.update(RuleHistoryHarness.MAIN, OwlAxiomSparqlWriter.toInsertData(Set.of(plain)));
        String hashId = UnannotatedRules.hashId(h.rulesIn(false).iterator().next());
        h.update(false, hashId, new RuleInput("Adopted", null, null, null, null));

        assertTrue(h.undo(h.lastSetId(), false).ok());

        Set<SWRLRule> back = h.rulesIn(false);
        assertEquals(1, back.size());
        assertTrue(SwrlRuleKeys.ruleId(back.iterator().next()).isEmpty());
        assertTrue(SwrlRuleKeys.name(back.iterator().next()).isEmpty());
    }

    @Test
    void twoHandWrittenRulesWithTheSameLogicAreNeverBothRemoved() throws Exception {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        SWRLRule plain = RuleTestSupport.plainRule("Student", "Person");
        SWRLRule first = df.getSWRLRule(plain.body().toList(), plain.head().toList(),
                Set.of(df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("first"))));
        SWRLRule second = df.getSWRLRule(plain.body().toList(), plain.head().toList(),
                Set.of(df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("second"))));
        h.store.update(RuleHistoryHarness.MAIN, OwlAxiomSparqlWriter.toInsertData(Set.of(first, second)));
        Model before = h.graph(false);
        String hashId = UnannotatedRules.hashId(first);

        RuleService.RuleAmbiguousException refused = assertThrows(RuleService.RuleAmbiguousException.class,
                () -> h.delete(false, hashId));
        assertTrue(refused.getMessage().contains("cannot tell which one"));
        assertThrows(RuleService.RuleAmbiguousException.class,
                () -> h.update(false, hashId, new RuleInput("Renamed", null, null, null, null)));

        assertTrue(same(h.graph(false), before));
        assertEquals(2, h.rulesIn(false).size());
        assertTrue(h.rows.isEmpty());
    }

    @Test
    void aPublishedRuleCreateStaysUndoableInTheHistory() throws Exception {
        h.create(true, NAME, "Student>Person");

        publishDraftRulesToMain();

        HistoryChange row = h.rows.get(0);
        assertNull(row.getUndoBlockedReason(), String.valueOf(row.getUndoBlockedReason()));
        assertTrue(row.carriesExactDelta());
        assertTrue(h.rows.stream().noneMatch(r -> "publishDraft".equals(r.getOperationType())));
    }

    @Test
    void whenARuleWasEditedAfterItsCreationBothStepsAreBlockedAfterPublishingBecauseTheMiddleStateNeverReachedPublic() throws Exception {
        String id = h.create(true, NAME, "Student>Person");
        h.update(true, id, new RuleInput(null, "Student>Parent", null, null, null));

        publishDraftRulesToMain();

        assertEquals(CodeViewHistoryRecorder.RULE_CHANGE, h.rows.get(0).getOperationType());
        assertEquals(PublishedHistory.CHANGED_IN_PUBLISH, h.rows.get(0).getUndoBlockedReason());
        assertEquals(PublishedHistory.CHANGED_IN_PUBLISH, h.rows.get(1).getUndoBlockedReason());
    }

    @Test
    void aPublishedRuleStaysUndoableWhenPublicAlreadyHoldsTheSameVariableTyping() throws Exception {
        h.create(false, "Teachers are people", "Teacher>Person");
        h.create(true, NAME, "Student>Person");
        HistoryChange draftRow = h.lastRow();

        publishDraftRulesToMain();

        assertNull(draftRow.getUndoBlockedReason(), String.valueOf(draftRow.getUndoBlockedReason()));
        assertTrue(draftRow.carriesExactDelta());
        assertTrue(h.rows.stream().noneMatch(row -> "publishDraft".equals(row.getOperationType())));
    }

    void publishDraftRulesToMain() throws Exception {
        var capture = h.published.begin("p");
        assertTrue(capture.isPresent());
        Set<SWRLRule> draftRules = h.rulesIn(true);
        h.store.update(RuleHistoryHarness.MAIN, OwlAxiomSparqlWriter.toInsertData(draftRules));
        h.published.reconcile("p", RuleHistoryHarness.USER, capture);
    }

    @Test
    void aPullRequestRevertFoldsRuleChangesBackToTheRightGraph() throws Exception {
        Model start = h.graph(true);
        String id = h.create(true, NAME, "Student>Person");
        h.update(true, id, new RuleInput("Pupils are people", "Student>Parent", null, null, null));
        h.create(true, "Teachers are people", "Teacher>Person");
        Model end = h.graph(true);

        Model deleted = new LinkedHashModel();
        Model inserted = new LinkedHashModel();
        for (HistoryChange row : h.rows) {
            PrRevertService.fold(deleted, inserted, ExactReversal.deltaOf(row).orElseThrow());
        }
        ExactDelta folded = new ExactDelta(deleted, inserted);
        h.store.put("urn:graph:copy", start);
        h.store.update("urn:graph:copy", DeltaSparql.forward(folded));

        assertTrue(same(h.store.read("urn:graph:copy"), end));
        h.store.update("urn:graph:copy", DeltaSparql.reverse(folded));
        assertTrue(same(h.store.read("urn:graph:copy"), start));
    }

    @Test
    void theRulesFingerprintAndTheReasonerCacheFollowARuleUndo() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            String empty = OntologyRulesFingerprint.of(h.store.ontology(h.graphFor(draft)));
            h.create(draft, NAME + draft, "Student>Person");
            String withRule = OntologyRulesFingerprint.of(h.store.ontology(h.graphFor(draft)));
            assertNotEquals(empty, withRule);
            Mockito.clearInvocations(h.reasonerCache);

            assertTrue(h.undo(h.lastSetId(), draft).ok());

            assertEquals(empty, OntologyRulesFingerprint.of(h.store.ontology(h.graphFor(draft))));
            assertNotNull(withRule);
            if (draft) {
                verify(h.reasonerCache, atLeastOnce()).invalidateDraft("p", RuleHistoryHarness.USER);
            } else {
                verify(h.reasonerCache, atLeastOnce()).invalidateOntology("p");
            }
        }
    }

    @Test
    void anOldSavedRuleDocumentIsRefusedWithAClearMessage() {
        RollbackAction action = RollbackAction.restoreDocument(DocumentStore.SWRL_RULE.key(), "r1", "{\"_id\":\"r1\",\"projectId\":\"p\"}", null);
        DocumentRollbackActions actions = new DocumentRollbackActions(mock(org.springframework.data.mongodb.core.MongoTemplate.class),
                "http://localhost:1");

        assertFalse(actions.holds("p", action, false, null));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> actions.run("p", action, false, null));
        assertEquals("Saved rules moved into the ontology; undo from the rule list", refused.getMessage());
    }
}
