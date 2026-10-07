package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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
