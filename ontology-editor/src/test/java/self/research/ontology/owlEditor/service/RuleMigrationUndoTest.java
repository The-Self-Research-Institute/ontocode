package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

class RuleMigrationUndoTest {

    static final String BASE = "urn:graph:base";

    RuleHistoryHarness h;
    RuleMigrationService migration;

    @BeforeEach
    void setUp() throws Exception {
        h = new RuleHistoryHarness();
        h.store.copy(RuleHistoryHarness.MAIN, BASE);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "m1");
        row.put("ruleName", "Old rule");
        row.put("ruleText", "Student>Person");
        row.put("enabled", true);
        h.legacy.add(row);
        RuleMigrationService.DraftTargets targets = new RuleMigrationService.DraftTargets() {
            @Override
            public List<String> readyDraftUsers(String projectId) {
                return List.of(RuleHistoryHarness.USER);
            }

            @Override
            public void addToDraft(String projectId, String userId, Set<SWRLRule> axioms) throws Exception {
                h.store.update(RuleHistoryHarness.DRAFT, OwlAxiomSparqlWriter.toInsertData(axioms));
            }

            @Override
            public void addToBaseline(String projectId, String userId, Set<SWRLRule> axioms) throws Exception {
                h.store.update(BASE, OwlAxiomSparqlWriter.toInsertData(axioms));
            }
        };
        migration = new RuleMigrationService(h.client(), h.rules, new ProjectWriteLockRegistry(), targets);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void theMigrationIsOneUndoableEntryWithTheMigrationWording() throws Exception {
        Model before = h.graph(false);

        assertEquals(1, migration.migrate("p").migrated());

        assertEquals(1, h.rows.size());
        HistoryChange row = h.lastRow();
        assertEquals("Migrated saved rule \"Old rule\"", row.getDescription());
        assertEquals(RuleMigrationService.ORIGIN_SOURCE, row.getMetadata().get("toolSource"));
        assertTrue(UndoAbility.of(row).undoable());
        assertTrue(h.undo(row.getChangeSetId(), false).ok());
        assertTrue(Models.isomorphic(h.graph(false), before));
        assertTrue(h.redo(row.getChangeSetId(), false).ok());
        assertEquals(1, h.rulesIn(false).size());
    }

    @Test
    void undoingTheMigrationSaysTheOldCopyStaysArchivedAndAnyLaterListDoesNotBringTheRuleBack() throws Exception {
        migration.migrate("p");
        String changeSet = h.lastSetId();

        Result undone = h.undo(changeSet, false);

        assertTrue(undone.ok(), String.valueOf(undone.skipped()));
        assertEquals("The old saved copy stays archived", undone.message());
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(h.rollbackHistory).recordEdit(anyString(), any(), anyString(), anyString(), any(), any(), any(), any(),
                description.capture(), any(), any(), anyBoolean(), any());
        assertEquals("Undid: Migrated saved rule \"Old rule\". The old saved copy stays archived", description.getValue());
        assertTrue(h.rulesIn(false).isEmpty());
        assertEquals(List.of("m1"), h.legacyMarked);

        assertEquals(0, migration.migrate("p").migrated());
        migration.ensureMigrated("p");
        assertTrue(h.rulesIn(false).isEmpty());
    }

    @Test
    void aDraftCopyCarriedByTheMigrationSurvivesTheUndoQuietlyButPublishingDoesNotBringTheRuleBack() throws Exception {
        migration.migrate("p");
        assertEquals(1, h.rulesIn(true).size());

        assertTrue(h.undo(h.lastSetId(), false).ok());

        assertEquals(1, h.rulesIn(true).size());
        assertTrue(h.rulesIn(false).isEmpty());

        OntologyMergeService merge = new OntologyMergeService(null, null, null, null, null);
        OWLOntology base = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(h.store.read(BASE)));
        OWLOntology draft = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(h.store.read(RuleHistoryHarness.DRAFT)));
        OWLOntology main = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(h.store.read(RuleHistoryHarness.MAIN)));
        Set<String> conflicts = new LinkedHashSet<>(merge.collectTouchedIris(base, main));
        conflicts.retainAll(merge.collectTouchedIris(base, draft));
        assertTrue(conflicts.isEmpty(), String.valueOf(conflicts));

        OWLOntology merged = merge.mergeDraftPublishThreeWay(base, draft, main, conflicts, new HashMap<>(), merge.header(base));

        assertTrue(RuleTestSupport.rulesOf(merged).isEmpty());
        assertNotNull(new HashSet<>(conflicts));
        assertFalse(RuleTestSupport.rulesOf(base).isEmpty());
    }
}
