package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OWLEntityRenamer;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ImportOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

/**
 * Protégé-style entity IRI rename via OWLAPI {@link OWLEntityRenamer}.
 */
@Service
@Slf4j
public class EntityRenameService {

    private final StorageManager storageManager;
    private final GraphDBDatasetService datasetService;

    public EntityRenameService(StorageManager storageManager, GraphDBDatasetService datasetService) {
        this.storageManager = storageManager;
        this.datasetService = datasetService;
    }

    public void renameEntity(String projectId, String oldIri, String newIri) throws Exception {
        if (oldIri == null || oldIri.isBlank() || newIri == null || newIri.isBlank()) {
            throw new IllegalArgumentException("oldIri and newIri are required");
        }
        if (oldIri.equals(newIri)) {
            return;
        }

        IRI oldEntityIri = IRI.create(oldIri);
        IRI newEntityIri = IRI.create(newIri);

        Path exportPath = storageManager.exportOntology(projectId, "rdfxml");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(exportPath.toFile());

        OWLEntity entity = resolveEntity(ontology, oldEntityIri)
                .orElseThrow(() -> new IllegalArgumentException("Entity not found: " + oldIri));

        if (entityInSignature(ontology, newEntityIri)) {
            throw new IllegalArgumentException("Target IRI already exists in ontology: " + newIri);
        }

        OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(ontology));
        renamer.changeIRI(entity, newEntityIri);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);

        datasetService.bulkLoadChunked(
                projectId,
                new ByteArrayInputStream(out.toByteArray()),
                RDFFormat.RDFXML,
                out.size(),
                ImportOptions.builder().mode(ImportOptions.ImportMode.FULL).build());

        log.info("Renamed entity {} -> {} in project {}", oldIri, newIri, projectId);
    }

    private Optional<OWLEntity> resolveEntity(OWLOntology ontology, IRI iri) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        if (!ontology.containsClassInSignature(iri)) {
            if (ontology.containsObjectPropertyInSignature(iri)) {
                return Optional.of(df.getOWLObjectProperty(iri));
            }
            if (ontology.containsDataPropertyInSignature(iri)) {
                return Optional.of(df.getOWLDataProperty(iri));
            }
            if (ontology.containsAnnotationPropertyInSignature(iri)) {
                return Optional.of(df.getOWLAnnotationProperty(iri));
            }
            if (ontology.containsIndividualInSignature(iri)) {
                return Optional.of(df.getOWLNamedIndividual(iri));
            }
            if (ontology.containsDatatypeInSignature(iri)) {
                return Optional.of(df.getOWLDatatype(iri));
            }
            return Optional.empty();
        }
        return Optional.of(df.getOWLClass(iri));
    }

    private boolean entityInSignature(OWLOntology ontology, IRI iri) {
        return ontology.containsClassInSignature(iri)
                || ontology.containsObjectPropertyInSignature(iri)
                || ontology.containsDataPropertyInSignature(iri)
                || ontology.containsAnnotationPropertyInSignature(iri)
                || ontology.containsIndividualInSignature(iri)
                || ontology.containsDatatypeInSignature(iri);
    }
}
