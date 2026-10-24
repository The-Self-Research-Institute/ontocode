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
        assertEquals("pr-entry#1", out.get(1).get("id"));
    }

    @Test
    void aMergedPrWithNoTaggedRowsStaysAsTheSingleEntry() {
        Map<String, Object> base = toMap(entry());

        List<Map<String, Object>> out = PrFeedExpander.expand(entry(), base, prId -> List.of(), PrFeedExpanderTest::toMap);

        assertEquals(List.of(base), out);
    }

    @Test
    void otherEntriesAreNotMergedPrEntries() {
        assertFalse(PrFeedExpander.isMergedPrEntry(row("r1", "A")));
        assertTrue(PrFeedExpander.isMergedPrEntry(entry()));
        assertEquals("7", PrFeedExpander.prIdOf(entry()));
    }
}
