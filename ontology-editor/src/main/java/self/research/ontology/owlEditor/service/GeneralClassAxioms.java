package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class GeneralClassAxioms {

    static final String SUB_CLASS_OF = "SubClassOf";
    static final String EQUIVALENT_TO = "EquivalentTo";
    static final String DISJOINT_WITH = "DisjointWith";

    record Entry(OWLAxiom axiom, String type, String subClass, String superClass, String value) {
    }

    private GeneralClassAxioms() {
    }

    static List<Entry> list(OWLOntology ontology) {
        List<Entry> entries = new ArrayList<>();
        for (OWLSubClassOfAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)) {
            if (!ax.getSubClass().isAnonymous()) {
                continue;
            }
            String sub = OwlApiQuerySupport.classExpressionToManchester(ontology, ax.getSubClass());
            String sup = OwlApiQuerySupport.classExpressionToManchester(ontology, ax.getSuperClass());
            entries.add(new Entry(ax, SUB_CLASS_OF, sub, sup, sub + " " + SUB_CLASS_OF + " " + sup));
        }
        for (OWLNaryClassAxiom ax : ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES, Imports.INCLUDED)) {
            nary(ontology, ax, EQUIVALENT_TO).ifPresent(entries::add);
        }
        for (OWLNaryClassAxiom ax : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)) {
            nary(ontology, ax, DISJOINT_WITH).ifPresent(entries::add);
        }
        return entries;
    }

    static Optional<Entry> find(OWLOntology ontology, String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.trim();
        return list(ontology).stream().filter(entry -> entry.value().equals(wanted)).findFirst();
    }

    static List<Map<String, Object>> describe(OWLOntology ontology) {
        List<Map<String, Object>> described = new ArrayList<>();
        for (Entry entry : list(ontology)) {
            Map<String, Object> gci = new LinkedHashMap<>();
            gci.put("type", entry.type());
            gci.put("subClass", entry.subClass());
            gci.put("superClass", entry.superClass());
            gci.put("value", entry.value());
            described.add(gci);
        }
        return described;
    }

    private static Optional<Entry> nary(OWLOntology ontology, OWLNaryClassAxiom ax, String type) {
        List<OWLClassExpression> operands = ax.getClassExpressionsAsList();
        if (operands.size() < 2 || operands.stream().anyMatch(ce -> !ce.isAnonymous())) {
            return Optional.empty();
        }
        List<String> rendered = operands.stream()
                .map(ce -> OwlApiQuerySupport.classExpressionToManchester(ontology, ce))
                .sorted()
                .collect(Collectors.toList());
        String value = String.join(" " + type + " ", rendered);
        return Optional.of(new Entry(ax, type, rendered.get(0),
                String.join(", ", rendered.subList(1, rendered.size())), value));
    }
}
