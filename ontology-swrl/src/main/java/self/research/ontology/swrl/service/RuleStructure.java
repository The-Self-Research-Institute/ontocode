package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.HashSet;
import java.util.Set;

final class RuleStructure {

    private static final String[] NAMESPACES = {
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2003/11/swrl#",
            "http://www.w3.org/2003/11/swrlb#",
            "http://swrl.stanford.edu/ontologies/3.3/swrla.owl#"
    };

    private final Set<OWLIndividual> anonymous;

    private RuleStructure(Set<OWLIndividual> anonymous) {
        this.anonymous = anonymous;
    }

    static RuleStructure none() {
        return new RuleStructure(Set.of());
    }

    static RuleStructure of(OWLOntology ontology) {
        Set<OWLIndividual> found = new HashSet<>();
        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (!axiom.getClassExpression().isAnonymous() && vocabulary(axiom.getClassExpression().asOWLClass().getIRI())
                    && !axiom.getIndividual().isNamed()) {
                found.add(axiom.getIndividual());
            }
        }
        for (OWLObjectPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            if (!axiom.getProperty().isAnonymous() && vocabulary(axiom.getProperty().asOWLObjectProperty().getIRI())) {
                addAnonymous(found, axiom.getSubject());
                addAnonymous(found, axiom.getObject());
            }
        }
        for (OWLDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            if (!axiom.getProperty().isAnonymous() && vocabulary(axiom.getProperty().asOWLDataProperty().getIRI())) {
                addAnonymous(found, axiom.getSubject());
            }
        }
        return found.isEmpty() ? none() : new RuleStructure(found);
    }

    private static void addAnonymous(Set<OWLIndividual> into, OWLIndividual individual) {
        if (!individual.isNamed()) {
            into.add(individual);
        }
    }

    static boolean vocabulary(IRI iri) {
        String text = iri.toString();
        for (String namespace : NAMESPACES) {
            if (text.startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }

    boolean isArtifact(OWLAxiom axiom) {
        if (axiom instanceof OWLClassAssertionAxiom a) {
            if (!a.getClassExpression().isAnonymous() && vocabulary(a.getClassExpression().asOWLClass().getIRI())) {
                return true;
            }
            return anonymous.contains(a.getIndividual());
        }
        if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
            if (!a.getProperty().isAnonymous() && vocabulary(a.getProperty().asOWLObjectProperty().getIRI())) {
                return true;
            }
            return anonymous.contains(a.getSubject()) || anonymous.contains(a.getObject());
        }
        if (axiom instanceof OWLDataPropertyAssertionAxiom a) {
            if (!a.getProperty().isAnonymous() && vocabulary(a.getProperty().asOWLDataProperty().getIRI())) {
                return true;
            }
            return anonymous.contains(a.getSubject());
        }
        return false;
    }
}
