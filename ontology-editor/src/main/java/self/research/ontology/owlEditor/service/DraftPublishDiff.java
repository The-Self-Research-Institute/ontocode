package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.owlEditor.model.merge.ConflictResolution;
import self.research.ontology.owlEditor.service.OntologyHeaderMerge.Delta;
import self.research.ontology.owlEditor.service.OntologyHeaderMerge.Field;
import self.research.ontology.owlEditor.service.OntologyHeaderMerge.Header;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record DraftPublishDiff(boolean available,
                               boolean baselineLost,
                               boolean publicChanged,
                               Set<String> conflictKeys,
                               List<Map<String, Object>> conflictRows,
                               Map<String, Object> payload) {

    private static final int MAX_LISTED_CHANGES = 500;
    private static final int MAX_LISTED_OTHER_AXIOMS = 200;
    private static final int MAX_AXIOM_LINES = 50;

    static DraftPublishDiff unavailable() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", false);
        payload.put("baselineLost", false);
        payload.put("publicChangedSinceDraft", false);
        payload.put("conflictCount", 0);
        payload.put("changes", List.of());
        payload.put("totalChanges", 0);
        payload.put("header", List.of());
        payload.put("otherAxioms", otherAxiomsPayload(new Delta<>(Set.of(), Set.of())));
        return new DraftPublishDiff(false, false, false, Set.of(), List.of(), payload);
    }

    public Set<String> unresolved(Map<String, ConflictResolution> resolutions) {
        return conflictKeys.stream()
                .filter(key -> {
                    ConflictResolution chosen = resolutions != null ? resolutions.get(key) : null;
                    return chosen == null || chosen.getAction() == null;
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static String labelOf(IRI iri, OWLOntology... sides) {
        for (OWLOntology side : sides) {
            for (OWLAnnotationAssertionAxiom axiom : side.getAnnotationAssertionAxioms(iri)) {
                if (axiom.getProperty().isLabel() && axiom.getValue().asLiteral().isPresent()) {
                    String label = axiom.getValue().asLiteral().get().getLiteral();
                    if (!label.isBlank()) {
                        return label;
                    }
                }
            }
        }
        return AxiomPlainText.shortName(iri.toString());
    }

    static String kindOf(IRI iri, OWLOntology... sides) {
        for (OWLOntology side : sides) {
            if (side.containsClassInSignature(iri)) {
                return "class";
            }
            if (side.containsObjectPropertyInSignature(iri)) {
                return "objectProperty";
            }
            if (side.containsDataPropertyInSignature(iri)) {
                return "dataProperty";
            }
            if (side.containsAnnotationPropertyInSignature(iri)) {
                return "annotationProperty";
            }
            if (side.containsIndividualInSignature(iri)) {
                return "individual";
            }
            if (side.containsDatatypeInSignature(iri)) {
                return "datatype";
            }
        }
        return "other";
    }

    private static Map<String, Object> otherAxiomsPayload(Delta<OWLAxiom> delta) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (OWLAxiom axiom : sorted(delta.added())) {
            items.add(Map.of("axiom", AxiomPlainText.describeOther(axiom), "change", "added"));
        }
        for (OWLAxiom axiom : sorted(delta.removed())) {
            items.add(Map.of("axiom", AxiomPlainText.describeOther(axiom), "change", "removed"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("added", delta.added().size());
        payload.put("removed", delta.removed().size());
        payload.put("items", new ArrayList<>(items.subList(0, Math.min(items.size(), MAX_LISTED_OTHER_AXIOMS))));
        return payload;
    }

    private static List<OWLAxiom> sorted(Collection<OWLAxiom> axioms) {
        return axioms.stream().sorted(Comparator.comparing(OWLAxiom::toString)).collect(Collectors.toList());
    }

    private static List<String> axiomLines(Set<OWLAxiom> axioms, Set<OWLAxiom> other, IRI entity) {
        List<String> lines = sorted(axioms.stream().filter(axiom -> !other.contains(axiom)).toList()).stream()
                .map(axiom -> AxiomPlainText.describe(axiom, entity))
                .distinct()
                .collect(Collectors.toList());
        if (lines.size() <= MAX_AXIOM_LINES) {
            return lines;
        }
        List<String> shown = new ArrayList<>(lines.subList(0, MAX_AXIOM_LINES));
        shown.add("+ " + (lines.size() - MAX_AXIOM_LINES) + " more");
        return shown;
    }

    static final class Builder {
        private final List<Map<String, Object>> changes = new ArrayList<>();
        private final List<Map<String, Object>> header = new ArrayList<>();
        private final Set<String> conflictKeys = new LinkedHashSet<>();
        private final List<Map<String, Object>> conflictRows = new ArrayList<>();

        void entity(IRI iri, String label, String kind, String change,
                    Set<OWLAxiom> draftDefs, Set<OWLAxiom> mainDefs, boolean conflict) {
            List<String> yours = axiomLines(draftDefs, mainDefs, iri);
            List<String> theirs = axiomLines(mainDefs, draftDefs, iri);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityIri", iri.toString());
            row.put("entityLabel", label);
            row.put("kind", kind);
            row.put("change", change);
            row.put("yourAxioms", yours);
            row.put("publicAxioms", theirs);
            row.put("conflict", conflict);
            changes.add(row);
            if (conflict) {
                conflictKeys.add(iri.toString());
                conflictRows.add(conflictRow(iri.toString(), label, String.join("\n", yours), String.join("\n", theirs)));
            }
        }

        void field(Field field, IRI baselineValue, boolean conflict) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", field.field());
            row.put("baselineValue", HeaderChangeRows.text(baselineValue));
            row.put("yourValue", HeaderChangeRows.text(field.source()));
            row.put("publicValue", HeaderChangeRows.text(field.target()));
            row.put("conflict", conflict);
            header.add(row);
            if (conflict) {
                String label = HeaderChangeRows.label(field.key());
                conflictKeys.add(field.key());
                conflictRows.add(conflictRow(field.key(), label, label + ": " + HeaderChangeRows.shown(field.source()),
                        label + ": " + HeaderChangeRows.shown(field.target())));
            }
        }

        void sets(Header baseline, Header draft, Header main, boolean baselineKnown) {
            if (!OntologyHeaderMerge.changes(baseline.annotations(), draft.annotations()).isEmpty()) {
                header.add(setRow("ontologyAnnotations",
                        baselineKnown ? HeaderChangeRows.joined(baseline.annotations(), AxiomPlainText::describe) : null,
                        HeaderChangeRows.joined(draft.annotations(), AxiomPlainText::describe),
                        HeaderChangeRows.joined(main.annotations(), AxiomPlainText::describe)));
            }
            if (!OntologyHeaderMerge.changes(baseline.imports(), draft.imports()).isEmpty()) {
                header.add(setRow("imports",
                        baselineKnown ? HeaderChangeRows.joined(baseline.imports(), HeaderChangeRows::importText) : null,
                        HeaderChangeRows.joined(draft.imports(), HeaderChangeRows::importText),
                        HeaderChangeRows.joined(main.imports(), HeaderChangeRows::importText)));
            }
        }

        DraftPublishDiff build(boolean baselineLost, boolean publicChanged, Delta<OWLAxiom> otherAxioms) {
            changes.sort(Comparator.comparing((Map<String, Object> row) -> !Boolean.TRUE.equals(row.get("conflict")))
                    .thenComparing(row -> String.valueOf(row.get("entityLabel")).toLowerCase()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("available", true);
            payload.put("baselineLost", baselineLost);
            payload.put("publicChangedSinceDraft", publicChanged);
            payload.put("conflictCount", conflictKeys.size());
            payload.put("changes", new ArrayList<>(changes.subList(0, Math.min(changes.size(), MAX_LISTED_CHANGES))));
            payload.put("totalChanges", changes.size());
            payload.put("header", header);
            payload.put("otherAxioms", otherAxiomsPayload(otherAxioms));
            return new DraftPublishDiff(true, baselineLost, publicChanged, conflictKeys, conflictRows, payload);
        }

        private static Map<String, Object> setRow(String field, String baselineValue, String yourValue,
                                                  String publicValue) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", field);
            row.put("baselineValue", baselineValue);
            row.put("yourValue", yourValue);
            row.put("publicValue", publicValue);
            row.put("conflict", false);
            return row;
        }

        private static Map<String, Object> conflictRow(String key, String label, String yours, String theirs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityIRI", key);
            row.put("entityLabel", label);
            row.put("yourAxioms", yours);
            row.put("mainAxioms", theirs);
            return row;
        }
    }
}
