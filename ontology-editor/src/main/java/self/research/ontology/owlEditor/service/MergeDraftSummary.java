package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MergeDraftSummary {

    static final int MAX_NAMES = 200;
    static final String COPY_SUFFIX = "_imported";
    static final List<String> KINDS = List.of("class", "objectProperty", "dataProperty", "annotationProperty",
            "individual", "datatype", "other");

    record Entity(String kind, String label) {}

    interface Lookup {
        Map<String, Entity> find(Collection<String> iris);
    }

    private MergeDraftSummary() {
    }

    private static final class Subject {
        String insertedKind;
        String deletedKind;
        String insertedLabel;
        String deletedLabel;
    }

    private static final class Group {
        final Map<String, List<String>> names = new LinkedHashMap<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();

        void add(String change, String name) {
            counts.merge(change, 1, Integer::sum);
            List<String> list = names.computeIfAbsent(change, key -> new ArrayList<>());
            if (list.size() < MAX_NAMES) {
                list.add(name);
            }
        }
    }

    static Map<String, Object> of(ExactDelta delta, String description, Lookup lookup) {
        Map<String, Subject> subjects = new LinkedHashMap<>();
        int unnamed = collect(delta.inserted(), subjects, true) + collect(delta.deleted(), subjects, false);
        Map<String, Entity> known = Map.of();
        if (lookup != null) {
            List<String> unresolved = subjects.entrySet().stream()
                    .filter(e -> e.getValue().insertedKind == null && e.getValue().deletedKind == null
                            || e.getValue().insertedLabel == null && e.getValue().deletedLabel == null)
                    .map(Map.Entry::getKey)
                    .limit(MAX_NAMES * 5L)
                    .toList();
            try {
                known = unresolved.isEmpty() ? Map.of() : lookup.find(unresolved);
            } catch (RuntimeException e) {
                known = Map.of();
            }
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String change : List.of("added", "changed", "removed", "copies")) {
            totals.put(change, 0);
        }
        for (Map.Entry<String, Subject> entry : subjects.entrySet()) {
            String iri = entry.getKey();
            Subject subject = entry.getValue();
            Entity found = known.get(iri);
            String change = subject.insertedKind != null && subject.deletedKind == null ? "added"
                    : subject.deletedKind != null && subject.insertedKind == null ? "removed" : "changed";
            if ("added".equals(change) && iri.endsWith(COPY_SUFFIX)) {
                change = "copies";
            }
            String kind = subject.insertedKind != null ? subject.insertedKind
                    : subject.deletedKind != null ? subject.deletedKind
                    : found != null && found.kind() != null ? found.kind() : "other";
            String label = "removed".equals(change)
                    ? first(subject.deletedLabel, subject.insertedLabel)
                    : first(subject.insertedLabel, subject.deletedLabel);
            if (label == null && found != null) {
                label = found.label();
            }
            groups.computeIfAbsent(kind, key -> new Group()).add(change, label != null ? label : shortName(iri));
            totals.merge(change, 1, Integer::sum);
        }

        List<Map<String, Object>> kinds = new ArrayList<>();
        for (String kind : KINDS) {
            Group group = groups.get(kind);
            if (group == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            for (String change : List.of("added", "changed", "removed", "copies")) {
                row.put(change, group.names.getOrDefault(change, List.of()));
                row.put(change + "Count", group.counts.getOrDefault(change, 0));
            }
            kinds.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        if (description != null && !description.isBlank()) {
            summary.put("description", description);
        }
        Map<String, Object> allTotals = new LinkedHashMap<>(totals);
        allTotals.put("statementsAdded", delta.inserted().size());
        allTotals.put("statementsRemoved", delta.deleted().size());
        allTotals.put("unnamedStatements", unnamed);
        summary.put("totals", allTotals);
        summary.put("kinds", kinds);
        return summary;
    }

    private static int collect(Model model, Map<String, Subject> subjects, boolean inserted) {
        int unnamed = 0;
        for (Statement st : model) {
            if (!(st.getSubject() instanceof IRI subjectIri)) {
                unnamed++;
                continue;
            }
            Subject subject = subjects.computeIfAbsent(subjectIri.stringValue(), key -> new Subject());
            if (RDF.TYPE.equals(st.getPredicate()) && st.getObject() instanceof IRI type) {
                String kind = kindOf(type);
                if (kind != null) {
                    if (inserted) {
                        subject.insertedKind = subject.insertedKind == null ? kind : subject.insertedKind;
                    } else {
                        subject.deletedKind = subject.deletedKind == null ? kind : subject.deletedKind;
                    }
                }
            } else if (RDFS.LABEL.equals(st.getPredicate()) && st.getObject() instanceof Literal literal) {
                if (inserted) {
                    subject.insertedLabel = subject.insertedLabel == null ? literal.getLabel() : subject.insertedLabel;
                } else {
                    subject.deletedLabel = subject.deletedLabel == null ? literal.getLabel() : subject.deletedLabel;
                }
            }
        }
        return unnamed;
    }

    static String kindOf(IRI type) {
        if (OWL.CLASS.equals(type)) {
            return "class";
        }
        if (OWL.OBJECTPROPERTY.equals(type)) {
            return "objectProperty";
        }
        if (OWL.DATATYPEPROPERTY.equals(type)) {
            return "dataProperty";
        }
        if (OWL.ANNOTATIONPROPERTY.equals(type)) {
            return "annotationProperty";
        }
        if (OWL.NAMEDINDIVIDUAL.equals(type)) {
            return "individual";
        }
        if (RDFS.DATATYPE.equals(type)) {
            return "datatype";
        }
        return null;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    static String shortName(String iri) {
        int idx = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return idx >= 0 && idx < iri.length() - 1 ? iri.substring(idx + 1) : iri;
    }
}
