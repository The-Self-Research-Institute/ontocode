package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read/write OWL 2 axiom annotations (Protégé-style provenance on axioms).
 */
@Service
@Slf4j
public class AxiomAnnotationService {

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;
    private final AxiomLookupService axiomLookupService;

    public AxiomAnnotationService(StorageManager storageManager,
                                  SparqlDatasetService datasetService,
                                  AxiomLookupService axiomLookupService) {
        this.storageManager = storageManager;
        this.datasetService = datasetService;
        this.axiomLookupService = axiomLookupService;
    }

    public List<Map<String, String>> getAnnotations(String projectId, String entityIri,
                                                    String relatedIri, String sectionName) throws Exception {
        OWLOntology ontology = loadOntology(projectId);
        OWLAxiom axiom = axiomLookupService.findFirst(ontology, entityIri, relatedIri, sectionName);
        if (axiom == null) {
            return List.of();
        }
        List<Map<String, String>> annotations = new ArrayList<>();
        for (OWLAnnotation annotation : axiom.annotations().collect(Collectors.toList())) {
            annotations.add(toMap(annotation));
        }
        return annotations;
    }

    public void addAnnotation(String projectId, String entityIri, String relatedIri, String sectionName,
                              String annotationProperty, String value, String language) throws Exception {
        if (annotationProperty == null || annotationProperty.isBlank()) {
            throw new IllegalArgumentException("annotationProperty is required");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }

        OWLOntology ontology = loadOntology(projectId);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLAxiom axiom = axiomLookupService.findFirst(ontology, entityIri, relatedIri, sectionName);
        if (axiom == null) {
            throw new IllegalArgumentException("Could not locate axiom for annotation");
        }

        OWLAnnotation newAnnotation = buildAnnotation(df, annotationProperty, value, language);
        Set<OWLAnnotation> merged = axiom.annotations().collect(Collectors.toCollection(LinkedHashSet::new));
        merged.add(newAnnotation);

        OWLAxiom base = axiom.getAxiomWithoutAnnotations();
        OWLAxiom annotated = base.getAnnotatedAxiom(merged);
        replaceAxiom(projectId, base, annotated);
    }

    public void deleteAnnotation(String projectId, String entityIri, String relatedIri, String sectionName,
                                 String annotationProperty, String value) throws Exception {
        OWLOntology ontology = loadOntology(projectId);
        OWLAxiom axiom = axiomLookupService.findFirst(ontology, entityIri, relatedIri, sectionName);
        if (axiom == null) {
            throw new IllegalArgumentException("Could not locate axiom for annotation removal");
        }

        Set<OWLAnnotation> remaining = new LinkedHashSet<>();
        boolean removed = false;
        for (OWLAnnotation annotation : axiom.annotations().collect(Collectors.toList())) {
            if (!removed && matchesAnnotation(annotation, annotationProperty, value)) {
                removed = true;
                continue;
            }
            remaining.add(annotation);
        }
        if (!removed) {
            throw new IllegalArgumentException("Annotation not found on axiom");
        }

        OWLAxiom base = axiom.getAxiomWithoutAnnotations();
        OWLAxiom updated = remaining.isEmpty() ? base : base.getAnnotatedAxiom(remaining);
        replaceAxiom(projectId, base, updated);
    }

    private void replaceAxiom(String projectId, OWLAxiom previous, OWLAxiom next) throws Exception {
        String delete = OwlAxiomSparqlWriter.toDeleteData(Set.of(previous));
        if (delete.isBlank()) {
            throw new IllegalStateException("Failed to serialize axiom for replacement");
        }
        String insert = OwlAxiomSparqlWriter.toInsertData(Set.of(next));
        if (insert.isBlank()) {
            throw new IllegalStateException("Failed to serialize annotated axiom");
        }
        datasetService.execUpdate(projectId, delete + ";\n" + insert);
        log.info("Updated axiom annotations for project {}", projectId);
    }

    private OWLAnnotation buildAnnotation(OWLDataFactory df, String propertyIri, String value, String language) {
        OWLAnnotationProperty property = df.getOWLAnnotationProperty(IRI.create(propertyIri.trim()));
        OWLAnnotationValue annotationValue;
        if (language != null && !language.isBlank()) {
            annotationValue = df.getOWLLiteral(value, language);
        } else {
            annotationValue = df.getOWLLiteral(value);
        }
        return df.getOWLAnnotation(property, annotationValue);
    }

    private boolean matchesAnnotation(OWLAnnotation annotation, String propertyIri, String value) {
        if (!annotation.getProperty().getIRI().toString().equals(propertyIri)) {
            return false;
        }
        OWLAnnotationValue annotationValue = annotation.getValue();
        if (annotationValue instanceof OWLLiteral literal) {
            return literal.getLiteral().equals(value);
        }
        return annotationValue.toString().equals(value);
    }

    private Map<String, String> toMap(OWLAnnotation annotation) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("property", annotation.getProperty().getIRI().toString());
        OWLAnnotationValue value = annotation.getValue();
        if (value instanceof OWLLiteral literal) {
            map.put("value", literal.getLiteral());
            if (literal.hasLang()) {
                map.put("language", literal.getLang());
            }
        } else {
            map.put("value", value.toString());
        }
        return map;
    }

    private OWLOntology loadOntology(String projectId) throws Exception {
        Path exportPath = storageManager.exportOntology(projectId, "rdfxml");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(exportPath.toFile());
    }
}
