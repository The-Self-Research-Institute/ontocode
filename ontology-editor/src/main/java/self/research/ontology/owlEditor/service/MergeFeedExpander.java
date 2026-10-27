package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MergeFeedExpander {

    static final int MAX_ENTITIES = 150;
    static final int MAX_STATEMENTS = 600;
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    private MergeFeedExpander() {
    }

    public static String baseId(String changeId) {
        if (changeId == null) {
            return null;
        }
        int hash = changeId.indexOf('#');
        return hash > 0 ? changeId.substring(0, hash) : changeId;
    }

    public static List<Map<String, Object>> expand(HistoryChange change, Map<String, Object> base) {
        if (!OntologyMergeService.MERGE_OPERATION.equals(change.getOperationType())) {
            return List.of(base);
        }
        Optional<ExactDelta> delta = ExactReversal.deltaOf(change);
        if (delta.isEmpty() || delta.get().isEmpty()) {
            return List.of(base);
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        int[] taken = {0};
        int[] hidden = {0};
        collect(groups, delta.get().deleted(), false, taken, hidden);
        collect(groups, delta.get().inserted(), true, taken, hidden);
        List<Map<String, Object>> entries = new ArrayList<>();
        int index = 0;
        for (Group group : groups.values()) {
            entries.add(entry(change, base, group, index++));
        }
        if (hidden[0] > 0) {
            Map<String, Object> more = new HashMap<>(base);
            more.put("id", change.getId() + "#more");
            more.put("entityIRI", null);
            more.put("entityLabel", hidden[0] + " more statements not shown");
            more.put("operationType", "mergeChangeStatements");
            more.put("subChanges", List.of());
            more.put("setOnly", true);
            entries.add(more);
        }
        return entries;
    }

    private static void collect(Map<String, Group> groups, Model model, boolean addition, int[] taken, int[] hidden) {
        for (Statement st : model) {
            String subject = st.getSubject().stringValue();
            Group group = groups.get(subject);
            if (group == null && groups.size() >= MAX_ENTITIES || taken[0] >= MAX_STATEMENTS) {
                hidden[0]++;
                continue;
            }
            if (group == null) {
                group = new Group(subject);
                groups.put(subject, group);
            }
            taken[0]++;
            group.parts.add(new Part(st.getPredicate().stringValue(), st.getObject().stringValue(), addition));
        }
    }

    private static Map<String, Object> entry(HistoryChange change, Map<String, Object> base, Group group, int index) {
        boolean added = group.parts.stream().anyMatch(part -> part.addition);
        boolean removed = group.parts.stream().anyMatch(part -> !part.addition);
        Map<String, Object> map = new HashMap<>(base);
        map.put("id", change.getId() + "#" + index);
        map.put("entityIRI", group.subject);
        map.put("entityLabel", group.label());
        map.put("operationType", added && removed ? "mergeChangeStatements" : added ? "mergeAddStatements" : "mergeRemoveStatements");
        map.put("undoable", true);
        map.put("undoReason", null);
        map.put("setOnly", true);
        List<Map<String, Object>> subs = new ArrayList<>();
        int sub = 0;
        for (Part part : group.parts) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", String.valueOf(sub++));
            row.put("predicate", part.predicate);
            row.put(part.addition ? "newValue" : "oldValue", part.value);
            row.put("addition", part.addition);
            row.put("reverted", change.isReverted());
            subs.add(row);
        }
        map.put("subChanges", subs);
        return map;
    }

    static String localName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }

    private record Part(String predicate, String value, boolean addition) {}

    private static final class Group {
        final String subject;
        final List<Part> parts = new ArrayList<>();

        Group(String subject) {
            this.subject = subject;
        }

        String label() {
            for (Part part : parts) {
                if (RDFS_LABEL.equals(part.predicate) || SKOS_PREF_LABEL.equals(part.predicate)) {
                    return part.value;
                }
            }
            return localName(subject);
        }
    }
}
