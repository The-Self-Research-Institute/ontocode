package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectComplementOf;
import org.semanticweb.owlapi.model.OWLObjectHasValue;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class AxiomPlainText {

    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    private static final Pattern FULL_IRI = Pattern.compile("<([^<>\\s]+)>");
    static final int MAX_LINES = 10;

    private AxiomPlainText() {
    }

    static String summarize(Collection<OWLAxiom> axioms, IRI entity) {
        Set<String> lines = new LinkedHashSet<>();
        for (OWLAxiom axiom : axioms) {
            lines.add(describe(axiom, entity));
        }
        List<String> shown = lines.stream().limit(MAX_LINES).collect(Collectors.toList());
        String text = String.join("\n", shown);
        return lines.size() > MAX_LINES ? text + "\n+ " + (lines.size() - MAX_LINES) + " more" : text;
    }

    static String describe(OWLAxiom axiom, IRI entity) {
        if (axiom instanceof OWLDeclarationAxiom declaration) {
            return "This is " + entityKind(declaration.getEntity());
        }
        if (axiom instanceof OWLSubClassOfAxiom sub) {
            if (isEntity(sub.getSubClass(), entity)) {
                return sub.getSuperClass().isAnonymous()
                        ? "Must satisfy: " + render(sub.getSuperClass())
                        : "Kind of: " + render(sub.getSuperClass());
            }
            if (isEntity(sub.getSuperClass(), entity)) {
                return "Parent of: " + render(sub.getSubClass());
            }
            return "Used in: " + render(sub.getSubClass()) + " — " + render(sub.getSuperClass());
        }
        if (axiom instanceof OWLEquivalentClassesAxiom equivalent) {
            return "Same as: " + others(equivalent, entity);
        }
        if (axiom instanceof OWLDisjointClassesAxiom disjoint) {
            return "Cannot overlap with: " + others(disjoint, entity);
        }
        if (axiom instanceof OWLClassAssertionAxiom typed) {
            return "Is a: " + render(typed.getClassExpression());
        }
        if (axiom instanceof OWLObjectPropertyAssertionAxiom related) {
            String property = shorten(related.getProperty().toString());
            String subject = shorten(related.getSubject().toString());
            String object = shorten(related.getObject().toString());
            if (related.getSubject().isNamed()
                    && related.getSubject().asOWLNamedIndividual().getIRI().equals(entity)) {
                return property + ": " + object;
            }
            return "Used by: " + subject + " (" + property + ")";
        }
        if (axiom instanceof OWLDataPropertyAssertionAxiom data) {
            return shorten(data.getProperty().toString()) + ": \"" + data.getObject().getLiteral() + "\"";
        }
        if (axiom instanceof OWLAnnotationAssertionAxiom annotation) {
            return annotationLine(annotation);
        }
        if (axiom instanceof OWLPropertyDomainAxiom<?> domain) {
            return "Applies to: " + render(domain.getDomain());
        }
        if (axiom instanceof OWLPropertyRangeAxiom<?, ?> range) {
            return "Values are: " + shorten(String.valueOf(range.getRange()));
        }
        return shorten(axiom.toString());
    }

    private static String annotationLine(OWLAnnotationAssertionAxiom annotation) {
        String property = annotation.getProperty().getIRI().toString();
        String value = annotationValue(annotation.getValue());
        if (RDFS_LABEL.equals(property)) {
            return "Label: \"" + value + "\"";
        }
        if (RDFS_COMMENT.equals(property)) {
            return "Comment: \"" + value + "\"";
        }
        return shortName(property) + ": \"" + value + "\"";
    }

    private static String annotationValue(OWLAnnotationValue value) {
        return value.asLiteral().map(literal -> literal.getLiteral())
                .orElseGet(() -> value.asIRI().map(iri -> shortName(iri.toString())).orElse(value.toString()));
    }

    private static String entityKind(OWLEntity entity) {
        return switch (entity.getEntityType().getName()) {
            case "Class" -> "a class";
            case "NamedIndividual" -> "an individual";
            case "ObjectProperty" -> "an object property";
            case "DataProperty" -> "a data property";
            case "AnnotationProperty" -> "an annotation property";
            case "Datatype" -> "a datatype";
            default -> "an entity";
        };
    }

    private static boolean isEntity(OWLClassExpression expression, IRI entity) {
        return !expression.isAnonymous() && expression.asOWLClass().getIRI().equals(entity);
    }

    private static String others(OWLNaryClassAxiom axiom, IRI entity) {
        String text = axiom.getClassExpressionsAsList().stream()
                .filter(expression -> !isEntity(expression, entity))
                .map(AxiomPlainText::render)
                .collect(Collectors.joining(", "));
        return text.isEmpty() ? "(itself)" : text;
    }

    static String render(OWLClassExpression expression) {
        if (!expression.isAnonymous()) {
            return shortName(expression.asOWLClass().getIRI().toString());
        }
        if (expression instanceof OWLObjectSomeValuesFrom some) {
            return "has some " + shorten(some.getProperty().toString()) + " " + render(some.getFiller());
        }
        if (expression instanceof OWLObjectAllValuesFrom only) {
            return "has only " + shorten(only.getProperty().toString()) + " " + render(only.getFiller());
        }
        if (expression instanceof OWLObjectHasValue hasValue) {
            return shorten(hasValue.getProperty().toString()) + " " + shorten(hasValue.getFiller().toString());
        }
        if (expression instanceof OWLObjectIntersectionOf all) {
            return all.getOperandsAsList().stream().map(AxiomPlainText::render).collect(Collectors.joining(" and "));
        }
        if (expression instanceof OWLObjectUnionOf any) {
            return any.getOperandsAsList().stream().map(AxiomPlainText::render).collect(Collectors.joining(" or "));
        }
        if (expression instanceof OWLObjectComplementOf not) {
            return "not " + render(not.getOperand());
        }
        return shorten(expression.toString());
    }

    static String shortName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        String name = cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    static String shorten(String text) {
        return FULL_IRI.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(shortName(match.group(1))));
    }
}
