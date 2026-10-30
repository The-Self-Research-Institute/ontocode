package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.*;
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

    private static final int MAX_TEXT = 120;

    private static final java.util.regex.Pattern ANGLE_IRI = java.util.regex.Pattern.compile("<[^<>\\s]*[#/:]([^#/:<>\\s]+)>");

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

    private static String clip(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > MAX_TEXT ? flat.substring(0, MAX_TEXT) + "..." : flat;
    }

    private String literal(OWLLiteral literal) {
        String type = literal.getDatatype().getIRI().toString();
        if (literal.hasLang()) {
            return "\"" + clip(literal.getLiteral()) + "\"@" + literal.getLang();
        }
        if (type.endsWith("#integer") || type.endsWith("#decimal") || type.endsWith("#double") || type.endsWith("#float")
                || type.endsWith("#int") || type.endsWith("#long") || type.endsWith("#boolean")) {
            return literal.getLiteral();
        }
        if (type.endsWith("#string") || type.endsWith("PlainLiteral")) {
            return "\"" + clip(literal.getLiteral()) + "\"";
        }
        return "\"" + clip(literal.getLiteral()) + "\"^^" + name(literal.getDatatype().getIRI());
    }

    String sentence(OWLAxiom axiom) {
        String text = known(axiom);
        if (text == null) {
            try {
                text = renderer().render(axiom).replaceAll("\\s+", " ").trim();
            } catch (RuntimeException e) {
                text = axiom.toString();
            }
        }
        return ANGLE_IRI.matcher(text).replaceAll("$1");
    }

    private String property(OWLPropertyExpression expression) {
        if (expression instanceof OWLObjectPropertyExpression object) {
            return object.isAnonymous() ? "inverse " + name(object.getNamedProperty().getIRI()) : name(object.asOWLObjectProperty().getIRI());
        }
        if (expression instanceof OWLDataProperty data) {
            return name(data.getIRI());
        }
        return renderer().render(expression);
    }

    private String properties(java.util.Collection<? extends OWLPropertyExpression> expressions, String separator) {
        return expressions.stream().map(this::property).sorted().collect(Collectors.joining(separator));
    }

    private String range(OWLDataRange range) {
        if (range instanceof OWLDatatype) {
            return name(range.asOWLDatatype().getIRI());
        }
        return "(" + renderer().render(range).replaceAll("\\s+", " ").trim() + ")";
    }

    private String annotationProperty(IRI iri) {
        String full = iri.toString();
        return switch (full) {
            case "http://www.w3.org/2000/01/rdf-schema#label" -> "label";
            case "http://www.w3.org/2000/01/rdf-schema#comment" -> "comment";
            case "http://www.w3.org/2000/01/rdf-schema#seeAlso" -> "see also";
            case "http://www.w3.org/2000/01/rdf-schema#isDefinedBy" -> "is defined by";
            default -> name(iri);
        };
    }

    private String annotationValue(OWLAnnotationValue value) {
        if (value instanceof IRI iri) {
            return name(iri);
        }
        if (value instanceof OWLLiteral literal) {
            return literal(literal);
        }
        return "_:anonymous";
    }

    private String characteristic(OWLAxiom axiom) {
        if (axiom instanceof OWLFunctionalObjectPropertyAxiom a) {
            return property(a.getProperty()) + " is functional";
        }
        if (axiom instanceof OWLInverseFunctionalObjectPropertyAxiom a) {
            return property(a.getProperty()) + " is inverse functional";
        }
        if (axiom instanceof OWLTransitiveObjectPropertyAxiom a) {
            return property(a.getProperty()) + " is transitive";
        }
        if (axiom instanceof OWLSymmetricObjectPropertyAxiom a) {
            return property(a.getProperty()) + " is symmetric";
        }
        if (axiom instanceof OWLAsymmetricObjectPropertyAxiom a) {
            return property(a.getProperty()) + " is asymmetric";
        }
        if (axiom instanceof OWLReflexiveObjectPropertyAxiom a) {
            return property(a.getProperty()) + " is reflexive";
        }
        if (axiom instanceof OWLIrreflexiveObjectPropertyAxiom a) {
            return property(a.getProperty()) + " is irreflexive";
        }
        if (axiom instanceof OWLFunctionalDataPropertyAxiom a) {
            return property(a.getProperty()) + " is functional";
        }
        return null;
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
        if (axiom instanceof OWLAnnotationAssertionAxiom a) {
            String subject = a.getSubject() instanceof IRI iri ? name(iri) : "_:anonymous";
            return subject + " has " + annotationProperty(a.getProperty().getIRI()) + " " + annotationValue(a.getValue());
        }
        if (axiom instanceof OWLInverseObjectPropertiesAxiom a) {
            return property(a.getFirstProperty()) + " is the inverse of " + property(a.getSecondProperty());
        }
        if (axiom instanceof OWLDataPropertyRangeAxiom a) {
            return property(a.getProperty()) + " holds " + range(a.getRange());
        }
        if (axiom instanceof OWLDifferentIndividualsAxiom a) {
            return a.getIndividualsAsList().stream().map(this::individual).collect(Collectors.joining(", ")) + " are different from each other";
        }
        if (axiom instanceof OWLSameIndividualAxiom a) {
            return a.getIndividualsAsList().stream().map(this::individual).collect(Collectors.joining(" is the same as "));
        }
        if (axiom instanceof OWLNegativeObjectPropertyAssertionAxiom a) {
            return individual(a.getSubject()) + " does not " + property(a.getProperty()) + " " + individual(a.getObject());
        }
        if (axiom instanceof OWLNegativeDataPropertyAssertionAxiom a) {
            return individual(a.getSubject()) + " does not have " + property(a.getProperty()) + " " + literal(a.getObject());
        }
        if (axiom instanceof OWLHasKeyAxiom a) {
            return operand(a.getClassExpression()) + " is identified by " + properties(a.getPropertyExpressions(), " and ");
        }
        if (axiom instanceof OWLDisjointUnionAxiom a) {
            return name(a.getOWLClass().getIRI()) + " is exactly one of " + a.getClassExpressions().stream().map(this::operand).sorted().collect(Collectors.joining(", "));
        }
        if (axiom instanceof OWLEquivalentObjectPropertiesAxiom a) {
            return properties(a.getProperties(), " is the same as ");
        }
        if (axiom instanceof OWLEquivalentDataPropertiesAxiom a) {
            return properties(a.getProperties(), " is the same as ");
        }
        if (axiom instanceof OWLDisjointObjectPropertiesAxiom a) {
            return properties(a.getProperties(), " and ") + " cannot overlap";
        }
        if (axiom instanceof OWLDisjointDataPropertiesAxiom a) {
            return properties(a.getProperties(), " and ") + " cannot overlap";
        }
        if (axiom instanceof OWLSubPropertyChainOfAxiom a) {
            return a.getPropertyChain().stream().map(this::property).collect(Collectors.joining(" then ")) + " implies " + property(a.getSuperProperty());
        }
        if (axiom instanceof OWLSubAnnotationPropertyOfAxiom a) {
            return name(a.getSubProperty().getIRI()) + " is a kind of " + name(a.getSuperProperty().getIRI());
        }
        if (axiom instanceof OWLAnnotationPropertyDomainAxiom a) {
            return name(a.getProperty().getIRI()) + " applies to " + name(a.getDomain());
        }
        if (axiom instanceof OWLAnnotationPropertyRangeAxiom a) {
            return name(a.getProperty().getIRI()) + " points to " + name(a.getRange());
        }
        if (axiom instanceof OWLDeclarationAxiom a) {
            return name(a.getEntity().getIRI()) + " is declared";
        }
        return characteristic(axiom);
    }

    private String joined(List<OWLClassExpression> expressions, String separator) {
        return expressions.stream().map(this::operand).collect(Collectors.joining(separator));
    }
}
