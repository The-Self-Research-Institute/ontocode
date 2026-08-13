package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OWLEntityRenamer;
import org.springframework.stereotype.Service;

import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.model.ImportOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * entity IRI rename via OWLAPI {@link OWLEntityRenamer}.
 */
@Service
@Slf4j
public class EntityRenameService {

    private final StorageManager storageManager;
    private final OntologyMutationService ontologyMutationService;
    private final DraftCopyService draftCopyService;


private final ProjectOntologyCache ontologyCache;
private final ProjectMetadataService metadataService;
private final HierarchyIndexService hierarchyIndexService;
private final SparqlDatasetService datasetService;
public EntityRenameService(StorageManager storageManager,
                           OntologyMutationService ontologyMutationService,
                           DraftCopyService draftCopyService,
                           ProjectOntologyCache ontologyCache,
                           ProjectMetadataService metadataService,
                           HierarchyIndexService hierarchyIndexService,
                           SparqlDatasetService datasetService) {
    this.storageManager = storageManager;
    this.ontologyMutationService = ontologyMutationService;
    this.draftCopyService = draftCopyService;
    this.ontologyCache = ontologyCache;
    this.metadataService = metadataService;
    this.hierarchyIndexService = hierarchyIndexService;
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

    // Avoid exporting the full ontology (heavy and can fail on some backends).
    // Instead, verify existence with lightweight SPARQL ASK queries and perform
    // the triple-level DELETE/INSERT rename via SPARQL updates.
    String askOld = "ASK { { ?s ?p <" + oldIri + "> } UNION { <" + oldIri + "> ?p ?o } UNION { ?s <" + oldIri + "> ?o } }";
    boolean oldExists = datasetService.execAsk(projectId, askOld);
    if (!oldExists) {
        throw new IllegalArgumentException("Entity not found: " + oldIri);
    }

    String askNew = "ASK { { ?s ?p <" + newIri + "> } UNION { <" + newIri + "> ?p ?o } UNION { ?s <" + newIri + "> ?o } }";
    boolean newExists = datasetService.execAsk(projectId, askNew);
    if (newExists) {
        throw new IllegalArgumentException("Target IRI already exists in ontology: " + newIri);
    }

    String sparql =
        "DELETE { ?s ?p <" + oldIri + "> } INSERT { ?s ?p <" + newIri + "> } WHERE { ?s ?p <" + oldIri + "> } ;\n" +
        "DELETE { <" + oldIri + "> ?p ?o } INSERT { <" + newIri + "> ?p ?o } WHERE { <" + oldIri + "> ?p ?o } ;\n" +
        "DELETE { ?s <" + oldIri + "> ?o } INSERT { ?s <" + newIri + "> ?o } WHERE { ?s <" + oldIri + "> ?o }";

    ontologyMutationService.applyRawUpdate(projectId, sparql, false, null);


    long version = metadataService.incrementMutationVersion(projectId);

    ontologyCache.evict(projectId);

    ontologyCache.updateCachedVersion(projectId, version);

    hierarchyIndexService.scheduleBuild(projectId);

    log.info("Renamed entity {} -> {} in project {}", oldIri, newIri, projectId);
}
    /**
     * Draft-mode rename on the user's full copy-on-switch draft graph.
     */
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

        // OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(ontology));
        // renamer.changeIRI(entity, newEntityIri);

        // ByteArrayOutputStream out = new ByteArrayOutputStream();
        // manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        // datasetService.replaceNamedGraphFromRdf(
        //         projectId, draftGraph, out.toString(java.nio.charset.StandardCharsets.UTF_8), RDFFormat.RDFXML);

        // log.info("Draft rename {} -> {} for project {} user {}", oldIri, newIri, projectId, userId);
    OWLEntityRenamer renamer = new OWLEntityRenamer(manager, Collections.singleton(ontology));
List<OWLOntologyChange> changes = renamer.changeIRI(entity, newEntityIri);
manager.applyChanges(changes);

ByteArrayOutputStream out = new ByteArrayOutputStream();
manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
datasetService.replaceNamedGraphFromRdf(
        projectId, draftGraph, out.toString(java.nio.charset.StandardCharsets.UTF_8), RDFFormat.RDFXML);

hierarchyIndexService.markStale(projectId);

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
