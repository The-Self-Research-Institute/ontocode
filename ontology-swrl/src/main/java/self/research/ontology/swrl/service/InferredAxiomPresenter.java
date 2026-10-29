package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

import self.research.ontology.swrl.model.InferredAxiom;

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
            if (isTrivial(axiom)) {
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

    static boolean isTrivial(OWLAxiom axiom) {
        return isTrivialSame(axiom) || isThingAssertion(axiom);
    }

    static String readable(OWLAxiom axiom) {
        return axiom.toString()
                .replace("http://www.w3.org/2002/07/owl#", "owl:")
                .replace("http://purl.obolibrary.org/obo/", "");
    }

    static InferredAxiom format(OWLAxiom axiom) {
        String subjectIri = null;
        String predicateIri = null;
        String objectIri = null;
        String objectLiteral = null;
        String literalDatatypeIri = null;
        String literalLangTag = null;

        if (axiom instanceof OWLClassAssertionAxiom classAssertion) {
            OWLIndividual individual = classAssertion.getIndividual();
            OWLClassExpression classExpr = classAssertion.getClassExpression();
            if (individual.isNamed() && !classExpr.isAnonymous()) {
                subjectIri = individual.asOWLNamedIndividual().getIRI().toString();
                predicateIri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
                objectIri = classExpr.asOWLClass().getIRI().toString();
            }
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom objectAssertion) {
            OWLIndividual subject = objectAssertion.getSubject();
            OWLIndividual object = objectAssertion.getObject();
            if (subject.isNamed() && object.isNamed() && !objectAssertion.getProperty().isAnonymous()) {
                subjectIri = subject.asOWLNamedIndividual().getIRI().toString();
                predicateIri = objectAssertion.getProperty().asOWLObjectProperty().getIRI().toString();
                objectIri = object.asOWLNamedIndividual().getIRI().toString();
            }
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom dataAssertion) {
            OWLIndividual subject = dataAssertion.getSubject();
            if (subject.isNamed() && !dataAssertion.getProperty().isAnonymous()) {
                subjectIri = subject.asOWLNamedIndividual().getIRI().toString();
                predicateIri = dataAssertion.getProperty().asOWLDataProperty().getIRI().toString();
                OWLLiteral literal = dataAssertion.getObject();
                objectLiteral = literal.getLiteral();
                literalDatatypeIri = literal.getDatatype().getIRI().toString();
                literalLangTag = literal.hasLang() ? literal.getLang() : null;
            }
        }

        return new InferredAxiom(axiom.getAxiomType().getName(), axiom.toString(), readable(axiom),
                subjectIri, predicateIri, objectIri, objectLiteral, literalDatatypeIri, literalLangTag);
    }

    private static boolean isThingAssertion(OWLAxiom axiom) {
        return axiom instanceof OWLClassAssertionAxiom a && !a.getClassExpression().isAnonymous() && a.getClassExpression().isOWLThing();
    }

    private static boolean isTrivialSame(OWLAxiom axiom) {
        return axiom instanceof OWLSameIndividualAxiom same && new java.util.HashSet<>(same.getIndividuals()).size() < 2;
    }
}
