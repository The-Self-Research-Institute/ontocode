package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftAxiomCountTest {

    private static final String PREFIX = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
        + "@prefix : <http://example.org/o#> .\n";

    private SparqlDatasetService dataset;
    private ManchesterExpressionService manchester;
    private OntologyMetrics metrics;
    private final AtomicLong draftVersion = new AtomicLong();
    private String draftTurtle = PREFIX + ":A a owl:Class .\n:B a owl:Class .\n";

    @BeforeEach
    void setUp() throws Exception {
        dataset = mock(SparqlDatasetService.class);
        manchester = mock(ManchesterExpressionService.class);
        ProjectMetadataService projectMetadata = mock(ProjectMetadataService.class);
        when(projectMetadata.versionStamp("p1")).thenReturn("v1");
        when(dataset.hasActiveDraftOverlay("p1", "kay")).thenReturn(true);
        doAnswer(inv -> {
            write(inv.getArgument(3), draftTurtle);
            return null;
        }).when(dataset).exportDatasetToStream(eq("p1"), eq("kay"), any(), any());
        metrics = new OntologyMetrics(dataset, projectMetadata, null, manchester, null, p -> null,
            (project, user) -> draftVersion.get());
    }

    @AfterEach
    void clear() {
        SparqlQueryContext.clear();
    }

    private static void write(OutputStream out, String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aDrafterGetsAxiomCountsFromTheirDraft() throws Exception {
        SparqlQueryContext.setUserId("kay");

        assertEquals(2, metrics.getOwlApiAxiomCounts("p1").declarations());
        verify(manchester, never()).loadFreshOntology(any());
    }

    @Test
    void aNewDraftClassIsCountedAfterTheDraftChanges() {
        SparqlQueryContext.setUserId("kay");
        assertEquals(2, metrics.getOwlApiAxiomCounts("p1").total());

        draftTurtle = PREFIX + ":A a owl:Class .\n:B a owl:Class .\n:C a owl:Class .\n";
        draftVersion.incrementAndGet();

        assertEquals(3, metrics.getOwlApiAxiomCounts("p1").total());
    }

    @Test
    void anUnchangedDraftReusesTheCount() {
        SparqlQueryContext.setUserId("kay");

        metrics.getOwlApiAxiomCounts("p1");
        metrics.getOwlApiAxiomCounts("p1");

        verify(dataset, times(1)).exportDatasetToStream(eq("p1"), eq("kay"), any(), any());
    }

    @Test
    void aUserWithoutADraftStillGetsThePublicCount() throws Exception {
        SparqlQueryContext.setUserId("other");
        when(manchester.loadFreshOntology("p1")).thenReturn(
            org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager().createOntology());

        assertEquals(0, metrics.getOwlApiAxiomCounts("p1").total());
        verify(manchester).loadFreshOntology("p1");
        verify(dataset, never()).exportDatasetToStream(any(), eq("other"), any(), any());
    }
}
