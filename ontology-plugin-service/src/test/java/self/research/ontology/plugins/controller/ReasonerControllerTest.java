package self.research.ontology.plugins.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.plugins.service.ReasonerService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        ReflectionTestUtils.setField(controller, "classifyTimeoutMs", 600_000L);
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

    private static final String DRAFT_EXPORT_URL_PART = "/internal/reasoning/proj-1/export.ttl";
    private static final byte[] TINY_ONTOLOGY = "<urn:ex/A> <urn:ex/type> <urn:ex/Class> .".getBytes(StandardCharsets.UTF_8);

    @SuppressWarnings("unchecked")
    private void cachePublishedOntology(String projectId) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        ((Map<String, OWLOntology>) ReflectionTestUtils.getField(controller, "ontologyCache")).put(projectId, ontology);
    }

    private Map<String, Object> awaitClassifyTask(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> body = controller.classifyStatus("proj-1", taskId).getBody();
        while ("RUNNING".equals(body.get("status")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            body = controller.classifyStatus("proj-1", taskId).getBody();
        }
        return body;
    }

    private Object invokeDraftLoad(String userId) {
        return ReflectionTestUtils.invokeMethod(controller, "tryLoadOntologyForId", "proj-1", userId);
    }

    @Test
    void classifyTaskFailsWithAMessageEvenWhenTheExceptionHasNone() throws Exception {
        cachePublishedOntology("proj-1");
        doThrow(new NullPointerException()).when(reasonerService).classify(any(), any(), any());

        ResponseEntity<Map<String, Object>> accepted = controller.classify("proj-1", Map.of("reasonerType", "HERMIT"));
        Map<String, Object> status = awaitClassifyTask((String) accepted.getBody().get("taskId"));

        assertEquals("FAILED", status.get("status"));
        assertEquals("NullPointerException", status.get("error"));
    }

    @Test
    void classifyTaskCompletesWhenSomeResultSectionsAreMissing() throws Exception {
        cachePublishedOntology("proj-1");
        when(reasonerService.getClassificationResults(any(), any()))
                .thenReturn(Map.of("classHierarchy", List.of(), "totalClasses", 0));

        ResponseEntity<Map<String, Object>> accepted = controller.classify("proj-1", Map.of("reasonerType", "HERMIT"));
        Map<String, Object> status = awaitClassifyTask((String) accepted.getBody().get("taskId"));

        assertEquals("COMPLETED", status.get("status"));
        assertEquals(List.of(), status.get("classHierarchy"));
    }

    @Test
    void notFoundErrorKeepsTheInternalLoadFailureOutOfTheUserMessage() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException(
                        "I/O error on GET request for http://owl-editor:8083/internal/reasoning/proj-1/export.ttl: Connection refused"));

        ResponseEntity<Map<String, Object>> response = controller.checkConsistency("proj-1",
                Map.of("reasonerType", "HERMIT", "userId", "user-1"));

        assertEquals(404, response.getStatusCode().value());
        assertFalse(String.valueOf(response.getBody().get("error")).contains("owl-editor"));
        assertTrue(String.valueOf(response.getBody().get("technicalDetail")).contains("Connection refused"));
    }

    @Test
    void everyDraftRequestFetchesTheCurrentDraft() {
        when(restTemplate.exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenAnswer(invocation -> ResponseEntity.ok(TINY_ONTOLOGY));
        when(reasonerService.isConsistent(any(), any())).thenReturn(true);

        controller.checkConsistency("proj-1", Map.of("reasonerType", "HERMIT", "userId", "user-1"));
        controller.checkConsistency("proj-1", Map.of("reasonerType", "HERMIT", "userId", "user-1"));

        verify(restTemplate, times(2)).exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class));
    }

    @Test
    void concurrentDraftRequestsShareOneInFlightLoad() throws Exception {
        CountDownLatch exportStarted = new CountDownLatch(1);
        CountDownLatch releaseExport = new CountDownLatch(1);
        when(restTemplate.exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenAnswer(invocation -> {
                    exportStarted.countDown();
                    releaseExport.await(10, TimeUnit.SECONDS);
                    return ResponseEntity.ok(TINY_ONTOLOGY);
                });

        AtomicReference<Object> first = new AtomicReference<>();
        AtomicReference<Object> second = new AtomicReference<>();
        Thread loader = new Thread(() -> first.set(invokeDraftLoad("user-1")));
        loader.start();
        assertTrue(exportStarted.await(10, TimeUnit.SECONDS));
        Thread joiner = new Thread(() -> second.set(invokeDraftLoad("user-1")));
        joiner.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (joiner.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        releaseExport.countDown();
        loader.join(10_000);
        joiner.join(10_000);

        assertTrue(first.get() instanceof OWLOntology);
        assertSame(first.get(), second.get());
        verify(restTemplate, times(1)).exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class));
    }

    @Test
    void releasingAnEvictedDraftAlsoDropsItsManagerReference() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenAnswer(invocation -> ResponseEntity.ok(TINY_ONTOLOGY));
        ReflectionTestUtils.setField(controller, "maxDraftOntologies", 1);

        OWLOntology inUse = (OWLOntology) invokeDraftLoad("user-1");
        OWLOntology other = (OWLOntology) invokeDraftLoad("user-2");
        ReflectionTestUtils.invokeMethod(controller, "endDraftUse", other);
        ReflectionTestUtils.invokeMethod(controller, "endDraftUse", inUse);

        Map<?, ?> managerRefs = (Map<?, ?>) ReflectionTestUtils.getField(controller, "managerRefs");
        assertFalse(managerRefs.containsValue(inUse.getOWLOntologyManager()));
        assertTrue(managerRefs.containsValue(other.getOWLOntologyManager()));
    }

    private static final String DRAFT_VERSION_URL_PART = "/internal/reasoning/proj-1/draft-version?userId=user-1";

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void draftIsReusedUntilItsDraftVersionChanges() {
        ReflectionTestUtils.setField(controller, "maxDraftOntologies", 10);
        AtomicReference<String> version = new AtomicReference<>("editor-a:3.7");
        when(restTemplate.exchange(contains(DRAFT_VERSION_URL_PART), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenAnswer(invocation -> ResponseEntity.ok((Map) Map.of("version", version.get())));
        when(restTemplate.exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenAnswer(invocation -> ResponseEntity.ok(TINY_ONTOLOGY));

        OWLOntology first = (OWLOntology) invokeDraftLoad("user-1");
        ReflectionTestUtils.invokeMethod(controller, "endDraftUse", first);
        OWLOntology repeat = (OWLOntology) invokeDraftLoad("user-1");
        ReflectionTestUtils.invokeMethod(controller, "endDraftUse", repeat);

        assertSame(first, repeat);
        verify(restTemplate, times(1)).exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class));

        version.set("editor-a:3.8");
        OWLOntology afterWrite = (OWLOntology) invokeDraftLoad("user-1");

        assertFalse(afterWrite == first);
        verify(restTemplate, times(2)).exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void draftVersionCheckSendsTheInternalToken() {
        when(restTemplate.exchange(contains(DRAFT_VERSION_URL_PART), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok((Map) Map.of("version", "editor-a:1.1")));
        when(restTemplate.exchange(contains(DRAFT_EXPORT_URL_PART), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenAnswer(invocation -> ResponseEntity.ok(TINY_ONTOLOGY));

        invokeDraftLoad("user-1");

        org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> entity =
                org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(restTemplate).exchange(contains(DRAFT_VERSION_URL_PART), eq(HttpMethod.GET), entity.capture(), eq(Map.class));
        assertEquals("ontocode-internal", entity.getValue().getHeaders().getFirst("X-Ontocode-Internal-Token"));
    }
}
