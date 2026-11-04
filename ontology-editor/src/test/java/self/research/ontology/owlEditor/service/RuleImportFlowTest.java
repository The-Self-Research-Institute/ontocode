package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;
import self.research.ontology.owlEditor.service.spreadsheet.RuleSpreadsheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static self.research.ontology.owlEditor.service.RuleImportSupport.demoFile;

class RuleImportFlowTest {

    RuleHistoryHarness h;
    RuleHistoryHarness clean;

    @BeforeEach
    void setUp() throws Exception {
        RuleImportSupport.parseHook = text -> {
        };
        RuleImportSupport.embedded = List::of;
        h = new RuleHistoryHarness(x -> RuleImportSupport.client());
        clean = new RuleHistoryHarness(x -> RuleImportSupport.client());
    }

    @AfterEach
    void tearDown() {
        RuleImportSupport.embedded = List::of;
        h.close();
        clean.close();
    }

    static String textOf(SWRLRule rule) {
        String body = rule.body().map(atom -> ((SWRLClassAtom) atom).getPredicate().asOWLClass().getIRI().getShortForm())
                .collect(Collectors.joining(","));
        String head = rule.head().map(atom -> ((SWRLClassAtom) atom).getPredicate().asOWLClass().getIRI().getShortForm())
                .collect(Collectors.joining(","));
        return body + ">" + head;
    }

    List<Map<String, Object>> listing(RuleHistoryHarness harness, boolean draft) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SWRLRule rule : harness.rulesIn(draft)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", SwrlRuleKeys.ruleId(rule).orElse(null));
            item.put("ruleName", SwrlRuleKeys.name(rule).orElse(null));
            item.put("ruleText", textOf(rule));
            item.put("comment", RuleService.commentOf(rule));
            item.put("category", RuleService.annotationText(rule, DisabledRules.CATEGORY));
            item.put("enabled", !DisabledRules.isDisabled(rule));
            item.put("source", null);
            item.put("readOnly", false);
            items.add(item);
        }
        Map<String, Object> fromOntology = new HashMap<>();
        fromOntology.put("id", "x");
        fromOntology.put("ruleName", "From an import");
        fromOntology.put("ruleText", "A>B");
        fromOntology.put("source", "imported-ontology");
        fromOntology.put("readOnly", true);
        items.add(fromOntology);
        return items;
    }

    RuleImportReport importFile(RuleHistoryHarness harness, boolean draft, RuleImportFile file) throws Exception {
        return harness.rules.importRules(RuleHistoryHarness.P, draft, harness.user(draft), "T", file,
                UUID.randomUUID().toString(), null, false, ChangeOrigin.manual(), () -> null);
    }

    @Test
    void anExportedWorkbookImportsIntoACleanProjectWithTheSameRulesFlagsAndPrefixes() throws Exception {
        for (boolean draft : new boolean[] {false, true}) {
            RuleImportFile.Row off = new RuleImportFile.Row("Rules", 3, "Off rule", "Teacher>Person", "", "", false, null, null);
            importFile(h, draft, RuleImportSupport.file("a.xlsx",
                    new RuleImportFile.Row("Rules", 2, "Students are people", "Student>Person", "every student", "People", true, null, null),
                    off));
            RuleImportSupport.embedded = () -> listing(h, draft);

            RuleImporter.Export export = h.rules.exportRules(RuleHistoryHarness.P, draft, h.user(draft));

            assertEquals(2, export.rules().size());
            assertEquals(1, export.leftOut(), "rules that came from an imported ontology are left out");
            assertEquals(RuleTestSupport.ONTO + "#", export.prefixes().get(""));
            byte[] workbook = RuleSpreadsheet.writeXlsx(export.rules(), export.prefixes());

            RuleImportFile reread = RuleSpreadsheet.read(workbook, "swrl-rules.xlsx");
            assertEquals(export.prefixes(), reread.prefixes());
            RuleImportReport report = importFile(clean, draft, reread);

            assertEquals(2, report.imported().size(), String.valueOf(report.skipped()));
            Map<String, SWRLRule> original = h.rulesIn(draft).stream().collect(Collectors.toMap(r -> SwrlRuleKeys.name(r).orElseThrow(), r -> r));
            Map<String, SWRLRule> copy = clean.rulesIn(draft).stream().collect(Collectors.toMap(r -> SwrlRuleKeys.name(r).orElseThrow(), r -> r));
            assertEquals(original.keySet(), copy.keySet());
            for (String name : original.keySet()) {
                assertEquals(original.get(name).getAxiomWithoutAnnotations(), copy.get(name).getAxiomWithoutAnnotations());
                assertEquals(DisabledRules.isDisabled(original.get(name)), DisabledRules.isDisabled(copy.get(name)), name);
                assertEquals(RuleService.commentOf(original.get(name)), RuleService.commentOf(copy.get(name)));
                assertFalse(SwrlRuleKeys.ruleId(original.get(name)).equals(SwrlRuleKeys.ruleId(copy.get(name))), "new ids");
            }
            clean.undo(clean.lastSetId(), draft);
            h.undo(h.lastSetId(), draft);
        }
    }

    @Test
    void theJsonExportImportsToo() throws Exception {
        importFile(h, false, RuleImportSupport.file("a.json",
                new RuleImportFile.Row("json", 1, "Alpha", "Student>Person", "", "", false, null, null)));
        RuleImportSupport.embedded = () -> listing(h, false);
        RuleImporter.Export export = h.rules.exportRules(RuleHistoryHarness.P, false, null);

        RuleImportFile reread = RuleSpreadsheet.read(RuleSpreadsheet.writeJson(export.rules(), export.prefixes()), "x.json");
        RuleImportReport report = importFile(clean, false, reread);

        assertEquals(1, report.imported().size());
        assertTrue(DisabledRules.isDisabled(clean.rulesIn(false).iterator().next()));
    }

    PrSnapshotService snapshots(String frozenGraph) throws Exception {
        DraftSessionRepository sessions = mock(DraftSessionRepository.class);
        DraftPublishMergeService merge = mock(DraftPublishMergeService.class);
        DraftTrackingService tracking = mock(DraftTrackingService.class);
        DraftSession author = new DraftSession(RuleHistoryHarness.P, RuleHistoryHarness.USER, 1, 1);
        when(sessions.findByProjectIdAndUserId(RuleHistoryHarness.P, RuleHistoryHarness.USER)).thenReturn(Optional.of(author));
        when(merge.baselineOf(RuleHistoryHarness.P, RuleHistoryHarness.USER)).thenReturn(Optional.of("<rdf/>"));
        when(merge.writeBaselineFor(eq(RuleHistoryHarness.P), anyString(), anyString())).thenReturn("baseline.owl");
        when(tracking.getUnappliedDraftsForUser(anyString(), anyString())).thenReturn(List.of());
        doAnswer(inv -> {
            h.store.copy(RuleHistoryHarness.DRAFT, frozenGraph);
            return 0;
        }).when(h.datasets).copyDraftGraph(RuleHistoryHarness.P, RuleHistoryHarness.USER, "pr-snapshot-pr1");
        return new PrSnapshotService(sessions, h.datasets, merge, mock(DraftPublishService.class), h.locks, tracking,
                mock(DraftPullRequestRepository.class));
    }

    @Test
    void aFrozenPullRequestSnapshotIsNotChangedByALaterImportOrItsUndo() throws Exception {
        String frozenGraph = "urn:graph:pr:one";
        PrSnapshotService service = snapshots(frozenGraph);
        h.create(true, "Raised with the PR", "Teacher>Person");
        PrSnapshotService.Frozen frozen = service.freeze(RuleHistoryHarness.P, RuleHistoryHarness.USER, "pr1");
        assertEquals("pr-snapshot-pr1", frozen.key());
        Model snapshot = h.store.read(frozenGraph);
        Model draftBefore = h.graph(true);
        assertTrue(Models.isomorphic(snapshot, draftBefore));

        RuleImportReport report = importFile(h, true, demoFile("rules.xlsx"));

        assertEquals(12, report.imported().size());
        assertFalse(Models.isomorphic(h.graph(true), draftBefore));
        assertTrue(Models.isomorphic(h.store.read(frozenGraph), snapshot), "the PR snapshot did not move");

        assertTrue(h.undo(report.changeSetId(), true).ok());

        assertTrue(Models.isomorphic(h.store.read(frozenGraph), snapshot), "undo left the PR snapshot alone");
        assertTrue(Models.isomorphic(h.graph(true), draftBefore));
        assertEquals(Set.of("Raised with the PR"), h.rulesIn(true).stream()
                .map(r -> SwrlRuleKeys.name(r).orElse("-")).collect(Collectors.toSet()));
        Mockito.verify(h.datasets).copyDraftGraph(RuleHistoryHarness.P, RuleHistoryHarness.USER, "pr-snapshot-pr1");
    }
}
