package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyMetadataServiceTest {

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectMetadataService projectMetadataService;
    @Mock
    private OntologyMutationService mutationService;
    @Mock
    private GeneralClassAxiomService generalClassAxiomService;
    @Mock
    private ProjectImportService importService;
    @Mock
    private ManchesterExpressionService manchesterExpressionService;
    @Mock
    private OntologyHistoryService historyService;

    private OntologyMetadataService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMetadataService(datasetService, projectMetadataService, mutationService,
                generalClassAxiomService, importService, manchesterExpressionService);
        ReflectionTestUtils.setField(service, "historyService", historyService);

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> ontologyIriCache =
                (java.util.Map<String, String>) ReflectionTestUtils.getField(service, "ontologyIriCache");
        ontologyIriCache.put("proj-1", "http://ex.org/onto");
    }

    @AfterEach
    void tearDown() {
        SparqlQueryContext.clear();
    }

    @Test
    void getPrefixesMergesDraftOverridesOverThePublicMapWhenDrafting() {
        when(projectMetadataService.readMeta("proj-1"))
                .thenReturn(Optional.of(Map.of("prefixes", Map.of("ex", "http://ex.org/"))));
        SparqlQueryContext.setUserId("u1");
        when(datasetService.hasActiveDraftOverlay("proj-1", "u1")).thenReturn(true);
        when(datasetService.readProjectPrefixes("proj-1", true, "u1"))
                .thenReturn(Map.of("ex", "http://ex.org/draft/", "new", "http://new.org/"));

        List<Map<String, String>> result = service.getPrefixes("proj-1");

        assertEquals("http://ex.org/draft/",
                result.stream().filter(p -> p.get("prefix").equals("ex")).findFirst().orElseThrow().get("namespace"));
        assertTrue(result.stream().anyMatch(p -> p.get("prefix").equals("new")));
    }

    @Test
    void getPrefixesSkipsDraftOverlayWhenNotDrafting() {
        when(projectMetadataService.readMeta("proj-1"))
                .thenReturn(Optional.of(Map.of("prefixes", Map.of("ex", "http://ex.org/"))));

        List<Map<String, String>> result = service.getPrefixes("proj-1");

        assertEquals("http://ex.org/",
                result.stream().filter(p -> p.get("prefix").equals("ex")).findFirst().orElseThrow().get("namespace"));
    }

    @Test
    void updatePrefixInDraftModeRoutesToTheDraftOverridesNotThePublicMetadata() {
        service.updatePrefix("proj-1", "ex", "http://ex.org/", null, true, "u1");

        verify(datasetService).updateDraftPrefix("proj-1", "u1", "ex", "http://ex.org/", null);
        verify(projectMetadataService, never()).writeMeta(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletePrefixInDraftModeRoutesToTheDraftOverridesNotThePublicMetadata() {
        service.deletePrefix("proj-1", "ex", true, "u1");

        verify(datasetService).deleteDraftPrefix("proj-1", "u1", "ex");
        verify(projectMetadataService, never()).readMeta(anyString());
        verify(projectMetadataService, never()).writeMeta(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addOntologyAnnotationThreadsUsernameIntoHistoryAwareUpdate() {
        service.addOntologyAnnotation("proj-1", "http://ex.org/prop", "value", null, null,
                false, "u1", "User One");

        verify(mutationService).applyRawUpdateWithHistory(eq("proj-1"), anyString(), eq(false),
                eq("u1"), eq("User One"));
    }

    @Test
    void addGCIFallbackRecordsGroupedMutationWithUsername() throws Exception {
        doThrow(new RuntimeException("manchester parse failed"))
                .when(generalClassAxiomService)
                .addGeneralClassAxiom(anyString(), anyString(), anyString(), eq(false), anyString(), anyString());

        service.addGCI("proj-1", "http://ex.org/A and http://ex.org/B", "http://ex.org/C",
                false, "u1", "User One");

        ArgumentCaptor<List<OntologyMutationService.MutationOp>> opsCaptor = ArgumentCaptor.forClass(List.class);
        verify(historyService).recordGroupedMutations(eq("proj-1"), eq("u1"), eq("User One"),
                opsCaptor.capture(), eq(false));
        assertEquals(1, opsCaptor.getValue().size());
        assertEquals("addGCAIntersection", opsCaptor.getValue().get(0).type());
    }
}
