package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasonerControllerStatsTest {

    private ReasonerController controller;
    private ReasonerService reasonerService;
    private EditorReasonerCacheService editorCache;
    private SparqlDatasetService datasetService;

    @BeforeEach
    void setUp() {
        controller = new ReasonerController();
        reasonerService = mock(ReasonerService.class);
        editorCache = mock(EditorReasonerCacheService.class);
        datasetService = mock(SparqlDatasetService.class);
        ReflectionTestUtils.setField(controller, "reasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", editorCache);
        ReflectionTestUtils.setField(controller, "datasetService", datasetService);
        ReflectionTestUtils.setField(controller, "maxReasonerTriples", 5_000_000L);
    }

    @Test
    void statsWithAnErrorAreNotReportedAsSuccess() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        when(editorCache.getOntology("proj-1")).thenReturn(Optional.of(ontology));
        Map<String, Object> stats = new HashMap<>();
        stats.put("reasonerType", "HermiT");
        stats.put("error", "Reasoner crashed");
        when(reasonerService.getReasonerStats(ontology, ReasonerType.HERMIT)).thenReturn(stats);

        Map<String, Object> body = controller.getReasonerStats("proj-1", "HERMIT", null, false,
                new MockHttpServletRequest()).getBody();

        assertEquals(false, body.get("success"));
    }

    @Test
    void statsWithoutAnErrorAreASuccess() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        when(editorCache.getOntology("proj-1")).thenReturn(Optional.of(ontology));
        when(reasonerService.getReasonerStats(ontology, ReasonerType.HERMIT)).thenReturn(new HashMap<>());

        Map<String, Object> body = controller.getReasonerStats("proj-1", "HERMIT", null, false,
                new MockHttpServletRequest()).getBody();

        assertEquals(true, body.get("success"));
    }

    @Test
    void unknownOntologySizeIsNotLoadedForReasoning() throws Exception {
        when(editorCache.getOntology("proj-1")).thenReturn(Optional.empty());
        when(datasetService.getDatasetSizeOrUnknown("proj-1")).thenReturn(-1L);

        Map<String, Object> body = controller.getReasonerStats("proj-1", "HERMIT", null, false,
                new MockHttpServletRequest()).getBody();

        assertEquals(false, body.get("success"));
        verify(datasetService, never()).exportDatasetToStream(anyString(), any(), any());
    }
}
