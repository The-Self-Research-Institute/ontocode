package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.RuleService.RuleInput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleRenameTest {

    RuleHistoryHarness h;

    @BeforeEach
    void setUp() throws Exception {
        h = new RuleHistoryHarness();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    SWRLRule only(boolean draft) {
        return h.rulesIn(draft).iterator().next();
    }

    void renameOnly(boolean draft) throws Exception {
        String id = h.create(draft, "Alpha", "Student>Person");
        SWRLRule before = only(draft);
        Model beforeGraph = h.graph(draft);

        h.update(draft, id, new RuleInput("Beta", "", null, null, null));

        assertEquals("Beta", SwrlRuleKeys.name(only(draft)).orElseThrow());
        assertEquals(id, SwrlRuleKeys.ruleId(only(draft)).orElseThrow(), "the rule keeps its id");
        assertEquals(before.getAxiomWithoutAnnotations(), only(draft).getAxiomWithoutAnnotations(), "the text is untouched");
        assertEquals("Rule \"Alpha\" renamed to \"Beta\"", h.lastRow().getDescription());
        assertEquals(1, h.lastSet().size());
        Model renamed = h.graph(draft);

        Result undone = h.undo(h.lastSetId(), draft);
        assertTrue(undone.ok(), String.valueOf(undone.skipped()));
        assertEquals("Alpha", SwrlRuleKeys.name(only(draft)).orElseThrow());
        assertTrue(Models.isomorphic(h.graph(draft), beforeGraph));

        Result redone = h.redo(h.lastSetId(), draft);
        assertTrue(redone.ok(), String.valueOf(redone.skipped()));
        assertEquals("Beta", SwrlRuleKeys.name(only(draft)).orElseThrow());
        assertTrue(Models.isomorphic(h.graph(draft), renamed));
    }

    @Test
    void renamingAPublicRuleKeepsItsIdAndTextAndUndoesAndRedoes() throws Exception {
        renameOnly(false);
    }

    @Test
    void renamingADraftRuleKeepsItsIdAndTextAndUndoesAndRedoes() throws Exception {
        renameOnly(true);
    }

    @Test
    void renamingWithoutRuleTextAtAllAlsoWorks() throws Exception {
        String id = h.create(false, "Alpha", "Student>Person");

        h.update(false, id, new RuleInput("Beta", null, null, null, null));

        assertEquals("Beta", SwrlRuleKeys.name(only(false)).orElseThrow());
        assertEquals("Rule \"Alpha\" renamed to \"Beta\"", h.lastRow().getDescription());
    }

    @Test
    void aRenameTogetherWithATextEditIsOneChangeSetThatSaysRenamed() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            String id = h.create(draft, "Alpha" + draft, "Student>Person");
            Model before = h.graph(draft);

            h.update(draft, id, new RuleInput("Gamma" + draft, "Student>Parent", null, null, null));

            assertEquals(1, h.lastSet().size());
            assertEquals("Rule \"Alpha" + draft + "\" renamed to \"Gamma" + draft + "\"", h.lastRow().getDescription());
            assertTrue(h.undo(h.lastSetId(), draft).ok());
            assertTrue(Models.isomorphic(h.graph(draft), before));
            assertTrue(h.redo(h.lastSetId(), draft).ok());
            assertEquals("Gamma" + draft, SwrlRuleKeys.name(only(draft)).orElseThrow());
        }
    }

    @Test
    void renamingToANameAnotherRuleHasIsRefusedWhateverTheCase() throws Exception {
        h.create(false, "Alpha", "Student>Person");
        String second = h.create(false, "Beta", "Parent>Person");
        Model before = h.graph(false);
        int rows = h.rows.size();

        RuleService.RuleNameTakenException refused = assertThrows(RuleService.RuleNameTakenException.class,
                () -> h.update(false, second, new RuleInput("  alpha ", "", null, null, null)));

        assertEquals("A rule named \"alpha\" already exists. Choose a different name.", refused.getMessage());
        assertTrue(Models.isomorphic(h.graph(false), before));
        assertEquals(rows, h.rows.size());
    }

    @Test
    void aRuleMayBeRenamedToItsOwnNameInAnotherCase() throws Exception {
        String id = h.create(false, "Alpha", "Student>Person");

        h.update(false, id, new RuleInput("ALPHA", "", null, null, null));

        assertEquals("ALPHA", SwrlRuleKeys.name(only(false)).orElseThrow());
        assertEquals("Rule \"Alpha\" renamed to \"ALPHA\"", h.lastRow().getDescription());
    }

    @Test
    void savingWithTheSameNameIsNotARename() throws Exception {
        String id = h.create(false, "Alpha", "Student>Person");

        h.update(false, id, new RuleInput("Alpha", "Student>Parent", null, null, null));

        assertEquals("Rule \"Alpha\" changed", h.lastRow().getDescription());
    }
}
