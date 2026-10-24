package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class PrFeedExpander {

    static final String IRI_PREFIX = "urn:ontocode:pr:";
    static final int MAX_ITEMS = 200;

    private PrFeedExpander() {
    }

    public static boolean isMergedPrEntry(HistoryChange change) {
        return RollbackWorkSupport.PR_PUBLISHED.equals(change.getOperationType())
                && change.getEntityIRI() != null && change.getEntityIRI().startsWith(IRI_PREFIX);
    }

    public static String prIdOf(HistoryChange change) {
        return change.getEntityIRI().substring(IRI_PREFIX.length());
    }

    public static List<Map<String, Object>> expand(HistoryChange entry, Map<String, Object> base,
                                                   Function<String, List<HistoryChange>> rowsOfPr,
                                                   Function<HistoryChange, Map<String, Object>> toMap) {
        if (!isMergedPrEntry(entry)) {
            return List.of(base);
        }
        String prId = prIdOf(entry);
        List<HistoryChange> rows = new ArrayList<>(rowsOfPr.apply(prId));
        if (rows.isEmpty()) {
            return List.of(base);
        }
        rows.sort(Comparator.comparing(HistoryChange::getTimestampIso, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 0;
        for (HistoryChange row : rows) {
            if (index >= MAX_ITEMS) {
                break;
            }
            Map<String, Object> map = new HashMap<>(toMap.apply(row));
            map.put("id", entry.getId() + "#" + index++);
            map.put("timestamp", entry.getTimestampIso());
            map.put("changeSetId", "pr:" + prId);
            map.put("description", entry.getEntityLabel());
            map.put("setOnly", true);
            map.put("undoable", false);
            map.put("undoReason", "Part of a merged pull request. Revert the pull request instead");
            map.put("mergedPrId", prId);
            map.put("mergedBy", entry.getUsername());
            out.add(map);
        }
        return out;
    }
}
