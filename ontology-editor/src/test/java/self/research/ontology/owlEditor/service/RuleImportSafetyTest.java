package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.RuleService.RuleInput;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static self.research.ontology.owlEditor.service.RuleImportSupport.demoFile;
import static self.research.ontology.owlEditor.service.RuleImportSupport.file;
import static self.research.ontology.owlEditor.service.RuleImportSupport.row;

class RuleImportSafetyTest {

    RuleHistoryHarness h;

    @BeforeEach
    void setUp() throws Exception {
        RuleImportSupport.parseHook = text -> {
        };
        h = new RuleHistoryHarness(x -> RuleImportSupport.client());
    }

    @AfterEach
    void tearDown() {
        RuleImportSupport.parseHook = text -> {
        };
        h.close();
    }

    RuleImportReport run(RuleService service, boolean draft, RuleImportFile file, String importId) throws Exception {
        return service.importRules(RuleHistoryHarness.P, draft, h.user(draft), "Tester", file, importId, null, false,
                ChangeOrigin.manual(), () -> null);
    }

    RuleImportReport run(boolean draft, RuleImportFile file) throws Exception {
        return run(h.rules, draft, file, UUID.randomUUID().toString());
    }

    static boolean same(Model a, Model b) {
        return Models.isomorphic(a, b);
    }

    String ruleIdNamed(boolean draft, String name) {
        return h.rulesIn(draft).stream().filter(r -> name.equals(SwrlRuleKeys.name(r).orElse(null)))
                .map(r -> SwrlRuleKeys.ruleId(r).orElseThrow()).findFirst().orElseThrow();
    }

    @Test
    void aWriteFailureAfterTheGraphWriteLeavesTheGraphAsItWas() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            Model before = h.graph(draft);
            int rows = h.rows.size();
            RuleService failing = new RuleService(RuleImportSupport.client(),
                    (projectId, d, user) -> h.store.ontology(h.graphFor(d)),
                    (projectId, sparql, d, user, name, origin) -> {
                        h.mutations.applyRawUpdate(projectId, sparql, d, user);
                        throw new IllegalStateException("the connection dropped");
                    }, h.ledger);

            assertThrows(RuleService.ImportFailedException.class, () -> run(failing, draft, demoFile("rules.xlsx"),
                    UUID.randomUUID().toString()));

            assertTrue(same(h.graph(draft), before), "the graph must be isomorphic to before");
            assertEquals(rows, h.rows.size(), "no history entry may be left");
            assertTrue(h.rulesIn(draft).isEmpty());
        }
    }

    @Test
    void aFailureWhileWritingTheHistoryLeavesTheGraphAsItWas() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            Model before = h.graph(draft);
            org.mockito.Mockito.doThrow(new IllegalStateException("history store down")).when(h.repository).save(any());

            assertThrows(RuleService.ImportFailedException.class, () -> run(draft, demoFile("rules.xlsx")));

            assertTrue(same(h.graph(draft), before));
            assertTrue(h.rows.isEmpty());
            assertTrue(h.rulesIn(draft).isEmpty());
        }
    }

    @Test
    void afterAFailedImportTheSameFileCanBeImportedAgain() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("history store down")).when(h.repository).save(any());
        String id = UUID.randomUUID().toString();
        assertThrows(RuleService.ImportFailedException.class, () -> run(h.rules, false, demoFile("rules.xlsx"), id));
        org.mockito.Mockito.reset(h.repository);
        h.rows.clear();
        h.wireRepository();

        RuleImportReport report = run(h.rules, false, demoFile("rules.xlsx"), id);

        assertEquals(12, report.imported().size());
        assertFalse(report.replayed());
        assertEquals(12, h.rulesIn(false).size());
    }

    @Test
    void aLostResponseThenARetryWithTheSameImportIdImportsOnce() throws Exception {
        String id = UUID.randomUUID().toString();
        run(h.rules, true, demoFile("rules.xlsx"), id);

        RuleImportReport retry = run(h.rules, true, demoFile("rules.xlsx"), id);

        assertTrue(retry.replayed());
        assertEquals(1, h.rows.size());
        assertEquals(12, h.rulesIn(true).size());
    }

    @Test
    void twoSimultaneousImportsWithTheSameNamesLetExactlyOneWin() throws Exception {
        CyclicBarrier both = new CyclicBarrier(2);
        ThreadLocal<Boolean> waited = ThreadLocal.withInitial(() -> false);
        RuleImportSupport.parseHook = text -> {
            if (!waited.get()) {
                waited.set(true);
                try {
                    both.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<RuleImportReport>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> run(h.rules, false, demoFile("rules.xlsx"), UUID.randomUUID().toString())));
            }
            int winners = 0;
            int losers = 0;
            for (Future<RuleImportReport> future : futures) {
                RuleImportReport report = future.get(60, TimeUnit.SECONDS);
                if (report.imported().size() == 12) {
                    winners++;
                } else {
                    losers++;
                    assertEquals(12, report.duplicates());
                    assertTrue(report.imported().isEmpty());
                }
            }
            assertEquals(1, winners);
            assertEquals(1, losers);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, h.rows.size());
        assertEquals(12, h.rulesIn(false).size());
    }

    void editedRuleBlocksTheWholeUndo(boolean draft, String editor) throws Exception {
        RuleImportReport report = run(draft, demoFile("rules.xlsx"));
        String set = report.changeSetId();
        String id = ruleIdNamed(draft, "Demo 4");
        h.rules.update(RuleHistoryHarness.P, id, draft, editor, "Other", new RuleInput(null, null, "edited later", null, null),
                ChangeOrigin.manual());
        Model afterEdit = h.graph(draft);
        int rulesBefore = h.rulesIn(draft).size();

        Result refused = h.undo(set, draft);

        assertFalse(refused.ok());
        String reason = refused.skipped().get(0).reason();
        assertTrue(reason.contains("Demo 4"), reason);
        assertEquals(rulesBefore, h.rulesIn(draft).size());
        assertEquals(12, h.rulesIn(draft).size(), "all twelve rules remain");
        assertTrue(same(h.graph(draft), afterEdit), "nothing was removed");

        Result redo = h.redo(set, draft);
        assertFalse(redo.ok(), "redo after a refused undo has nothing to redo");
        assertTrue(same(h.graph(draft), afterEdit), "redo left the state untouched");
        HistoryChange entry = h.rows.get(0);
        assertFalse(entry.isReverted());
    }

    @Test
    void aSameUserEditInTheDraftBlocksTheWholeUndoAndNamesTheRule() throws Exception {
        editedRuleBlocksTheWholeUndo(true, RuleHistoryHarness.USER);
    }

    @Test
    void anotherUsersEditInPublicBlocksTheWholeUndoAndSaysSo() throws Exception {
        RuleImportReport report = run(false, demoFile("rules.xlsx"));
        h.rules.update(RuleHistoryHarness.P, ruleIdNamed(false, "Demo 4"), false, "bob@x.com", "Bob",
                new RuleInput(null, null, "bob was here", null, null), ChangeOrigin.manual());
        Model afterEdit = h.graph(false);

        Result refused = h.undo(report.changeSetId(), false);

        assertFalse(refused.ok());
        String reason = refused.skipped().get(0).reason();
        assertTrue(reason.contains("Demo 4"), reason);
        assertTrue(reason.contains("Other people's changes are on top of this one"), reason);
        assertTrue(same(h.graph(false), afterEdit));
        assertEquals(12, h.rulesIn(false).size());
    }

    @Test
    void aSameUserEditInPublicIsRefusedWithTheRuleNamed() throws Exception {
        editedRuleBlocksTheWholeUndo(false, RuleHistoryHarness.OWNER);
    }

    @Test
    void aDeletedImportedRuleBlocksTheWholeUndo() throws Exception {
        RuleImportReport report = run(false, demoFile("rules.xlsx"));
        h.delete(false, ruleIdNamed(false, "Demo 2"));
        Model afterDelete = h.graph(false);

        Result refused = h.undo(report.changeSetId(), false);

        assertFalse(refused.ok());
        assertTrue(refused.skipped().get(0).reason().contains("Demo 2"), refused.skipped().get(0).reason());
        assertTrue(same(h.graph(false), afterDelete));
        assertEquals(11, h.rulesIn(false).size());
    }

    @Test
    void anUntouchedImportUndoesEvenWhenOtherRulesAreAddedLater() throws Exception {
        RuleImportReport report = run(false, demoFile("rules.xlsx"));
        h.create(false, "Later rule", "Teacher>Person");

        Result undone = h.undo(report.changeSetId(), false);

        assertTrue(undone.ok(), String.valueOf(undone.skipped()));
        assertEquals(1, h.rulesIn(false).size());
    }

    @Test
    void redoIsRefusedWhenAnotherRuleTookAnImportedName() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            RuleImportReport report = run(draft, file("two.xlsx", row(2, "Alpha", "Student>Person"),
                    row(3, "Beta", "Parent>Person")));
            assertTrue(h.undo(report.changeSetId(), draft).ok());
            h.create(draft, "beta", "Teacher>Parent");
            Model withTaker = h.graph(draft);

            Result refused = h.redo(report.changeSetId(), draft);

            assertFalse(refused.ok());
            assertTrue(refused.skipped().get(0).reason().contains("Another rule is already named"),
                    String.valueOf(refused.skipped()));
            assertTrue(same(h.graph(draft), withTaker), "the graph stayed as it was");
            h.undo(h.lastSetId(), draft);
            h.undo(report.changeSetId(), draft);
        }
    }
}
