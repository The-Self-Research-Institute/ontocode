package self.research.ontology.owlEditor.service.infer;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class InconsistencyExplainer {

    private final Map<OWLIndividual, OWLIndividual> parent = new HashMap<>();

    static String explain(OWLOntology ontology) {
        return new InconsistencyExplainer().find(ontology);
    }

    private String find(OWLOntology ontology) {
        ontology.axioms(AxiomType.SAME_INDIVIDUAL).forEach(this::unite);
        for (OWLDifferentIndividualsAxiom axiom : (Iterable<OWLDifferentIndividualsAxiom>) ontology.axioms(AxiomType.DIFFERENT_INDIVIDUALS)::iterator) {
            List<OWLIndividual> items = axiom.getIndividualsAsList();
            for (int i = 0; i < items.size(); i++) {
                for (int j = i + 1; j < items.size(); j++) {
                    OWLIndividual a = items.get(i);
                    OWLIndividual b = items.get(j);
                    if (root(a).equals(root(b))) {
                        return label(a) + " and " + label(b) + " are stated to be the same individual (owl:sameAs, directly or through a chain)"
                                + " and also different (owl:differentFrom).";
                    }
                }
            }
        }
        return "The reasoner found a contradiction in the captured ontology, but it could not be narrowed to a single pair of individuals."
                + " Check disjoint classes, functional properties and cardinality restrictions on the individuals you recently changed.";
    }

    private void unite(OWLSameIndividualAxiom axiom) {
        List<OWLIndividual> items = axiom.getIndividualsAsList();
        for (int i = 1; i < items.size(); i++) {
            OWLIndividual a = root(items.get(0));
            OWLIndividual b = root(items.get(i));
            if (!a.equals(b)) {
                parent.put(b, a);
            }
        }
    }

    private OWLIndividual root(OWLIndividual x) {
        OWLIndividual current = x;
        while (parent.containsKey(current)) {
            current = parent.get(current);
        }
        return current;
    }

    private static String label(OWLIndividual individual) {
        return individual.isNamed() ? individual.asOWLNamedIndividual().getIRI().toString() : "an unnamed individual";
    }
}
