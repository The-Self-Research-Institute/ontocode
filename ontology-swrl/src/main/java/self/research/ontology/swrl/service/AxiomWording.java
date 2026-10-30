package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.PrefixManager;
import org.semanticweb.owlapi.util.ShortFormProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class AxiomWording {

    private static final Map<String, String> WELL_KNOWN = Map.of(
            "http://www.w3.org/2002/07/owl#", "owl:",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:",
            "http://www.w3.org/2000/01/rdf-schema#", "rdfs:",
            "http://www.w3.org/2001/XMLSchema#", "xsd:");

    private static final String PLACEHOLDER = "urn:ontocode:blank:";

    private final PrefixManager prefixes;
    private ManchesterOWLSyntaxOWLObjectRendererImpl renderer;

    private AxiomWording(PrefixManager prefixes) {
        this.prefixes = prefixes;
    }

    static AxiomWording plain() {
        return new AxiomWording(null);
    }

    static AxiomWording of(OWLOntology ontology) {
        try {
            OWLDocumentFormat format = ontology.getOWLOntologyManager().getOntologyFormat(ontology);
            if (format instanceof PrefixDocumentFormat prefixFormat) {
                return new AxiomWording(prefixFormat);
            }
        } catch (RuntimeException e) {
            return plain();
        }
        return plain();
    }

    String name(IRI iri) {
        String full = iri.toString();
        for (Map.Entry<String, String> known : WELL_KNOWN.entrySet()) {
            if (full.startsWith(known.getKey())) {
                return known.getValue() + full.substring(known.getKey().length());
            }
        }
        if (prefixes != null) {
            String curie = prefixes.getPrefixIRI(iri);
            if (curie != null && !curie.isEmpty()) {
                return curie.startsWith(":") ? curie.substring(1) : curie;
            }
        }
        int cut = Math.max(full.lastIndexOf('#'), full.lastIndexOf('/'));
        return cut >= 0 && cut < full.length() - 1 ? full.substring(cut + 1) : full;
    }

    private ManchesterOWLSyntaxOWLObjectRendererImpl renderer() {
        if (renderer == null) {
            renderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
            renderer.setShortFormProvider(new ShortFormProvider() {
                @Override
                public String getShortForm(OWLEntity entity) {
                    return name(entity.getIRI());
                }

                @Override
                public void dispose() {
                }
            });
        }
        return renderer;
    }

    private String individual(OWLIndividual individual) {
        if (individual.isNamed()) {
            IRI iri = individual.asOWLNamedIndividual().getIRI();
            return iri.toString().startsWith(PLACEHOLDER) ? "_:anonymous" : name(iri);
        }
        return individual.toStringID();
    }

    private String operand(OWLClassExpression expression) {
        if (!expression.isAnonymous()) {
            return name(expression.asOWLClass().getIRI());
        }
        return "(" + renderer().render(expression).replaceAll("\\s+", " ").trim() + ")";
    }

    private String literal(OWLLiteral literal) {
        String type = literal.getDatatype().getIRI().toString();
        if (literal.hasLang()) {
            return "\"" + literal.getLiteral() + "\"@" + literal.getLang();
        }
        if (type.endsWith("#integer") || type.endsWith("#decimal") || type.endsWith("#double") || type.endsWith("#float")
                || type.endsWith("#int") || type.endsWith("#long") || type.endsWith("#boolean")) {
            return literal.getLiteral();
        }
        if (type.endsWith("#string") || type.endsWith("PlainLiteral")) {
            return "\"" + literal.getLiteral() + "\"";
        }
        return "\"" + literal.getLiteral() + "\"^^" + name(literal.getDatatype().getIRI());
    }

    String sentence(OWLAxiom axiom) {
        String text = known(axiom);
        if (text != null) {
            return text;
        }
        try {
            return renderer().render(axiom).replaceAll("\\s+", " ").trim();
        } catch (RuntimeException e) {
            return axiom.toString();
        }
    }

    private String known(OWLAxiom axiom) {
        if (axiom instanceof OWLClassAssertionAxiom a) {
            return individual(a.getIndividual()) + " is a " + operand(a.getClassExpression());
        }
        if (axiom instanceof OWLObjectPropertyAssertionAxiom a) {
            String property = a.getProperty().isAnonymous()
                    ? "inverse " + name(a.getProperty().getNamedProperty().getIRI())
                    : name(a.getProperty().asOWLObjectProperty().getIRI());
            return individual(a.getSubject()) + " " + property + " " + individual(a.getObject());
        }
        if (axiom instanceof OWLDataPropertyAssertionAxiom a && !a.getProperty().isAnonymous()) {
            return individual(a.getSubject()) + " " + name(a.getProperty().asOWLDataProperty().getIRI()) + " " + literal(a.getObject());
        }
        if (axiom instanceof OWLSubClassOfAxiom a) {
            return operand(a.getSubClass()) + " is a kind of " + operand(a.getSuperClass());
        }
        if (axiom instanceof OWLEquivalentClassesAxiom a) {
            return joined(a.getClassExpressionsAsList(), " is the same as ");
        }
        if (axiom instanceof OWLDisjointClassesAxiom a) {
            return joined(a.getClassExpressionsAsList(), " and ") + " cannot overlap";
        }
        if (axiom instanceof OWLObjectPropertyDomainAxiom a && !a.getProperty().isAnonymous()) {
            return name(a.getProperty().asOWLObjectProperty().getIRI()) + " applies to " + operand(a.getDomain());
        }
        if (axiom instanceof OWLObjectPropertyRangeAxiom a && !a.getProperty().isAnonymous()) {
            return name(a.getProperty().asOWLObjectProperty().getIRI()) + " points to " + operand(a.getRange());
        }
        if (axiom instanceof OWLDataPropertyDomainAxiom a && !a.getProperty().isAnonymous()) {
            return name(a.getProperty().asOWLDataProperty().getIRI()) + " applies to " + operand(a.getDomain());
        }
        if (axiom instanceof OWLSubObjectPropertyOfAxiom a && !a.getSubProperty().isAnonymous() && !a.getSuperProperty().isAnonymous()) {
            return name(a.getSubProperty().asOWLObjectProperty().getIRI()) + " is a kind of " + name(a.getSuperProperty().asOWLObjectProperty().getIRI());
        }
        if (axiom instanceof OWLSubDataPropertyOfAxiom a && !a.getSubProperty().isAnonymous() && !a.getSuperProperty().isAnonymous()) {
            return name(a.getSubProperty().asOWLDataProperty().getIRI()) + " is a kind of " + name(a.getSuperProperty().asOWLDataProperty().getIRI());
        }
        return null;
    }

    private String joined(List<OWLClassExpression> expressions, String separator) {
        return expressions.stream().map(this::operand).collect(Collectors.joining(separator));
    }
}
