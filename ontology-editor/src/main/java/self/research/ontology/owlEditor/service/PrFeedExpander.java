package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class PrFeedExpander {

    static final int MAX_ITEMS = 200;

    private PrFeedExpander() {
    }

    public static List<Map<String, Object>> expand(HistoryChange entry, Map<String, Object> base,
                                                   Function<String, List<HistoryChange>> rowsOfPr,
                                                   Function<HistoryChange, Map<String, Object>> toMap) {
        if (!PrEntries.isMergedPrEntry(entry)) {
            return List.of(base);
        }
        String prId = PrEntries.prIdOf(entry.getEntityIRI());
        List<HistoryChange> rows = new ArrayList<>(rowsOfPr.apply(prId));
        if (rows.isEmpty()) {
            return List.of(base);
        }
        rows.sort(Comparator.comparing(HistoryChange::getTimestampIso, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 0;
        for (HistoryChange row : rows) {
            if (index >= MAX_ITEMS) {
                out.add(moreMarker(entry, base, rows.size() - MAX_ITEMS, prId));
                break;
            }
            Map<String, Object> map = new HashMap<>(toMap.apply(row));
            map.put("id", entry.getId() + "#" + index++);
            map.put("timestamp", entry.getTimestampIso());
            map.put("changeSetId", "pr:" + prId);
            map.put("description", entry.getEntityLabel());
            map.put("setOnly", true);
            map.put("undoable", false);
            map.put("undoReason", PrEntries.MERGED_PR_PART);
            map.put("mergedPrId", prId);
            map.put("mergedPrReverted", entry.isReverted());
            map.put("mergedBy", entry.getUsername());
            out.add(map);
        }
        return out;
    }

    private static Map<String, Object> moreMarker(HistoryChange entry, Map<String, Object> base, int hidden, String prId) {
        Map<String, Object> more = new HashMap<>(base);
        more.put("id", entry.getId() + "#more");
        more.put("entityIRI", null);
        more.put("entityLabel", hidden + " more items not shown");
        more.put("operationType", "mergeChangeStatements");
        more.put("subChanges", List.of());
        more.put("changeSetId", "pr:" + prId);
        more.put("description", entry.getEntityLabel());
        more.put("setOnly", true);
        more.put("undoable", false);
        more.put("undoReason", PrEntries.MERGED_PR_PART);
        return more;
    }
}
