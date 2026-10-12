package self.research.ontology.plugins.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.plugins.service.ReasonerService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasonerControllerTest {

    @Mock
    private ReasonerService reasonerService;

    @Mock
    private RestTemplate restTemplate;

    private ReasonerController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ReasonerController();
        ReflectionTestUtils.setField(controller, "reasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(controller, "editorServiceUrl", "http://owl-editor:8083");
        ReflectionTestUtils.setField(controller, "internalToken", "ontocode-internal");
    }

    private void stubWhatIfExport() {
        byte[] body = "<urn:ex/A> <urn:ex/type> <urn:ex/Class> .".getBytes(StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                eq("http://owl-editor:8083/internal/reasoning/proj-1/whatif/assistant-whatif-g1/export.nt"),
                eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Test
    void whatIfKeyForcesTheSyncPathAndCallsTheWhatIfExportUrl() {
        stubWhatIfExport();
        when(reasonerService.isConsistent(any(), any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.checkConsistency("proj-1",
                Map.of("reasonerType", "HERMIT", "whatIfKey", "assistant-whatif-g1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("consistent"));
        verify(restTemplate).exchange(
                eq("http://owl-editor:8083/internal/reasoning/proj-1/whatif/assistant-whatif-g1/export.nt"),
                eq(HttpMethod.GET), any(), eq(byte[].class));
    }

    @Test
    void whatIfKeyNeverPopulatesTheSharedOntologyCacheOrManagerRefs() {
        stubWhatIfExport();
        when(reasonerService.isConsistent(any(), any())).thenReturn(true);

        controller.checkConsistency("proj-1", Map.of("reasonerType", "HERMIT", "whatIfKey", "assistant-whatif-g1"));

        Map<?, ?> ontologyCache = (Map<?, ?>) ReflectionTestUtils.getField(controller, "ontologyCache");
        Map<?, ?> managerRefs = (Map<?, ?>) ReflectionTestUtils.getField(controller, "managerRefs");
        assertTrue(ontologyCache.isEmpty());
        assertTrue(managerRefs.isEmpty());
    }

    @Test
    void whatIfKeyAlwaysDisposesTheReasonerAfterwards() {
        stubWhatIfExport();
        when(reasonerService.isConsistent(any(), any())).thenReturn(false);
        when(reasonerService.getUnsatisfiableClasses(any(), any())).thenReturn(java.util.Set.of());

        controller.checkConsistency("proj-1", Map.of("reasonerType", "HERMIT", "whatIfKey", "assistant-whatif-g1"));

        verify(reasonerService).disposeReasoners(any());
    }

    @Test
    void whatIfKeySendsTheInternalTokenHeader() {
        stubWhatIfExport();
        when(reasonerService.isConsistent(any(), any())).thenReturn(true);

        controller.checkConsistency("proj-1", Map.of("reasonerType", "HERMIT", "whatIfKey", "assistant-whatif-g1"));

        org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.GET), entityCaptor.capture(), eq(byte[].class));
        assertEquals("ontocode-internal", entityCaptor.getValue().getHeaders().getFirst("X-Ontocode-Internal-Token"));
    }

    @Test
    void absentWhatIfKeyNeverCallsTheWhatIfExportUrl() {
        controller.checkConsistency("proj-1", Map.of("reasonerType", "HERMIT"));

        verify(restTemplate, never()).exchange(
                org.mockito.ArgumentMatchers.contains("/whatif/"), any(), any(), eq(byte[].class));
    }
}
