package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.owlEditor.util.AnnotationValueCollector;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared OWLAPI helpers for desktop query services (labels, annotations, pagination).
 */
public final class OwlApiQuerySupport {

    public static final Imports IMPORTS_EXCLUDED = Imports.EXCLUDED;

    private static final IRI RDFS_LABEL = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
    private static final IRI RDFS_COMMENT = IRI.create("http://www.w3.org/2000/01/rdf-schema#comment");

    private OwlApiQuerySupport() {}

    public static String getLabel(OWLOntology ont, IRI iri) {
        return ont.annotationAssertionAxioms(iri, IMPORTS_EXCLUDED)
            .filter(ax -> ax.getProperty().getIRI().equals(RDFS_LABEL))
            .findFirst()
            .flatMap(ax -> ax.getValue().asLiteral())
            .map(OWLLiteral::getLiteral)
            .orElse(iri.getShortForm());
    }

    public static String getComment(OWLOntology ont, IRI iri) {
        return ont.annotationAssertionAxioms(iri, IMPORTS_EXCLUDED)
            .filter(ax -> ax.getProperty().getIRI().equals(RDFS_COMMENT))
            .findFirst()
            .flatMap(ax -> ax.getValue().asLiteral())
            .map(OWLLiteral::getLiteral)
            .orElse("");
    }

    public static Map<String, List<String>> collectAnnotations(OWLOntology ont, IRI entityIri) {
        Map<String, List<String>> annotations = AnnotationValueCollector.newMap();
        ont.annotationAssertionAxioms(entityIri, IMPORTS_EXCLUDED).forEach(ax -> {
            String prop = ax.getProperty().getIRI().toString();
            ax.getValue().asLiteral().ifPresent(lit ->
                AnnotationValueCollector.add(annotations, prop, lit.getLiteral()));
            ax.getValue().asIRI().ifPresent(iri ->
                AnnotationValueCollector.add(annotations, prop, iri.toString()));
        });
        return annotations;
    }

    public static <T> List<T> paginate(List<T> all, int limit, int offset) {
        int safeLimit = Math.max(1, limit);
        int safeOffset = Math.max(0, offset);
        if (safeOffset >= all.size()) {
            return List.of();
        }
        return all.subList(safeOffset, Math.min(safeOffset + safeLimit, all.size()));
    }

    public static Map<String, String> usageEntry(String type, String subject, String subjectLabel, String context) {
        Map<String, String> usage = new LinkedHashMap<>();
        usage.put("type", type);
        usage.put("subject", subject);
        usage.put("subjectLabel", subjectLabel);
        usage.put("context", context);
        return usage;
    }

    public static String entityIriString(OWLEntity entity) {
        return entity.getIRI().toString();
    }

    public static List<String> standardDatatypeIris() {
        Set<String> iris = new LinkedHashSet<>();
        iris.add("http://www.w3.org/2002/07/owl#rational");
        iris.add("http://www.w3.org/2002/07/owl#real");
        iris.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString");
        iris.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral");
        iris.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral");
        iris.add("http://www.w3.org/2000/01/rdf-schema#Literal");
        String[] xsdTypes = {
            "anyURI", "base64Binary", "boolean", "byte", "date", "dateTime", "dateTimeStamp",
            "decimal", "double", "float", "hexBinary", "int", "integer", "language",
            "long", "Name", "NCName", "negativeInteger", "NMTOKEN", "nonNegativeInteger",
            "nonPositiveInteger", "normalizedString", "positiveInteger", "short", "string",
            "time", "token", "unsignedByte", "unsignedInt", "unsignedLong", "unsignedShort"
        };
        for (String xsdType : xsdTypes) {
            iris.add("http://www.w3.org/2001/XMLSchema#" + xsdType);
        }
        return new ArrayList<>(iris);
    }

    /**
     * Renders a class expression the same way for every consumer: the desktop hierarchy service
     * uses this to build the "definition" text shown in the UI for anonymous SubClassOf/GCI/
     * EquivalentTo entries, and {@code OwlApiMutationPatcher} uses the SAME method to re-render
     * candidates when matching a delete request by definition text (desktop has no stable
     * Fuseki blank-node id for these, since the in-memory model is a separate parse — see
     * OwlApiMutationPatcher's deleteAxiom/deleteSubClassOf/deleteEquivalentClass handling).
     * Keeping one implementation guarantees the rendering used to label an entry always matches
     * the rendering used to find it again later.
     */
    public static String classExpressionToManchester(OWLOntology ont, OWLClassExpression ce) {
        if (!ce.isAnonymous()) {
            return getLabel(ont, ce.asOWLClass().getIRI());
        } else if (ce instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom r = (OWLObjectSomeValuesFrom) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " some " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom r = (OWLObjectAllValuesFrom) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " only " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality r = (OWLObjectMinCardinality) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " min " + r.getCardinality() + " " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectMaxCardinality) {
            OWLObjectMaxCardinality r = (OWLObjectMaxCardinality) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " max " + r.getCardinality() + " " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectExactCardinality) {
            OWLObjectExactCardinality r = (OWLObjectExactCardinality) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " exactly " + r.getCardinality() + " " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectHasValue) {
            OWLObjectHasValue r = (OWLObjectHasValue) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            String ind = r.getFiller().isAnonymous() ? "?" : getLabel(ont, r.getFiller().asOWLNamedIndividual().getIRI());
            return p + " value " + ind;
        } else if (ce instanceof OWLObjectHasSelf) {
            OWLObjectHasSelf r = (OWLObjectHasSelf) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " Self";
        } else if (ce instanceof OWLDataSomeValuesFrom) {
            OWLDataSomeValuesFrom r = (OWLDataSomeValuesFrom) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " some " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataAllValuesFrom) {
            OWLDataAllValuesFrom r = (OWLDataAllValuesFrom) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " only " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataMinCardinality) {
            OWLDataMinCardinality r = (OWLDataMinCardinality) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " min " + r.getCardinality() + " " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataMaxCardinality) {
            OWLDataMaxCardinality r = (OWLDataMaxCardinality) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " max " + r.getCardinality() + " " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataExactCardinality) {
            OWLDataExactCardinality r = (OWLDataExactCardinality) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " exactly " + r.getCardinality() + " " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataHasValue) {
            OWLDataHasValue r = (OWLDataHasValue) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " value " + r.getFiller().getLiteral();
        } else if (ce instanceof OWLObjectIntersectionOf) {
            return ((OWLObjectIntersectionOf) ce).operands()
                .map(op -> classExpressionToManchester(ont, op))
                .collect(Collectors.joining(" and "));
        } else if (ce instanceof OWLObjectUnionOf) {
            return ((OWLObjectUnionOf) ce).operands()
                .map(op -> classExpressionToManchester(ont, op))
                .collect(Collectors.joining(" or "));
        } else if (ce instanceof OWLObjectComplementOf) {
            return "not " + classExpressionToManchester(ont, ((OWLObjectComplementOf) ce).getOperand());
        } else if (ce instanceof OWLObjectOneOf) {
            String inds = ((OWLObjectOneOf) ce).individuals()
                .map(i -> i.isAnonymous() ? "?" : getLabel(ont, i.asOWLNamedIndividual().getIRI()))
                .collect(Collectors.joining(", "));
            return "{" + inds + "}";
        }
        return ce.getClassExpressionType().getName();
    }

    public static String dataRangeToString(OWLDataRange range) {
        if (range instanceof OWLDatatype) {
            IRI dtIri = ((OWLDatatype) range).getIRI();
            String full = dtIri.toString();
            String xsdPrefix = "http://www.w3.org/2001/XMLSchema#";
            if (full.startsWith(xsdPrefix)) return "xsd:" + full.substring(xsdPrefix.length());
            return dtIri.getShortForm();
        }
        return range.getClass().getSimpleName();
    }

    /** True when {@code expr} is exactly {@code target}, or an anonymous expression built on top of it. */
    public static boolean expressionReferencesClass(OWLClassExpression expr, OWLClass target) {
        if (expr.equals(target)) {
            return true;
        }
        if (!expr.isAnonymous()) {
            return false;
        }
        if (expr instanceof OWLObjectIntersectionOf intersection) {
            return intersection.operands().anyMatch(op -> expressionReferencesClass(op, target));
        }
        if (expr instanceof OWLObjectUnionOf union) {
            return union.operands().anyMatch(op -> expressionReferencesClass(op, target));
        }
        if (expr instanceof OWLObjectSomeValuesFrom some) {
            return expressionReferencesClass(some.getFiller(), target);
        }
        if (expr instanceof OWLObjectAllValuesFrom all) {
            return expressionReferencesClass(all.getFiller(), target);
        }
        if (expr instanceof OWLObjectMinCardinality min) {
            return expressionReferencesClass(min.getFiller(), target);
        }
        if (expr instanceof OWLObjectMaxCardinality max) {
            return expressionReferencesClass(max.getFiller(), target);
        }
        if (expr instanceof OWLObjectExactCardinality exact) {
            return expressionReferencesClass(exact.getFiller(), target);
        }
        if (expr instanceof OWLObjectComplementOf complement) {
            return expressionReferencesClass(complement.getOperand(), target);
        }
        return false;
    }
}
