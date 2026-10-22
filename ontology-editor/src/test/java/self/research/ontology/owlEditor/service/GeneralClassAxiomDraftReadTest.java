package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneralClassAxiomDraftReadTest {

    private static final String PUBLIC = "<?xml version=\"1.0\"?><rdf:RDF "
            + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:owl=\"http://www.w3.org/2002/07/owl#\">"
            + "<owl:Ontology rdf:about=\"http://ex.org/o\"/><owl:Class rdf:about=\"http://ex.org/A\"/></rdf:RDF>";
    private static final String DRAFT_WITH_RULE = "<?xml version=\"1.0\"?><rdf:RDF "
            + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
            + "xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\" xmlns:owl=\"http://www.w3.org/2002/07/owl#\">"
            + "<owl:Ontology rdf:about=\"http://ex.org/o\"/><owl:Class rdf:about=\"http://ex.org/A\"/>"
            + "<owl:Class rdf:about=\"http://ex.org/Cheese\"/><owl:ObjectProperty rdf:about=\"http://ex.org/hasTopping\"/>"
            + "<owl:Restriction><owl:onProperty rdf:resource=\"http://ex.org/hasTopping\"/>"
            + "<owl:someValuesFrom rdf:resource=\"http://ex.org/Cheese\"/>"
            + "<rdfs:subClassOf rdf:resource=\"http://ex.org/A\"/></owl:Restriction></rdf:RDF>";

    private final SparqlDatasetService datasetService = mock(SparqlDatasetService.class);
    private final StorageManager storageManager = mock(StorageManager.class);
    private final ManchesterExpressionService service =
            new ManchesterExpressionService(storageManager, datasetService, mock(OntologyMutationService.class));

    @TempDir
    Path dir;

    @AfterEach
    void clearContext() {
        SparqlQueryContext.clear();
    }

    private static long rules(OWLOntology ontology) {
        return ontology.getAxioms(AxiomType.SUBCLASS_OF).stream().filter(ax -> ax.getSubClass().isAnonymous()).count();
    }

    @Test
    void inDraftModeTheRulesComeFromTheDraft() throws Exception {
        SparqlQueryContext.setUserId("u1");
        SparqlQueryContext.setWantsDraft(true);
        when(datasetService.hasActiveDraftSession("p1", "u1")).thenReturn(true);
        when(datasetService.getDraftGraphUri("p1", "u1")).thenReturn("urn:graph:draft");
        when(datasetService.exportNamedGraph("p1", "urn:graph:draft", RDFFormat.RDFXML)).thenReturn(DRAFT_WITH_RULE);

        assertEquals(1, rules(service.loadOntology("p1")));
        verify(storageManager, never()).exportOntology(anyString(), anyString());
    }

    @Test
    void inPublicModeTheDraftIsNotRead() throws Exception {
        Path exported = dir.resolve("public.owl");
        Files.writeString(exported, PUBLIC);
        SparqlQueryContext.setUserId("u1");
        when(datasetService.hasActiveDraftSession("p1", "u1")).thenReturn(false);
        when(storageManager.exportOntology("p1", "rdfxml")).thenReturn(exported);

        assertEquals(0, rules(service.loadOntology("p1")));
        verify(datasetService, never()).exportNamedGraph(anyString(), anyString(), any());
    }
}
