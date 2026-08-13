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

@Service
@Slf4j
public class EntityRenameService {

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;
    private final DraftCopyService draftCopyService;

    public EntityRenameService(StorageManager storageManager,
                               SparqlDatasetService datasetService,
                               DraftCopyService draftCopyService) {
        this.storageManager = storageManager;
        this.datasetService = datasetService;
        this.draftCopyService = draftCopyService;
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

        datasetService.markProjectDirty(projectId);

        log.info("Renamed entity {} -> {} in project {}", oldIri, newIri, projectId);
    }

    public void renameEntityDraft(String projectId, String userId, String oldIri, String newIri) throws Exception {
        if (oldIri == null || oldIri.isBlank() || newIri == null || newIri.isBlank()) {
            throw new IllegalArgumentException("oldIri and newIri are required");
        }
        if (oldIri.equals(newIri)) {
            return;
        }
        if (!draftCopyService.isReady(projectId, userId)) {
            throw new IllegalArgumentException(
                    "Private draft is not ready yet. Wait for the graph copy to finish before renaming.");
        }

        String draftGraph = datasetService.getDraftGraphUri(projectId, userId);
        String draftRdf = datasetService.exportNamedGraph(projectId, draftGraph, RDFFormat.RDFXML);
        if (draftRdf == null || draftRdf.isBlank()) {
            throw new IllegalStateException("Draft graph is empty");
        }

        IRI oldEntityIri = IRI.create(oldIri);
        IRI newEntityIri = IRI.create(newIri);

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                new ByteArrayInputStream(draftRdf.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        OWLEntity entity = resolveEntity(ontology, oldEntityIri)
                .orElseThrow(() -> new IllegalArgumentException("Entity not found: " + oldIri));

        if (entityInSignature(ontology, newEntityIri)) {
            throw new IllegalArgumentException("Target IRI already exists in ontology: " + newIri);
        }

        OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(ontology));
        renamer.changeIRI(entity, newEntityIri);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        datasetService.replaceNamedGraphFromRdf(
                projectId, draftGraph, out.toString(java.nio.charset.StandardCharsets.UTF_8), RDFFormat.RDFXML);

        log.info("Draft rename {} -> {} for project {} user {}", oldIri, newIri, projectId, userId);
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
