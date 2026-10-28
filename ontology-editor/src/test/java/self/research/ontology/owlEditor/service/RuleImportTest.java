package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.RuleService.RuleInput;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.owlEditor.service.RuleImportSupport.demoFile;
import static self.research.ontology.owlEditor.service.RuleImportSupport.file;
import static self.research.ontology.owlEditor.service.RuleImportSupport.row;

class RuleImportTest {

    RuleHistoryHarness h;

    @BeforeEach
    void setUp() throws Exception {
        RuleImportSupport.parseHook = text -> {
        };
        h = new RuleHistoryHarness(x -> RuleImportSupport.client());
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

    RuleImportReport run(boolean draft, RuleImportFile file, String importId, boolean dry, String stamp) throws Exception {
        return h.rules.importRules(RuleHistoryHarness.P, draft, h.user(draft), "Tester", file, importId, stamp, dry,
                ChangeOrigin.manual(), () -> null);
    }

    RuleImportReport run(boolean draft, RuleImportFile file) throws Exception {
        return run(draft, file, newId(), false, null);
    }

    static boolean same(Model a, Model b) {
        return Models.isomorphic(a, b);
    }

    static String diff(Model a, Model b) {
        Model extra = new org.eclipse.rdf4j.model.impl.LinkedHashModel(a);
        extra.removeAll(b);
        return extra.size() > 12 ? extra.size() + " triples" : extra.toString();
    }

    Set<String> names(boolean draft) {
        return h.rulesIn(draft).stream().map(r -> SwrlRuleKeys.name(r).orElse("-")).collect(Collectors.toSet());
    }

    void twelveDemoRules(boolean draft) throws Exception {
        Model before = h.graph(draft);
        RuleImportReport report = run(draft, demoFile("rules.xlsx"));

        assertEquals(12, report.read());
        assertEquals(12, report.imported().size());
        assertTrue(report.skipped().isEmpty());
        assertEquals(12, h.rulesIn(draft).size());
        assertEquals(1, h.rows.size(), "the whole import is one history entry");
        HistoryChange row = h.lastRow();
        assertEquals("Imported 12 rules from rules.xlsx", row.getDescription());
        assertEquals(CodeViewHistoryRecorder.RULE_CHANGE, row.getOperationType());
        assertEquals(report.changeSetId(), row.getChangeSetId());
        assertTrue(row.carriesExactDelta());
        Model after = h.graph(draft);
        long disabled = h.rulesIn(draft).stream().filter(self.research.ontology.common.rules.DisabledRules::isDisabled).count();
        assertEquals(1, disabled);

        Result undone = h.undo(row.getChangeSetId(), draft);
        assertTrue(undone.ok(), String.valueOf(undone.skipped()));
        Model left = h.graph(draft);
        assertTrue(same(left, before), "one undo removes all twelve rules; extra=" + diff(left, before) + " missing=" + diff(before, left));
        assertTrue(h.rulesIn(draft).isEmpty());

        Result redone = h.redo(row.getChangeSetId(), draft);
        assertTrue(redone.ok(), String.valueOf(redone.skipped()));
        assertTrue(same(h.graph(draft), after), "one redo brings all twelve back");
        assertEquals(12, h.rulesIn(draft).size());
        assertEquals(1, h.rows.stream().filter(r -> CodeViewHistoryRecorder.RULE_CHANGE.equals(r.getOperationType())).count());
    }

    @Test
    void twelveDemoRulesArePublicOneEntryOneUndoOneRedo() throws Exception {
        twelveDemoRules(false);
    }

    @Test
    void twelveDemoRulesInADraftAreOneEntryOneUndoOneRedo() throws Exception {
        twelveDemoRules(true);
    }

    @Test
    void importedEnabledStateAndCommentTravelAsAnnotations() throws Exception {
        RuleImportFile.Row off = new RuleImportFile.Row("Rules", 2, "Off rule", "Student>Person", "why", "People", false, null, null);
        run(false, file("a.xlsx", off));

        SWRLRule rule = h.rulesIn(false).iterator().next();
        assertTrue(self.research.ontology.common.rules.DisabledRules.isDisabled(rule));
        assertEquals("why", RuleService.commentOf(rule));
        assertEquals("People", RuleService.annotationText(rule, self.research.ontology.common.rules.DisabledRules.CATEGORY));
    }

    @Test
    void aPartialImportCountsOnlyTheCommittedRules() throws Exception {
        h.create(true, "Existing", "Teacher>Person");
        int before = h.rows.size();
        RuleImportFile.Row formula = new RuleImportFile.Row("Rules", 7, "Formula row", null, null, null, true,
                RuleImportFile.KIND_FORMULA, "Cell B7 is a formula");
        RuleImportReport report = run(true, file("mix.xlsx",
                row(2, "Good one", "Student>Person"),
                row(3, "existing", "Student>Parent"),
                row(4, "Good two", "Parent>Person"),
                row(5, "GOOD ONE", "Student>Adult"),
                row(6, "Broken", "garbage"),
                formula));

        assertEquals(6, report.read());
        assertEquals(2, report.imported().size());
        assertEquals(2, report.duplicates());
        assertEquals(2, report.invalid());
        assertEquals(List.of(3, 5, 6, 7), report.skipped().stream().map(RuleImportReport.Skipped::row).toList());
        assertEquals("duplicate", report.skipped().get(0).kind());
        assertEquals("invalid", report.skipped().get(2).kind());
        assertEquals("formula_cell", report.skipped().get(3).kind());
        assertEquals(before + 1, h.rows.size());
        assertEquals("Imported 2 rules from mix.xlsx", h.lastRow().getDescription());
    }

    @Test
    void nothingToImportWritesNothing() throws Exception {
        Model before = h.graph(false);
        RuleImportReport report = run(false, file("none.xlsx", row(2, "Broken", "garbage")));

        assertTrue(report.imported().isEmpty());
        assertNull(report.changeSetId());
        assertTrue(h.rows.isEmpty());
        assertTrue(same(h.graph(false), before));
    }

    @Test
    void aDryRunWritesNothingAndGivesAStamp() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            Model before = h.graph(draft);
            RuleImportReport report = run(draft, demoFile("rules.xlsx"), null, true, null);

            assertTrue(report.dryRun());
            assertEquals(12, report.imported().size());
            assertNotNull(report.previewStamp());
            assertTrue(h.rows.isEmpty());
            assertTrue(same(h.graph(draft), before));
        }
    }

    @Test
    void aDryRunThenTheSameImportGoesThrough() throws Exception {
        RuleImportReport preview = run(false, demoFile("rules.xlsx"), null, true, null);
        h.create(false, "Unrelated", "Teacher>Person");

        RuleImportReport real = run(false, demoFile("rules.xlsx"), newId(), false, preview.previewStamp());

        assertEquals(12, real.imported().size());
        assertFalse(real.stale());
    }

    @Test
    void aStalePreviewThatChangesTheOutcomeIsRefusedWithTheFreshReport() throws Exception {
        RuleImportFile rules = file("two.xlsx", row(2, "First", "Student>Person"), row(3, "Second", "Parent>Person"));
        RuleImportReport preview = run(false, rules, null, true, null);
        assertEquals(2, preview.imported().size());
        h.create(false, "Second", "Teacher>Person");
        Model before = h.graph(false);
        int rows = h.rows.size();

        RuleService.ImportStaleException stale = assertThrows(RuleService.ImportStaleException.class,
                () -> run(false, rules, newId(), false, preview.previewStamp()));

        assertTrue(stale.report().stale());
        assertEquals(1, stale.report().duplicates());
        assertEquals(1, stale.report().imported().size());
        assertTrue(stale.getMessage().contains("changed since the preview"), stale.getMessage());
        assertNotNull(stale.report().previewStamp());
        assertTrue(same(h.graph(false), before));
        assertEquals(rows, h.rows.size());

        RuleImportReport confirmed = run(false, rules, newId(), false, stale.report().previewStamp());
        assertEquals(1, confirmed.imported().size());
    }

    @Test
    void aPreviewOfAnotherFileIsRefused() throws Exception {
        RuleImportReport preview = run(false, file("a.xlsx", row(2, "A", "Student>Person")), null, true, null);

        assertThrows(IllegalArgumentException.class,
                () -> run(false, file("b.xlsx", row(2, "B", "Student>Person")), newId(), false, preview.previewStamp()));
    }

    @Test
    void unknownAndAmbiguousEntitiesAreRowErrorsWithTheirRowNumbers() throws Exception {
        h.store.update(RuleHistoryHarness.MAIN, "INSERT DATA { <http://a.org/o#Dup> a <http://www.w3.org/2002/07/owl#Class> . "
                + "<http://b.org/o#Dup> a <http://www.w3.org/2002/07/owl#Class> . }");
        RuleImportReport report = run(false, file("e.xlsx",
                row(2, "Typo", "bad:Chlid"),
                row(3, "Clash", "bad:Dup"),
                row(4, "Fine", "Student>Person")));

        assertEquals(1, report.imported().size());
        RuleImportReport.Skipped typo = report.skipped().get(0);
        assertEquals("unknown_entity", typo.kind());
        assertEquals(2, typo.row());
        assertEquals("Rules", typo.sheet());
        assertTrue(typo.reason().contains("Chlid"), typo.reason());
        assertEquals(List.of("Did you mean: Student, Parent?"), typo.suggestions());
        RuleImportReport.Skipped clash = report.skipped().get(1);
        assertEquals("ambiguous_entity", clash.kind());
        assertEquals(3, clash.row());
        assertTrue(clash.reason().contains("http://a.org/o#") && clash.reason().contains("http://b.org/o#"), clash.reason());
    }

    @Test
    void aPrefixOfTheFileIsUsedToReadItsRulesButNeverChangesTheProject() throws Exception {
        Model before = h.graph(false);
        RuleImportReport report = run(false, file("p.xlsx", Map.of("ex", RuleTestSupport.NS),
                row(2, "Prefixed", "ex:Student(?x) -> ex:Person(?x)")));

        assertEquals(1, report.imported().size(), String.valueOf(report.skipped()));
        SWRLRule rule = h.rulesIn(false).iterator().next();
        assertTrue(rule.getAxiomWithoutAnnotations().toString().contains(RuleTestSupport.NS + "Student"));
        Model after = h.graph(false);
        assertFalse(after.getNamespaces().stream().anyMatch(ns -> "ex".equals(ns.getPrefix())));
        assertEquals(before.getNamespaces(), after.getNamespaces());
    }

    @Test
    void aPrefixBoundToAnotherNamespaceInTheProjectIsARowErrorNamingBoth() {
        Map<String, String> project = Map.of("ex", "http://project.org/ns#");
        Map<String, String> declared = Map.of("ex", "http://file.org/ns#");

        RuleImporter.Resolved resolved = RuleImporter.resolve("ex:Student(?x) -> ex:Person(?x)", project, declared);

        assertNotNull(resolved.conflict());
        assertTrue(resolved.conflict().contains("http://file.org/ns#") && resolved.conflict().contains("http://project.org/ns#"));
        assertNull(RuleImporter.resolve("ex:Student(?x) -> ex:Person(?x)", project, Map.of("ex", "http://project.org/ns#")).conflict());
        assertNull(RuleImporter.resolve("<http://x.org/ex:A>(?x) -> B(?x)", project, declared).conflict());
    }

    @Test
    void aDifferentDefaultNamespaceIsAConflictOnlyForRulesThatUseBareNames() throws Exception {
        RuleImportReport report = run(false, file("d.xlsx", Map.of("", "http://elsewhere.org/x#"),
                row(2, "Bare", "Student(?x) -> Person(?x)")));

        assertTrue(report.imported().isEmpty());
        assertEquals("prefix_conflict", report.skipped().get(0).kind());
        assertTrue(report.skipped().get(0).reason().contains("http://elsewhere.org/x#"));
        assertNull(RuleImporter.resolve("sameAs(?x, ?y)", Map.of("", "http://a/"), Map.of("", "http://b/")).conflict());
    }

    @Test
    void theSameImportIdReturnsTheOriginalResultWithoutWriting() throws Exception {
        String id = newId();
        RuleImportReport first = run(false, demoFile("rules.xlsx"), id, false, null);
        Model after = h.graph(false);

        RuleImportReport retry = run(false, demoFile("rules.xlsx"), id, false, null);

        assertTrue(retry.replayed());
        assertFalse(first.replayed());
        assertEquals(first.changeSetId(), retry.changeSetId());
        assertEquals(first.imported(), retry.imported());
        assertEquals(1, h.rows.size());
        assertTrue(same(h.graph(false), after));
    }

    @Test
    void theSameImportIdWithDifferentContentIsRejected() throws Exception {
        String id = newId();
        run(false, file("a.xlsx", row(2, "A", "Student>Person")), id, false, null);
        int rows = h.rows.size();

        RuleService.ImportConflictException refused = assertThrows(RuleService.ImportConflictException.class,
                () -> run(false, file("a.xlsx", row(2, "B", "Student>Parent")), id, false, null));

        assertTrue(refused.getMessage().contains("different file"));
        assertEquals(rows, h.rows.size());
        assertEquals(Set.of("A"), names(false));
    }

    @Test
    void aRetryStillReturnsTheOriginalResultAfterTheImportWasUndone() throws Exception {
        String id = newId();
        RuleImportReport first = run(false, demoFile("rules.xlsx"), id, false, null);
        assertTrue(h.undo(first.changeSetId(), false).ok());

        RuleImportReport retry = run(false, demoFile("rules.xlsx"), id, false, null);

        assertTrue(retry.replayed());
        assertTrue(h.rulesIn(false).isEmpty());
    }

    @Test
    void anImportIdIsRequiredForARealImport() {
        assertThrows(IllegalArgumentException.class, () -> run(false, demoFile("r.xlsx"), null, false, null));
        assertThrows(IllegalArgumentException.class, () -> run(false, demoFile("r.xlsx"), "x", false, null));
        assertTrue(h.rows.isEmpty());
    }

    @Test
    void aForbiddenCallerImportsNothing() {
        assertThrows(RuleService.ImportForbiddenException.class, () -> h.rules.importRules(RuleHistoryHarness.P, false,
                RuleHistoryHarness.OWNER, "T", demoFile("r.xlsx"), newId(), null, false, ChangeOrigin.manual(),
                () -> "You have view-only access to this project."));
        assertTrue(h.rows.isEmpty());
        assertTrue(h.rulesIn(false).isEmpty());
    }

    @Test
    void aDraftImportWithoutAUserIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> h.rules.importRules(RuleHistoryHarness.P, true, "", "T",
                demoFile("r.xlsx"), newId(), null, false, ChangeOrigin.manual(), () -> null));
    }

    @Test
    void aSingleRuleImportReadsAsOneRule() throws Exception {
        run(false, file("one.json", row(1, "Solo", "Student>Person")));

        assertEquals("Imported 1 rule from one.json", h.lastRow().getDescription());
        assertEquals(Set.of("Solo"), names(false));
        h.update(false, SwrlRuleKeys.ruleId(h.rulesIn(false).iterator().next()).orElseThrow(),
                new RuleInput(null, "Student>Parent", null, null, null));
    }
}
