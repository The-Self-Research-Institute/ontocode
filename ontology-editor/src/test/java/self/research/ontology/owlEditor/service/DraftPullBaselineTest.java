package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftPullBaselineTest {

    private static final String MAIN_GRAPH = "urn:graph:main";
    private static final String DRAFT_GRAPH = "urn:graph:draft";
    private static final String CLASS_A = "http://ex.org/A";
    private static final String CLASS_B = "http://ex.org/B";

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private DraftSessionRepository sessionRepository;
    @Mock
    private MainGraphRevisionService revisionService;
    @Mock
    private DraftBaselineStore baselineStore;
    @Mock
    private ProjectImportService importService;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private OntologyHistoryService historyService;

    @TempDir
    Path projectDir;

    private DraftPublishMergeService service;
    private DraftSession session;

    private static String rdf(String... classes) {
        StringBuilder body = new StringBuilder();
        for (String c : classes) {
            body.append("<owl:Class rdf:about=\"").append(c).append("\"/>");
        }
        return "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:owl=\"http://www.w3.org/2002/07/owl#\"><owl:Ontology rdf:about=\"http://ex.org/o\"/>"
                + body + "</rdf:RDF>";
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        OntologyMergeService mergeService = new OntologyMergeService(datasetService, importService, storageManager,
                metadataService, historyService);
        service = new DraftPublishMergeService(datasetService, storageManager, mergeService, sessionRepository,
                revisionService);
        ReflectionTestUtils.setField(service, "baselineStore", baselineStore);

        when(storageManager.projectDir("p1")).thenReturn(projectDir);
        when(datasetService.getGraphUri("p1")).thenReturn(MAIN_GRAPH);
        when(datasetService.getDraftGraphUri("p1", "u1")).thenReturn(DRAFT_GRAPH);
        when(baselineStore.load("p1", "u1")).thenReturn(Optional.empty());

        session = new DraftSession("p1", "u1", 1, 1);
        session.setBaselineSnapshotPath("baselines/u1.owl");
        when(sessionRepository.findByProjectIdAndUserId("p1", "u1")).thenReturn(Optional.of(session));
    }

    @Test
    void capturingABaselineAlsoKeepsADurableCopy() throws Exception {
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML)).thenReturn(rdf(CLASS_A));

        String relative = service.captureBaselineSnapshot("p1", "u1");

        assertTrue(Files.exists(projectDir.resolve(relative)));
        verify(baselineStore).save("p1", "u1", rdf(CLASS_A));
    }

    @Test
    void aFailingDurableCopyDoesNotStopTheDraftFromStarting() throws Exception {
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML)).thenReturn(rdf(CLASS_A));
        doThrow(new RuntimeException("mongo down")).when(baselineStore).save(anyString(), anyString(), anyString());

        String relative = service.captureBaselineSnapshot("p1", "u1");

        assertTrue(Files.exists(projectDir.resolve(relative)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRedeployDoesNotHideAClassAddedToPublicSinceTheDraftBegan() throws Exception {
        when(baselineStore.load("p1", "u1")).thenReturn(Optional.of(rdf(CLASS_A)));
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML)).thenReturn(rdf(CLASS_A, CLASS_B));
        when(datasetService.exportNamedGraph("p1", DRAFT_GRAPH, RDFFormat.RDFXML)).thenReturn(rdf(CLASS_A));

        Map<String, Object> result = service.analyzePull("p1", "u1");

        assertEquals(true, result.get("hasChanges"));
        assertFalse(result.containsKey("baselineLost"));
        List<Map<String, Object>> safe = (List<Map<String, Object>>) result.get("safeChanges");
        assertEquals(List.of(CLASS_B), safe.stream().map(row -> row.get("entityIri")).toList());
        assertTrue(Files.exists(projectDir.resolve("baselines/u1.owl")));
    }

    @Test
    void whenNoBaselineExistsAnywherePullSaysSoInsteadOfClaimingNothingChanged() throws Exception {
        Map<String, Object> result = service.analyzePull("p1", "u1");

        assertEquals(true, result.get("baselineLost"));
        assertEquals(false, result.get("hasChanges"));
        assertTrue(((String) result.get("message")).contains("starting snapshot"));
        verify(sessionRepository, never()).save(any(DraftSession.class));
        verify(datasetService, never()).exportNamedGraph(anyString(), anyString(), any(RDFFormat.class));
    }

    @Test
    void applyingAPullWithoutABaselineIsRefusedAndChangesNothing() throws Exception {
        Map<String, Object> result = service.applyPull("p1", "u1", Map.of());

        assertEquals(false, result.get("success"));
        assertEquals(true, result.get("baselineLost"));
        verify(datasetService, never()).replaceNamedGraphFromRdf(anyString(), anyString(), anyString(), any());
        verify(sessionRepository, never()).save(any(DraftSession.class));
    }

    @Test
    void aDraftThatNeverHadABaselineStillGetsOneEstablished() throws Exception {
        session.setBaselineSnapshotPath(null);
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML)).thenReturn(rdf(CLASS_A));

        Map<String, Object> result = service.analyzePull("p1", "u1");

        assertEquals(false, result.get("hasChanges"));
        assertFalse(result.containsKey("baselineLost"));
        verify(sessionRepository).save(session);
        verify(baselineStore).save(eq("p1"), eq("u1"), anyString());
    }

    @Test
    void deletingABaselineAlsoDeletesTheDurableCopy() {
        service.deleteBaselineSnapshot("p1", "u1");

        verify(baselineStore).delete("p1", "u1");
    }
}
