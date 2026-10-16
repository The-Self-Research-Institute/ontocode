package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import self.research.ontology.owlEditor.service.delta.CanonicalGraph;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
final class AxiomDelta {

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final Set<String> DECLARED_TYPES = Set.of(
            OWLRDFVocabulary.OWL_CLASS.getIRI().toString(),
            OWLRDFVocabulary.OWL_OBJECT_PROPERTY.getIRI().toString(),
            OWLRDFVocabulary.OWL_DATA_PROPERTY.getIRI().toString(),
            OWLRDFVocabulary.OWL_ANNOTATION_PROPERTY.getIRI().toString(),
            OWLRDFVocabulary.OWL_NAMED_INDIVIDUAL.getIRI().toString(),
            OWLRDFVocabulary.RDFS_DATATYPE.getIRI().toString());

    record Before(Set<OWLAxiom> axioms, Set<String> entityIris, Set<OWLAnnotation> annotations, Set<String> imports) {}

    private AxiomDelta() {
    }

    static Before capture(OWLOntology ontology) {
        Set<String> iris = new HashSet<>();
        for (OWLEntity entity : ontology.getSignature()) {
            iris.add(entity.getIRI().toString());
        }
        return new Before(new HashSet<>(ontology.getAxioms()), iris, new HashSet<>(ontology.getAnnotations()),
                importsOf(ontology));
    }

    private static Set<String> importsOf(OWLOntology ontology) {
        Set<String> imports = new HashSet<>();
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            imports.add(declaration.getIRI().toString());
        }
        return imports;
    }

    static Optional<ExactDelta> between(OWLOntologyManager manager, Before before, OWLOntology after, int maxTriples) {
        Set<OWLAxiom> afterAxioms = new HashSet<>(after.getAxioms());
        Set<OWLAxiom> added = new HashSet<>(afterAxioms);
        added.removeAll(before.axioms());
        Set<OWLAxiom> removed = new HashSet<>(before.axioms());
        removed.removeAll(afterAxioms);
        Set<OWLAnnotation> afterAnnotations = new HashSet<>(after.getAnnotations());
        Set<String> afterImports = importsOf(after);
        boolean headerChanged = !afterAnnotations.equals(before.annotations()) || !afterImports.equals(before.imports());
        if (added.isEmpty() && removed.isEmpty() && !headerChanged) {
            return Optional.empty();
        }
        if ((long) added.size() + removed.size() > maxTriples) {
            return Optional.empty();
        }
        Set<String> afterIris = new HashSet<>();
        for (OWLEntity entity : after.getSignature()) {
            afterIris.add(entity.getIRI().toString());
        }
        try {
            Model inserted = render(manager, added, before.entityIris());
            Model deleted = render(manager, removed, afterIris);
            if (headerChanged) {
                if (after.getOntologyID().isAnonymous()) {
                    return Optional.empty();
                }
                String subject = after.getOntologyID().getOntologyIRI().get().toString();
                if (!addHeader(inserted, subject, minus(afterAnnotations, before.annotations()), minus(afterImports, before.imports()))
                        || !addHeader(deleted, subject, minus(before.annotations(), afterAnnotations), minus(before.imports(), afterImports))) {
                    return Optional.empty();
                }
            }
            if (inserted.size() + deleted.size() > maxTriples || !DeltaSparql.isRenderable(inserted)
                    || !DeltaSparql.isRenderable(deleted)) {
                return Optional.empty();
            }
            ExactDelta delta = new ExactDelta(CanonicalGraph.canonicalise(deleted), CanonicalGraph.canonicalise(inserted));
            return delta.isEmpty() ? Optional.empty() : Optional.of(delta);
        } catch (Exception e) {
            log.warn("[UNDO] Could not work out the exact axiom changes: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static <T> Set<T> minus(Set<T> all, Set<T> others) {
        Set<T> result = new HashSet<>(all);
        result.removeAll(others);
        return result;
    }

    private static boolean addHeader(Model model, String ontologyIri, Set<OWLAnnotation> annotations, Set<String> imports) {
        ValueFactory values = SimpleValueFactory.getInstance();
        org.eclipse.rdf4j.model.IRI subject = values.createIRI(ontologyIri);
        for (String imported : imports) {
            model.add(subject, values.createIRI(OWLRDFVocabulary.OWL_IMPORTS.getIRI().toString()), values.createIRI(imported));
        }
        for (OWLAnnotation annotation : annotations) {
            if (!annotation.getAnnotations().isEmpty()) {
                return false;
            }
            org.eclipse.rdf4j.model.Value value;
            if (annotation.getValue() instanceof org.semanticweb.owlapi.model.IRI iri) {
                value = values.createIRI(iri.toString());
            } else if (annotation.getValue() instanceof OWLLiteral literal) {
                value = literal.hasLang() ? values.createLiteral(literal.getLiteral(), literal.getLang())
                        : values.createLiteral(literal.getLiteral(), values.createIRI(literal.getDatatype().getIRI().toString()));
            } else {
                return false;
            }
            model.add(subject, values.createIRI(annotation.getProperty().getIRI().toString()), value);
        }
        return true;
    }

    private static Model render(OWLOntologyManager manager, Set<OWLAxiom> axioms, Set<String> existingEntities)
            throws Exception {
        if (axioms.isEmpty()) {
            return new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        }
        OWLOntology part = manager.createOntology(axioms);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            manager.saveOntology(part, new RDFXMLDocumentFormat(), out);
            Model parsed = Rio.parse(new ByteArrayInputStream(out.toByteArray()), "", RDFFormat.RDFXML);
            Model kept = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
            for (Statement statement : parsed) {
                if (!isHeader(statement) && !isDeclarationOfExisting(statement, existingEntities)) {
                    kept.add(statement);
                }
            }
            return kept;
        } finally {
            manager.removeOntology(part);
        }
    }

    private static boolean isHeader(Statement statement) {
        return RDF_TYPE.equals(statement.getPredicate().stringValue())
                && OWLRDFVocabulary.OWL_ONTOLOGY.getIRI().toString().equals(statement.getObject().stringValue());
    }

    private static boolean isDeclarationOfExisting(Statement statement, Set<String> existingEntities) {
        return RDF_TYPE.equals(statement.getPredicate().stringValue())
                && DECLARED_TYPES.contains(statement.getObject().stringValue())
                && statement.getSubject().isIRI()
                && existingEntities.contains(statement.getSubject().stringValue());
    }
}
