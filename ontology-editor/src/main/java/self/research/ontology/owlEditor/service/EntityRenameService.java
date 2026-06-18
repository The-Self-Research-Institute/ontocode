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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protégé-style entity IRI rename via OWLAPI {@link OWLEntityRenamer}.
 */
@Service
@Slf4j
public class EntityRenameService {

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;

    public EntityRenameService(StorageManager storageManager, SparqlDatasetService datasetService) {
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

    /**
     * Draft-mode rename: apply OWLAPI rename and write resulting axioms to the user's draft graph.
     * The old entity is marked deleted in the draft overlay so reads hide it until publish.
     */
    public void renameEntityDraft(String projectId, String userId, String oldIri, String newIri) throws Exception {
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

        OWLEntity renamedEntity = resolveEntity(ontology, newEntityIri)
                .orElseThrow(() -> new IllegalStateException("Renamed entity not found: " + newIri));

        Set<OWLAxiom> renamedAxioms = ontology.getAxioms().stream()
                .filter(ax -> ax.containsEntityInSignature(renamedEntity)
                        || ax.containsEntityInSignature(entity))
                .collect(Collectors.toSet());

        if (!renamedAxioms.isEmpty()) {
            String sparql = axiomsToInsertDataSparql(renamedAxioms);
            datasetService.execDraftUpdate(projectId, userId, sparql);
        }
        datasetService.markDraftEntityDeleted(projectId, userId, oldIri);

        log.info("Draft rename {} -> {} for project {} user {}", oldIri, newIri, projectId, userId);
    }

    private String axiomsToInsertDataSparql(Set<OWLAxiom> axioms) throws Exception {
        OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();
        OWLOntology tempOntology = tempManager.createOntology(
                IRI.create("urn:ontocode:rename-draft:" + java.util.UUID.randomUUID()));
        tempManager.addAxioms(tempOntology, axioms);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tempManager.saveOntology(tempOntology, new RDFXMLDocumentFormat(), out);

        org.eclipse.rdf4j.model.Model model = org.eclipse.rdf4j.rio.Rio.parse(
                new ByteArrayInputStream(out.toByteArray()),
                "",
                org.eclipse.rdf4j.rio.RDFFormat.RDFXML);

        StringBuilder sparql = new StringBuilder("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
                INSERT DATA {
                """);

        for (org.eclipse.rdf4j.model.Statement st : model) {
            org.eclipse.rdf4j.model.Resource subj = st.getSubject();
            if (subj.isIRI() && subj.stringValue().startsWith("urn:ontocode:rename-draft:")) {
                continue;
            }
            sparql.append("  ")
                    .append(toSparqlTerm(st.getSubject()))
                    .append(" ")
                    .append(toSparqlTerm(st.getPredicate()))
                    .append(" ")
                    .append(toSparqlTerm(st.getObject()))
                    .append(" .\n");
        }
        sparql.append("}");
        return sparql.toString();
    }

    private String toSparqlTerm(org.eclipse.rdf4j.model.Value value) {
        if (value.isIRI()) {
            return "<" + value.stringValue() + ">";
        }
        if (value.isLiteral()) {
            org.eclipse.rdf4j.model.Literal lit = (org.eclipse.rdf4j.model.Literal) value;
            if (lit.getLanguage().isPresent()) {
                return "\"" + escapeLiteral(lit.getLabel()) + "\"@" + lit.getLanguage().get();
            }
            if (lit.getDatatype() != null) {
                return "\"" + escapeLiteral(lit.getLabel()) + "\"^^<" + lit.getDatatype().stringValue() + ">";
            }
            return "\"" + escapeLiteral(lit.getLabel()) + "\"";
        }
        return value.stringValue();
    }

    private String escapeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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
