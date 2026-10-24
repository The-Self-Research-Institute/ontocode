package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrFeedExpanderTest {

    private static HistoryChange entry() {
        HistoryChange change = new HistoryChange("p", "e1", "owner", "owner");
        change.setId("pr-entry");
        change.setOperationType("prPublished");
        change.setEntityIRI("urn:ontocode:pr:7");
        change.setEntityLabel("Merged PR: Pizza toppings");
        return change;
    }

    private static HistoryChange row(String id, String label) {
        HistoryChange change = new HistoryChange("p", "e-" + id, "ded", "ded");
        change.setId(id);
        change.setOperationType("createClass");
        change.setEntityLabel(label);
        return change;
    }

    private static Map<String, Object> toMap(HistoryChange change) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", change.getId());
        map.put("entityLabel", change.getEntityLabel());
        map.put("operationType", change.getOperationType());
        return map;
    }

    @Test
    void aMergedPrBecomesOneEntryPerItemUnderOneSharedSet() {
        Map<String, Object> base = toMap(entry());

        List<Map<String, Object>> out = PrFeedExpander.expand(entry(), base,
                prId -> "7".equals(prId) ? List.of(row("r1", "Pizzanew"), row("r2", "Pizzahut")) : List.of(), PrFeedExpanderTest::toMap);

        assertEquals(2, out.size());
        assertEquals("pr:7", out.get(0).get("changeSetId"));
        assertEquals("pr:7", out.get(1).get("changeSetId"));
        assertEquals("Merged PR: Pizza toppings", out.get(0).get("description"));
        assertEquals(true, out.get(0).get("setOnly"));
        assertEquals(false, out.get(0).get("undoable"));
        assertEquals("pr-entry#0", out.get(0).get("id"));
        assertEquals(false, out.get(0).get("mergedPrReverted"));
        assertEquals("pr-entry#1", out.get(1).get("id"));
    }

    @Test
    void theItemsTellTheClientWhenThePrWasReverted() {
        HistoryChange reverted = entry();
        reverted.setReverted(true);

        List<Map<String, Object>> out = PrFeedExpander.expand(reverted, toMap(reverted),
                prId -> List.of(row("r1", "Pizzanew")), PrFeedExpanderTest::toMap);

        assertEquals(true, out.get(0).get("mergedPrReverted"));
    }

    @Test
    void aVeryLargePrShowsAMarkerForTheItemsLeftOut() {
        List<HistoryChange> many = new java.util.ArrayList<>();
        for (int i = 0; i < PrFeedExpander.MAX_ITEMS + 5; i++) {
            many.add(row("r" + i, "Item" + i));
        }

        List<Map<String, Object>> out = PrFeedExpander.expand(entry(), toMap(entry()), prId -> many, PrFeedExpanderTest::toMap);

        assertEquals(PrFeedExpander.MAX_ITEMS + 1, out.size());
        Map<String, Object> marker = out.get(out.size() - 1);
        assertEquals("pr-entry#more", marker.get("id"));
        assertEquals("5 more items not shown", marker.get("entityLabel"));
        assertEquals("pr:7", marker.get("changeSetId"));
    }

    @Test
    void aMergedPrWithNoTaggedRowsStaysAsTheSingleEntry() {
        Map<String, Object> base = toMap(entry());

        List<Map<String, Object>> out = PrFeedExpander.expand(entry(), base, prId -> List.of(), PrFeedExpanderTest::toMap);

        assertEquals(List.of(base), out);
    }

    @Test
    void otherEntriesAreNotMergedPrEntries() {
        assertFalse(PrEntries.isMergedPrEntry(row("r1", "A")));
        assertTrue(PrEntries.isMergedPrEntry(entry()));
        assertEquals("7", PrEntries.prIdOf(entry().getEntityIRI()));
    }
}
