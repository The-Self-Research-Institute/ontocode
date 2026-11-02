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

    static Presented presentNew(Set<OWLAxiom> inferred, Set<OWLAxiom> existing, int limit) {
        List<OWLAxiom> conclusions = new ArrayList<>();
        List<OWLAxiom> structure = new ArrayList<>();
        int trivial = 0;
        for (OWLAxiom axiom : inferred) {
            if (isTrivialSame(axiom) || isThingAssertion(axiom) || isTopOrBottomSubclass(axiom) || isSchemaNoise(axiom)) {
                trivial++;
            } else if (!existing.contains(axiom.getAxiomWithoutAnnotations())) {
                (isAssertion(axiom) ? conclusions : structure).add(axiom);
            }
        }
        Comparator<OWLAxiom> order = Comparator.comparing((OWLAxiom a) -> a.getAxiomType().getName()).thenComparing(Object::toString);
        conclusions.sort(order);
        structure.sort(order);
        List<OWLAxiom> kept = new ArrayList<>(conclusions);
        kept.addAll(structure);
        boolean truncated = kept.size() > limit;
        if (truncated) {
            kept = new ArrayList<>(kept.subList(0, Math.max(limit, 0)));
        }
        return new Presented(kept, new HashSet<>(conclusions), conclusions.size(), structure.size(), trivial, truncated);
    }

    private static boolean isSchemaNoise(OWLAxiom axiom) {
        if (axiom instanceof org.semanticweb.owlapi.model.OWLDeclarationAxiom) {
            return true;
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLNaryClassAxiom n) {
            return n.getClassExpressions().size() < 2;
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLNaryPropertyAxiom<?> n) {
            return n.getProperties().size() < 2;
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom sub) {
            return sub.getSubProperty().equals(sub.getSuperProperty());
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom sub) {
            return sub.getSubProperty().equals(sub.getSuperProperty());
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom d) {
            return d.getDomain().isOWLThing();
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom r) {
            return r.getRange().isOWLThing();
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom d) {
            return d.getDomain().isOWLThing();
        }
        return false;
    }

    private static boolean isTopOrBottomSubclass(OWLAxiom axiom) {
        if (axiom instanceof org.semanticweb.owlapi.model.OWLSubClassOfAxiom sub) {
            return sub.getSuperClass().isOWLThing() || sub.getSubClass().isOWLNothing()
                    || selfForm(sub.getSubClass()).equals(selfForm(sub.getSuperClass()));
        }
        if (axiom instanceof org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom eq) {
            Set<OWLClassExpression> forms = new HashSet<>();
            for (OWLClassExpression expression : eq.getClassExpressions()) {
                forms.add(selfForm(expression));
            }
            return forms.size() < 2;
        }
        return false;
    }

    private static OWLClassExpression selfForm(OWLClassExpression expression) {
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectIntersectionOf intersection) {
            Set<OWLClassExpression> operands = new HashSet<>();
            for (OWLClassExpression operand : intersection.getOperands()) {
                if (!operand.isOWLThing()) {
                    operands.add(operand);
                }
            }
            if (operands.size() == 1) {
                return operands.iterator().next();
            }
        }
        return expression;
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

    private static boolean realName(OWLIndividual individual) {
        return individual.isNamed() && !placeholder(individual.asOWLNamedIndividual().getIRI().toString());
    }

    private static boolean placeholder(String text) {
        return text.contains("urn:ontocode:blank:");
    }

    static boolean isTrivial(OWLAxiom axiom) {
        return isTrivialSame(axiom) || isThingAssertion(axiom);
    }

    static String readable(OWLAxiom axiom) {
        return readable(axiom, AxiomWording.plain());
    }

    static String readable(OWLAxiom axiom, AxiomWording wording) {
        return withoutBlankNodeIris(wording.sentence(axiom));
    }

    private static final java.util.regex.Pattern BLANK_NODE = java.util.regex.Pattern.compile("<urn:ontocode:blank:[^>]*>");

    private static String withoutBlankNodeIris(String text) {
        return BLANK_NODE.matcher(text).replaceAll("_:anonymous");
    }

    static InferredAxiom format(OWLAxiom axiom) {
        return format(axiom, AxiomWording.plain());
    }

    static InferredAxiom format(OWLAxiom axiom, AxiomWording wording) {
        String subjectIri = null;
        String predicateIri = null;
        String objectIri = null;
        String objectLiteral = null;
        String literalDatatypeIri = null;
        String literalLangTag = null;

        if (axiom instanceof OWLClassAssertionAxiom classAssertion) {
            OWLIndividual individual = classAssertion.getIndividual();
            OWLClassExpression classExpr = classAssertion.getClassExpression();
            if (realName(individual) && !classExpr.isAnonymous()) {
                subjectIri = individual.asOWLNamedIndividual().getIRI().toString();
                predicateIri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
                objectIri = classExpr.asOWLClass().getIRI().toString();
            }
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom objectAssertion) {
            OWLIndividual subject = objectAssertion.getSubject();
            OWLIndividual object = objectAssertion.getObject();
            if (realName(subject) && realName(object) && !objectAssertion.getProperty().isAnonymous()) {
                subjectIri = subject.asOWLNamedIndividual().getIRI().toString();
                predicateIri = objectAssertion.getProperty().asOWLObjectProperty().getIRI().toString();
                objectIri = object.asOWLNamedIndividual().getIRI().toString();
            }
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom dataAssertion) {
            OWLIndividual subject = dataAssertion.getSubject();
            if (realName(subject) && !dataAssertion.getProperty().isAnonymous()) {
                subjectIri = subject.asOWLNamedIndividual().getIRI().toString();
                predicateIri = dataAssertion.getProperty().asOWLDataProperty().getIRI().toString();
                OWLLiteral literal = dataAssertion.getObject();
                objectLiteral = literal.getLiteral();
                literalDatatypeIri = literal.getDatatype().getIRI().toString();
                literalLangTag = literal.hasLang() ? literal.getLang() : null;
            }
        }

        return new InferredAxiom(axiom.getAxiomType().getName(), withoutBlankNodeIris(axiom.toString()), readable(axiom, wording),
                subjectIri, predicateIri, objectIri, objectLiteral, literalDatatypeIri, literalLangTag);
    }

    private static boolean isThingAssertion(OWLAxiom axiom) {
        return axiom instanceof OWLClassAssertionAxiom a && !a.getClassExpression().isAnonymous() && a.getClassExpression().isOWLThing();
    }

    private static boolean isTrivialSame(OWLAxiom axiom) {
        return axiom instanceof OWLSameIndividualAxiom same && new java.util.HashSet<>(same.getIndividuals()).size() < 2;
    }
}
