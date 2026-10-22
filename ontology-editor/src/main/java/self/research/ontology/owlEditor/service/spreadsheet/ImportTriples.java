package self.research.ontology.owlEditor.service.spreadsheet;

import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ImportTriples {

    private static final String RDF_TYPE = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final String RDFS_LABEL = "<" + RDFS + "label>";

    private ImportTriples() {
    }

    static List<String> of(MutationOp op) {
        List<String> t = new ArrayList<>();
        String s = iri(op.iri());
        switch (op.type()) {
            case "createClass" -> {
                t.add(s + " " + RDF_TYPE + " " + iri(OWL + "Class"));
                label(t, s, op.label());
                if (OntologyMutationService.isRealParentClass(op.parent())) {
                    t.add(s + " " + iri(RDFS + "subClassOf") + " " + iri(op.parent()));
                }
            }
            case "createIndividual" -> {
                t.add(s + " " + RDF_TYPE + " " + iri(OWL + "NamedIndividual"));
                label(t, s, op.label());
                t.add(s + " " + RDF_TYPE + " " + iri(op.classIri()));
            }
            case "createObjectProperty", "createDataProperty", "createAnnotationProperty" -> {
                String kind = switch (op.type()) {
                    case "createObjectProperty" -> "ObjectProperty";
                    case "createDataProperty" -> "DatatypeProperty";
                    default -> "AnnotationProperty";
                };
                t.add(s + " " + RDF_TYPE + " " + iri(OWL + kind));
                label(t, s, op.label());
                if (isRealParentProperty(op.parent())) {
                    t.add(s + " " + iri(RDFS + "subPropertyOf") + " " + iri(op.parent()));
                }
            }
            case "createDatatype" -> {
                t.add(s + " " + RDF_TYPE + " " + iri(RDFS + "Datatype"));
                label(t, s, op.label());
            }
            case "addClassAssertion" -> t.add(s + " " + RDF_TYPE + " " + iri(op.classIri()));
            case "addAnnotation", "addDataPropertyAssertion" ->
                t.add(s + " " + iri(op.property()) + " " + literal(op.value(), op.language(), op.datatype()));
            case "addObjectPropertyAssertion" -> t.add(s + " " + iri(op.property()) + " " + iri(op.target()));
            case "addSubClassOf" -> t.add(s + " " + iri(RDFS + "subClassOf") + " " + iri(op.target()));
            case "addEquivalentClass" -> t.add(s + " " + iri(OWL + "equivalentClass") + " " + iri(op.target()));
            case "addDisjointWith" -> t.add(s + " " + iri(OWL + "disjointWith") + " " + iri(op.target()));
            case "addSubPropertyOf" -> t.add(s + " " + iri(RDFS + "subPropertyOf") + " " + iri(op.target()));
            case "addPropertyDomain" -> t.add(s + " " + iri(RDFS + "domain") + " " + iri(op.target()));
            case "addPropertyRange" -> t.add(s + " " + iri(RDFS + "range") + " " + iri(op.target()));
            case "addCharacteristic" -> t.add(s + " " + RDF_TYPE + " " + iri(op.target()));
            case "addSameIndividual" -> symmetric(t, op, OWL + "sameAs");
            case "addDifferentIndividual" -> symmetric(t, op, OWL + "differentFrom");
            case "addInverseProperty" -> symmetric(t, op, OWL + "inverseOf");
            default -> {
            }
        }
        return t;
    }

    private static void symmetric(List<String> t, MutationOp op, String predicate) {
        t.add(iri(op.iri()) + " " + iri(predicate) + " " + iri(op.target()));
        t.add(iri(op.target()) + " " + iri(predicate) + " " + iri(op.iri()));
    }

    private static void label(List<String> t, String subject, String label) {
        if (label != null && !label.isBlank()) {
            t.add(subject + " " + RDFS_LABEL + " " + literal(label, null, null));
        }
    }

    private static boolean isRealParentProperty(String parent) {
        return parent != null && !parent.isEmpty()
            && !parent.contains("topObjectProperty") && !parent.contains("topDataProperty");
    }

    private static String iri(String iri) {
        if (!AxiomGenerator.isSafeIri(iri)) {
            throw new IllegalArgumentException("Unsafe IRI in import: " + iri);
        }
        return "<" + iri + ">";
    }

    static String literal(String value, String language, String datatype) {
        String v = value == null ? "" : value;
        String escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        String base = "\"" + escaped + "\"";
        if (language != null && !language.isBlank()) {
            return base + "@" + language.trim().toLowerCase(Locale.ROOT);
        }
        if (datatype != null && !datatype.isBlank()) {
            return base + "^^" + iri(datatype);
        }
        return base;
    }
}
