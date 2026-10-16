package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ReasonerControllerQueueFullTest {

    private static final String QUEUE_FULL = "Too many reasoning jobs are queued for this project. Please wait and try again.";

    private ReasonerController controller;
    private ReasonerWorkerClient workerClient;
    private ReasonerService reasonerService;
    private SparqlDatasetService datasetService;

    @BeforeEach
    void setUp() {
        controller = new ReasonerController();
        workerClient = mock(ReasonerWorkerClient.class);
        reasonerService = mock(ReasonerService.class);
        datasetService = mock(SparqlDatasetService.class);
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", workerClient);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        ReflectionTestUtils.setField(controller, "reasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", mock(EditorReasonerCacheService.class));
        ReflectionTestUtils.setField(controller, "datasetService", datasetService);
        when(workerClient.submitJob(anyString(), eq("proj-1"), isNull(), isNull(), anyString(), isNull()))
                .thenReturn(Map.of("success", false, "rateLimited", true, "error", QUEUE_FULL));
    }

    @Test
    void classHierarchyAnswersTooManyRequestsInsteadOfReasoningLocally() {
        ResponseEntity<Map<String, Object>> response = controller.getInferredClassHierarchy(
                "proj-1", "HERMIT", null, false, new MockHttpServletRequest());

        assertEquals(429, response.getStatusCode().value());
        assertEquals(QUEUE_FULL, response.getBody().get("error"));
        assertEquals(false, response.getBody().get("success"));
        verifyNoInteractions(reasonerService);
        verify(datasetService, never()).getDatasetSizeOrUnknown(any());
    }

    @Test
    void objectPropertyHierarchyAnswersTooManyRequests() {
        ResponseEntity<Map<String, Object>> response = controller.getInferredObjectPropertyHierarchy(
                "proj-1", "HERMIT", null, false, new MockHttpServletRequest());

        assertEquals(429, response.getStatusCode().value());
        verifyNoInteractions(reasonerService);
    }

    @Test
    void dataPropertyHierarchyAnswersTooManyRequests() {
        ResponseEntity<Map<String, Object>> response = controller.getInferredDataPropertyHierarchy(
                "proj-1", "HERMIT", null, false, new MockHttpServletRequest());

        assertEquals(429, response.getStatusCode().value());
        verifyNoInteractions(reasonerService);
    }
}
