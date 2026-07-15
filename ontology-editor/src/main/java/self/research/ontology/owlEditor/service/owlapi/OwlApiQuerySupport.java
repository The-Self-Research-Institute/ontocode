package self.research.ontology.owlEditor.service.owlapi;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.owlEditor.util.AnnotationValueCollector;

import java.util.*;

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
}
