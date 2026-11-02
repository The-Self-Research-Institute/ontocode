package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class InferredAxiomPresenter {

    record Presented(List<OWLAxiom> ordered, Set<OWLAxiom> conclusions, int conclusionCount, int structureCount,
                     int omittedTrivial, boolean truncated) {
    }

    private InferredAxiomPresenter() {
    }

    static Set<OWLAxiom> assertionsOf(OWLOntology ontology) {
        Set<OWLAxiom> out = new HashSet<>();
        for (OWLAxiom axiom : ontology.getABoxAxioms(Imports.INCLUDED)) {
            out.add(axiom.getAxiomWithoutAnnotations());
        }
        return out;
    }

    static Presented present(Set<OWLAxiom> inferred, Set<OWLAxiom> asserted, int limit) {
        List<OWLAxiom> conclusions = new ArrayList<>();
        List<OWLAxiom> structure = new ArrayList<>();
        int trivial = 0;
        for (OWLAxiom axiom : inferred) {
            if (isTrivialSame(axiom) || isThingAssertion(axiom)) {
                trivial++;
            } else if (isAssertion(axiom)) {
                if (!asserted.contains(axiom.getAxiomWithoutAnnotations())) {
                    conclusions.add(axiom);
                }
            } else {
                structure.add(axiom);
            }
        }
        Comparator<OWLAxiom> order = Comparator.comparing((OWLAxiom a) -> a.getAxiomType().getName()).thenComparing(Object::toString);
        conclusions.sort(order);
        structure.sort(order);
        int conclusionCap = Math.max(limit, 1) * 10;
        List<OWLAxiom> keptConclusions = conclusions.size() > conclusionCap ? new ArrayList<>(conclusions.subList(0, conclusionCap)) : conclusions;
        int structureRoom = Math.max(limit - keptConclusions.size(), 0);
        List<OWLAxiom> keptStructure = structure.size() > structureRoom ? new ArrayList<>(structure.subList(0, structureRoom)) : structure;
        boolean truncated = keptConclusions.size() < conclusions.size() || keptStructure.size() < structure.size();
        List<OWLAxiom> kept = new ArrayList<>(keptConclusions);
        kept.addAll(keptStructure);
        return new Presented(kept, new HashSet<>(conclusions), conclusions.size(), structure.size(), trivial, truncated);
    }

    private static boolean isAssertion(OWLAxiom axiom) {
        if (axiom instanceof OWLClassAssertionAxiom a) {
            return a.getIndividual().isNamed() && !a.getClassExpression().isAnonymous() && !placeholder(a.getIndividual().toString());
        }
        if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
            return !placeholder(a.getSubject().toString()) && !placeholder(a.getObject().toString());
        }
        return axiom instanceof OWLDataPropertyAssertionAxiom d && !placeholder(d.getSubject().toString());
    }

    private static boolean placeholder(String text) {
        return text.contains("urn:ontocode:blank:");
    }

    private static boolean isThingAssertion(OWLAxiom axiom) {
        return axiom instanceof OWLClassAssertionAxiom a && !a.getClassExpression().isAnonymous() && a.getClassExpression().isOWLThing();
    }

    private static boolean isTrivialSame(OWLAxiom axiom) {
        return axiom instanceof OWLSameIndividualAxiom same && new java.util.HashSet<>(same.getIndividuals()).size() < 2;
    }
}
