package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeFeedExpanderTest {

    private static HistoryChange merge(String deleted, String inserted) {
        HistoryChange change = new HistoryChange.Builder("p", "e1", "owner", "owner")
                .operationType(OntologyMergeService.MERGE_OPERATION).build();
        change.setId("m1");
        change.setExactDeleted(deleted);
        change.setExactInserted(inserted);
        return change;
    }

    private static Map<String, Object> base() {
        Map<String, Object> base = new HashMap<>();
        base.put("id", "m1");
        base.put("entityLabel", "Ontology merge");
        base.put("operationType", "ontologyMerge");
        return base;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMergeBecomesOneEntryPerItemWithItsPropertyRows() {
        String inserted = "<http://x#Hawaiian> <http://www.w3.org/2000/01/rdf-schema#label> \"Hawaiian\" .\n"
                + "<http://x#Hawaiian> <http://x#hasBase> <http://x#DeepPan> .\n"
                + "<http://x#Margherita> <http://x#hasBase> <http://x#ThinBase> .\n";
        String deleted = "<http://x#Margherita> <http://x#hasBase> <http://x#OldBase> .\n";

        List<Map<String, Object>> entries = MergeFeedExpander.expand(merge(deleted, inserted), base());

        assertEquals(2, entries.size());
        Map<String, Object> margherita = entries.get(0);
        assertEquals("Margherita", margherita.get("entityLabel"));
        assertEquals("mergeChangeStatements", margherita.get("operationType"));
        assertEquals(true, margherita.get("setOnly"));
        List<Map<String, Object>> subs = (List<Map<String, Object>>) margherita.get("subChanges");
        assertEquals(2, subs.size());
        assertEquals(false, subs.get(0).get("addition"));
        assertEquals("http://x#OldBase", subs.get(0).get("oldValue"));
        assertEquals(true, subs.get(1).get("addition"));
        Map<String, Object> hawaiian = entries.get(1);
        assertEquals("Hawaiian", hawaiian.get("entityLabel"));
        assertEquals("mergeAddStatements", hawaiian.get("operationType"));
        assertEquals("m1#1", hawaiian.get("id"));
    }

    @Test
    void anItemWithOnlyRemovalsIsShownAsRemoved() {
        List<Map<String, Object>> entries = MergeFeedExpander.expand(
                merge("<http://x#Old> <http://x#p> <http://x#o> .\n", null), base());
        assertEquals("mergeRemoveStatements", entries.get(0).get("operationType"));
    }

    @Test
    void aMergeWithoutRecordedStatementsStaysAsOneEntry() {
        Map<String, Object> base = base();
        assertEquals(List.of(base), MergeFeedExpander.expand(merge(null, null), base));
    }

    @Test
    void otherChangesAreLeftAlone() {
        HistoryChange other = new HistoryChange.Builder("p", "e2", "owner", "owner").operationType("createClass").build();
        other.setExactInserted("<http://x#A> <http://x#p> <http://x#o> .\n");
        Map<String, Object> base = base();
        assertEquals(List.of(base), MergeFeedExpander.expand(other, base));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHugeMergeIsCappedAndSaysHowManyAreHidden() {
        StringBuilder inserted = new StringBuilder();
        for (int i = 0; i < MergeFeedExpander.MAX_ENTITIES + 20; i++) {
            inserted.append("<http://x#S").append(i).append("> <http://x#p> <http://x#o> .\n");
        }

        List<Map<String, Object>> entries = MergeFeedExpander.expand(merge(null, inserted.toString()), base());

        assertEquals(MergeFeedExpander.MAX_ENTITIES + 1, entries.size());
        assertEquals("20 more statements not shown", entries.get(entries.size() - 1).get("entityLabel"));
    }

    @Test
    void theVirtualIdMapsBackToTheRealChange() {
        assertEquals("m1", MergeFeedExpander.baseId("m1#3"));
        assertEquals("m1", MergeFeedExpander.baseId("m1"));
        assertTrue(MergeFeedExpander.baseId(null) == null);
    }
}
