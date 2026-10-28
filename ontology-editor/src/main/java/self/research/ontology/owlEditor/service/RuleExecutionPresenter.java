package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleExecutionPresenter {

    public static final int MAX_INFERRED = 1000;

    public record Presented(List<OWLAxiom> ordered, Set<OWLAxiom> conclusions, int conclusionCount, int structureCount,
                            int omittedTrivial, boolean truncated) {
    }

    public record Full(List<OWLAxiom> conclusions, List<OWLAxiom> structure, int omittedTrivial) {
    }

    private RuleExecutionPresenter() {
    }

    public static Set<OWLAxiom> assertionsOf(OWLOntology ontology) {
        Set<OWLAxiom> out = new HashSet<>();
        for (OWLAxiom axiom : ontology.getABoxAxioms(Imports.INCLUDED)) {
            out.add(axiom.getAxiomWithoutAnnotations());
        }
        return out;
    }

    public static Full partition(Iterable<OWLAxiom> derived, Set<OWLAxiom> asserted) {
        List<OWLAxiom> conclusions = new ArrayList<>();
        List<OWLAxiom> structure = new ArrayList<>();
        int trivial = 0;
        for (OWLAxiom axiom : derived) {
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
        return new Full(conclusions, structure, trivial);
    }

    public static Presented present(Iterable<OWLAxiom> derived, Set<OWLAxiom> asserted, int limit) {
        return limited(partition(derived, asserted), limit);
    }

    public static Presented limited(Full full, int limit) {
        List<OWLAxiom> conclusions = full.conclusions();
        List<OWLAxiom> structure = full.structure();
        int trivial = full.omittedTrivial();
        int conclusionCap = Math.max(limit, 1) * 10;
        List<OWLAxiom> keptConclusions = conclusions.size() > conclusionCap ? new ArrayList<>(conclusions.subList(0, conclusionCap)) : conclusions;
        int structureRoom = Math.max(limit - keptConclusions.size(), 0);
        List<OWLAxiom> keptStructure = structure.size() > structureRoom ? new ArrayList<>(structure.subList(0, structureRoom)) : structure;
        boolean truncated = keptConclusions.size() < conclusions.size() || keptStructure.size() < structure.size();
        List<OWLAxiom> kept = new ArrayList<>(keptConclusions);
        kept.addAll(keptStructure);
        return new Presented(kept, new HashSet<>(conclusions), conclusions.size(), structure.size(), trivial, truncated);
    }

    public static Map<String, Object> toJson(OWLAxiom axiom, String category) {
        String subjectIri = null;
        String predicateIri = null;
        String objectIri = null;
        String objectLiteral = null;
        String literalDatatypeIri = null;
        String literalLangTag = null;
        if (axiom instanceof OWLClassAssertionAxiom a) {
            if (a.getIndividual().isNamed() && !a.getClassExpression().isAnonymous()) {
                subjectIri = a.getIndividual().asOWLNamedIndividual().getIRI().toString();
                predicateIri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
                objectIri = a.getClassExpression().asOWLClass().getIRI().toString();
            }
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
            if (a.getSubject().isNamed() && a.getObject().isNamed() && !a.getProperty().isAnonymous()) {
                subjectIri = a.getSubject().asOWLNamedIndividual().getIRI().toString();
                predicateIri = a.getProperty().asOWLObjectProperty().getIRI().toString();
                objectIri = a.getObject().asOWLNamedIndividual().getIRI().toString();
            }
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom a) {
            if (a.getSubject().isNamed() && !a.getProperty().isAnonymous()) {
                subjectIri = a.getSubject().asOWLNamedIndividual().getIRI().toString();
                predicateIri = a.getProperty().asOWLDataProperty().getIRI().toString();
                OWLLiteral literal = a.getObject();
                objectLiteral = literal.getLiteral();
                literalDatatypeIri = literal.getDatatype().getIRI().toString();
                literalLangTag = literal.hasLang() ? literal.getLang() : null;
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("axiomType", axiom.getAxiomType().getName());
        item.put("description", axiom.toString());
        item.put("readable", axiom.toString()
                .replace("http://www.w3.org/2002/07/owl#", "owl:")
                .replace("http://purl.obolibrary.org/obo/", ""));
        item.put("category", category);
        item.put("subjectIri", subjectIri);
        item.put("predicateIri", predicateIri);
        item.put("objectIri", objectIri);
        item.put("objectLiteral", objectLiteral);
        item.put("literalDatatypeIri", literalDatatypeIri);
        item.put("literalLangTag", literalLangTag);
        return item;
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
        return axiom instanceof OWLSameIndividualAxiom same && new HashSet<>(same.getIndividuals()).size() < 2;
    }
}
