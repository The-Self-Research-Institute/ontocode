package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import self.research.ontology.owlEditor.service.OntologyHeaderMerge.Delta;
import self.research.ontology.owlEditor.service.OntologyHeaderMerge.Field;
import self.research.ontology.owlEditor.service.OntologyHeaderMerge.Header;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class HeaderChangeRows {

    private static final int MAX_SUMMARY_LINES = 10;

    private HeaderChangeRows() {
    }

    static List<Map<String, Object>> pull(Header baseline, Header pub, Header mine) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Field field : OntologyHeaderMerge.fields(baseline, pub, mine)) {
            if (!field.incoming()) {
                continue;
            }
            String label = label(field.key());
            Map<String, Object> row = sectionRow(field.key(), "header");
            row.put("field", field.field());
            row.put("baselineValue", text(field.baseline()));
            row.put("publicValue", text(field.source()));
            row.put("yourValue", text(field.target()));
            row.put("conflict", field.conflict());
            row.put("publicAxioms", label + ": " + shown(field.baseline()) + " → " + shown(field.source()));
            if (field.conflict()) {
                row.put("yourAxioms", label + ": " + shown(field.baseline()) + " → " + shown(field.target()));
            }
            rows.add(row);
        }
        addSetRow(rows, OntologyHeaderMerge.ANNOTATIONS,
                OntologyHeaderMerge.delta(baseline.annotations(), pub.annotations(), mine.annotations()),
                AxiomPlainText::describe);
        addSetRow(rows, OntologyHeaderMerge.IMPORTS,
                OntologyHeaderMerge.delta(baseline.imports(), pub.imports(), mine.imports()),
                HeaderChangeRows::importText);
        addSetRow(rows, OntologyHeaderMerge.OTHER_AXIOMS,
                OntologyHeaderMerge.otherAxiomDelta(baseline, pub, mine), AxiomPlainText::describeOther);
        return rows;
    }

    static List<Map<String, Object>> direct(Header draft, Header pub) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Field field : OntologyHeaderMerge.fields(draft, pub, draft)) {
            if (!field.incoming()) {
                continue;
            }
            String label = label(field.key());
            Map<String, Object> row = sectionRow(field.key(), "header");
            row.put("field", field.field());
            row.put("publicValue", text(field.source()));
            row.put("yourValue", text(field.target()));
            row.put("conflict", true);
            row.put("publicAxioms", label + ": " + shown(field.source()));
            row.put("yourAxioms", label + ": " + shown(field.target()));
            rows.add(row);
        }
        addDirectSetRow(rows, OntologyHeaderMerge.ANNOTATIONS,
                OntologyHeaderMerge.changes(draft.annotations(), pub.annotations()), AxiomPlainText::describe);
        addDirectSetRow(rows, OntologyHeaderMerge.IMPORTS,
                OntologyHeaderMerge.changes(draft.imports(), pub.imports()), HeaderChangeRows::importText);
        addDirectSetRow(rows, OntologyHeaderMerge.OTHER_AXIOMS,
                OntologyHeaderMerge.otherAxiomChanges(draft, pub), AxiomPlainText::describeOther);
        return rows;
    }

    static String label(String key) {
        return switch (key) {
            case OntologyHeaderMerge.ONTOLOGY_IRI -> "Ontology IRI";
            case OntologyHeaderMerge.VERSION_IRI -> "Version IRI";
            case OntologyHeaderMerge.ANNOTATIONS -> "Ontology annotations";
            case OntologyHeaderMerge.IMPORTS -> "Imports";
            default -> "Other axioms";
        };
    }

    static String text(IRI iri) {
        return iri != null ? iri.toString() : null;
    }

    static String shown(IRI iri) {
        return iri != null ? iri.toString() : "(none)";
    }

    static String importText(OWLImportsDeclaration declaration) {
        return declaration.getIRI().toString();
    }

    static <T> String joined(Set<T> items, Function<T, String> describe) {
        return items.stream().map(describe).sorted().collect(Collectors.joining("\n"));
    }

    private static <T> void addSetRow(List<Map<String, Object>> rows, String key, Delta<T> delta,
                                      Function<T, String> describe) {
        if (delta.isEmpty()) {
            return;
        }
        Map<String, Object> row = sectionRow(key, kindOf(key));
        row.put("field", fieldName(key));
        row.put("added", delta.added().size());
        row.put("removed", delta.removed().size());
        row.put("conflict", false);
        List<String> lines = new ArrayList<>();
        lines.add(delta.added().size() + " added, " + delta.removed().size() + " removed");
        lines.addAll(capped(describedWith("Added: ", delta.added(), describe, "Removed: ", delta.removed())));
        row.put("publicAxioms", String.join("\n", lines));
        rows.add(row);
    }

    private static <T> void addDirectSetRow(List<Map<String, Object>> rows, String key, Delta<T> publicOnly,
                                            Function<T, String> describe) {
        if (publicOnly.added().isEmpty()) {
            return;
        }
        Map<String, Object> row = sectionRow(key, kindOf(key));
        row.put("field", fieldName(key));
        row.put("added", publicOnly.added().size());
        row.put("removed", publicOnly.removed().size());
        row.put("conflict", true);
        row.put("publicAxioms", String.join("\n", capped(describedWith("", publicOnly.added(), describe, "", Set.of()))));
        row.put("yourAxioms", String.join("\n", capped(describedWith("", publicOnly.removed(), describe, "", Set.of()))));
        rows.add(row);
    }

    private static <T> List<String> describedWith(String addedPrefix, Set<T> added, Function<T, String> describe,
                                                  String removedPrefix, Set<T> removed) {
        List<String> lines = new ArrayList<>();
        added.stream().map(describe).sorted().forEach(line -> lines.add(addedPrefix + line));
        removed.stream().map(describe).sorted().forEach(line -> lines.add(removedPrefix + line));
        return lines;
    }

    private static List<String> capped(List<String> lines) {
        if (lines.size() <= MAX_SUMMARY_LINES) {
            return lines;
        }
        List<String> shown = new ArrayList<>(lines.subList(0, MAX_SUMMARY_LINES));
        shown.add("+ " + (lines.size() - MAX_SUMMARY_LINES) + " more");
        return shown;
    }

    private static Map<String, Object> sectionRow(String key, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityIri", key);
        row.put("entityLabel", label(key));
        row.put("kind", kind);
        return row;
    }

    private static String kindOf(String key) {
        return OntologyHeaderMerge.OTHER_AXIOMS.equals(key) ? "other_axioms" : "header";
    }

    private static String fieldName(String key) {
        return OntologyHeaderMerge.OTHER_AXIOMS.equals(key) ? "otherAxioms" : key.substring(key.indexOf(':') + 1);
    }
}
