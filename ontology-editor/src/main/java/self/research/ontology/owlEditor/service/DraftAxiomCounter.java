package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import self.research.ontology.owlEditor.util.OWLFormatConverter;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
final class DraftAxiomCounter {

    private final SparqlDatasetService datasetService;

    DraftAxiomCounter(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    static OntologyMetrics.OwlApiAxiomCounts countsOf(OWLOntology ont) {
        return new OntologyMetrics.OwlApiAxiomCounts(ont.getAxiomCount(Imports.INCLUDED),
                ont.getLogicalAxiomCount(Imports.INCLUDED), ont.getAxiomCount(AxiomType.DECLARATION, Imports.INCLUDED));
    }

    OntologyMetrics.OwlApiAxiomCounts count(String projectId, String userId) {
        long started = System.nanoTime();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("axioms-draft-" + projectId + "-", ".ttl");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                datasetService.exportDatasetToStream(projectId, userId, RDFFormat.TURTLE, out);
            }
            OWLOntology ont;
            try (InputStream in = Files.newInputStream(tempFile)) {
                ont = OWLFormatConverter.createManagerWithSilentImports().loadOntologyFromOntologyDocument(in);
            }
            OntologyMetrics.OwlApiAxiomCounts counts = countsOf(ont);
            log.info("[PERF] Draft OWLAPI axiom counts for project {} computed in {}ms", projectId,
                    (System.nanoTime() - started) / 1_000_000);
            return counts;
        } catch (Exception e) {
            log.error("Error computing draft OWLAPI axiom counts for project {}", projectId, e);
            return null;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
