package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RollbackDescriptions {

    private static final int MAX_NAMED = 2;

    private static final Map<String, String> PREDICATE_NAMES = Map.ofEntries(
            Map.entry("label", "label"),
            Map.entry("comment", "comment"),
            Map.entry("subClassOf", "parent class"),
            Map.entry("subPropertyOf", "parent property"),
            Map.entry("type", "type"),
            Map.entry("domain", "domain"),
            Map.entry("range", "range"),
            Map.entry("disjointWith", "disjoint with"),
            Map.entry("equivalentClass", "equivalent class"),
            Map.entry("prefLabel", "preferred label"),
            Map.entry("definition", "definition"));

    private RollbackDescriptions() {
    }

    static String describe(String direction, String level, List<ChangeRollbackService.Item> applied,
                           List<HistoryChange> entries) {
        String verb = ChangeRollbackService.UNDO.equals(direction) ? "Undid" : "Redid";
        int count = applied.size();
        if (ChangeRollbackService.CHANGESET.equals(level)) {
            return verb + " change set (" + count + " change" + (count == 1 ? "" : "s") + ")";
        }
        HistoryChange single = entries.size() == 1 ? entries.get(0) : null;
        String label = single != null && single.getEntityLabel() != null && !single.getEntityLabel().isBlank()
                ? single.getEntityLabel() : null;
        Set<String> names = new LinkedHashSet<>();
        applied.stream().filter(i -> i.predicate() != null).forEach(i -> names.add(predicateName(i.predicate())));
        if (!names.isEmpty()) {
            return verb + " " + joinNames(names) + (label != null ? " on " + label : "");
        }
        if (single != null && label != null) {
            RollbackMutationPlanner.Kind kind = RollbackMutationPlanner.kindOf(single);
            if (kind == RollbackMutationPlanner.Kind.CREATE) {
                return verb + " creating " + label;
            }
            if (kind == RollbackMutationPlanner.Kind.DELETE) {
                return verb + " deleting " + label;
            }
            return verb + " change to " + label;
        }
        return verb + " " + (count > 1 ? count + " changes" : "change");
    }

    private static String joinNames(Set<String> names) {
        List<String> list = List.copyOf(names);
        if (list.size() == 1) {
            return list.get(0);
        }
        if (list.size() == 2) {
            return list.get(0) + " and " + list.get(1);
        }
        return String.join(", ", list.subList(0, MAX_NAMED)) + " and " + (list.size() - MAX_NAMED) + " more";
    }

    static String predicateName(String predicate) {
        String local = predicate.replaceAll("^.*[#/:]", "");
        String known = PREDICATE_NAMES.get(local);
        if (known != null) {
            return known;
        }
        return local.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }
}
