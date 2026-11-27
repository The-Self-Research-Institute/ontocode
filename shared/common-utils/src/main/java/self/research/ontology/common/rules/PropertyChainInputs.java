package self.research.ontology.common.rules;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLObjectPropertyAtom;
import org.semanticweb.owlapi.model.SWRLRule;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class PropertyChainInputs {

    private PropertyChainInputs() {}

    static Set<OWLObjectProperty> forNativeRules(OWLOntology ontology) {
        Set<OWLObjectProperty> reachable = ontology.axioms(AxiomType.SUB_PROPERTY_CHAIN_OF)
                .map(chain -> chain.getSuperProperty().getNamedProperty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (reachable.isEmpty()) {
            return Set.of();
        }

        Map<OWLObjectProperty, Set<OWLObjectProperty>> links = new LinkedHashMap<>();
        ontology.axioms(AxiomType.SUB_OBJECT_PROPERTY)
                .forEach(axiom -> link(links, axiom.getSubProperty(), axiom.getSuperProperty()));
        ontology.axioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES).forEach(axiom -> {
            var properties = axiom.properties().toList();
            if (!properties.isEmpty()) {
                var first = properties.get(0);
                properties.forEach(property -> twoWayLink(links, first, property));
            }
        });
        ontology.axioms(AxiomType.INVERSE_OBJECT_PROPERTIES)
                .forEach(axiom -> twoWayLink(links, axiom.getFirstProperty(), axiom.getSecondProperty()));

        var pending = new ArrayDeque<>(reachable);
        while (!pending.isEmpty()) {
            for (OWLObjectProperty linked : links.getOrDefault(pending.removeFirst(), Set.of())) {
                if (reachable.add(linked)) {
                    pending.addLast(linked);
                }
            }
        }

        return ontology.axioms(AxiomType.SWRL_RULE).flatMap(SWRLRule::body)
                .filter(SWRLObjectPropertyAtom.class::isInstance).map(SWRLObjectPropertyAtom.class::cast)
                .map(atom -> atom.getPredicate().getNamedProperty()).filter(reachable::contains)
                .filter(property -> !property.isBuiltIn())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void twoWayLink(Map<OWLObjectProperty, Set<OWLObjectProperty>> links,
                                   OWLObjectPropertyExpression left, OWLObjectPropertyExpression right) {
        link(links, left, right);
        link(links, right, left);
    }

    private static void link(Map<OWLObjectProperty, Set<OWLObjectProperty>> links,
                             OWLObjectPropertyExpression from, OWLObjectPropertyExpression to) {
        links.computeIfAbsent(from.getNamedProperty(), ignored -> new LinkedHashSet<>()).add(to.getNamedProperty());
    }
}
