package self.research.ontology.owlEditor.controller;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.config.SparqlQueryContextInterceptor;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.service.DLQueryQueueManager;
import self.research.ontology.owlEditor.service.DLQueryQueueProcessor;
import self.research.ontology.owlEditor.service.DLQueryService;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DLQueryDraftOwnerTest {

    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private DLQueryService dlQueryService;
    private DLQueryQueueManager queueManager;
    private DLQueryController controller;

    @BeforeEach
    void setUp() throws Exception {
        dlQueryService = mock(DLQueryService.class);
        queueManager = mock(DLQueryQueueManager.class);
        when(dlQueryService.executeQuery(anyString(), anyString(), any(), any())).thenReturn(Map.of("success", true));
        controller = new DLQueryController();
        ReflectionTestUtils.setField(controller, "dlQueryService", dlQueryService);
        ReflectionTestUtils.setField(controller, "dlQueryQueueManager", queueManager);
    }

    @AfterEach
    void clear() {
        SparqlQueryContext.clear();
    }

    private void requestAsUserBForUserADraft() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ontology/p1/dl-query");
        request.addHeader("Authorization", "Bearer " + Jwts.builder().claims(Map.of("userId", "user-b")).signWith(key).compact());
        request.addParameter("draft", "true");
        request.addParameter("userId", "user-a");
        request.addHeader("X-Ontocode-User-Id", "user-a");
        SparqlQueryContextInterceptor interceptor = new SparqlQueryContextInterceptor();
        ReflectionTestUtils.setField(interceptor, "desktopMode", false);
        interceptor.preHandle(request, new MockHttpServletResponse(), null);
    }

    private DLQueryController.DLQueryRequest query() {
        DLQueryController.DLQueryRequest body = new DLQueryController.DLQueryRequest();
        body.setExpression("Location");
        body.setQueryTypes(List.of("instances"));
        return body;
    }

    @Test
    void directDraftQueryNeverReadsAnotherUsersDraft() throws Exception {
        requestAsUserBForUserADraft();

        controller.executeDLQuery("p1", query());

        verify(dlQueryService).executeQuery(eq("p1"), eq("Location"), anyList(), eq("user-b"));
        verify(dlQueryService, never()).executeQuery(anyString(), anyString(), any(), eq("user-a"));
    }

    @Test
    void withTheWorkerEnabledADraftQueryRunsOnTheWorkerForTheTokenUser() throws Exception {
        ReasonerWorkerClient worker = mock(ReasonerWorkerClient.class);
        ReasoningJobRelayService relay = mock(ReasoningJobRelayService.class);
        when(worker.submitJob(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(Map.of("success", true, "jobId", "w1"));
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", worker);
        ReflectionTestUtils.setField(controller, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);

        requestAsUserBForUserADraft();
        controller.executeDLQuery("p1", query());
        verify(worker).submitJob(eq("DL_QUERY"), eq("p1"), eq("Location"), any(), isNull(), eq("user-b"));
        verify(worker, never()).submitJob(anyString(), anyString(), anyString(), any(), any(), eq("user-a"));
        verify(dlQueryService, never()).executeQuery(anyString(), anyString(), any(), any());

        SparqlQueryContext.clear();
        controller.executeDLQuery("p1", query());
        verify(worker).submitJob(eq("DL_QUERY"), eq("p1"), eq("Location"), any(), isNull(), isNull());
    }

    @Test
    void queuedDraftQueryNeverReadsAnotherUsersDraft() {
        ReflectionTestUtils.setField(controller, "dlQueryQueueProcessor", mock(DLQueryQueueProcessor.class));
        DLQueryJob job = DLQueryJob.builder().jobId("j1").status(DLQueryJob.Status.QUEUED).build();
        when(queueManager.enqueue(anyString(), anyString(), any(), any(), any())).thenReturn(job);
        requestAsUserBForUserADraft();

        controller.executeDLQuery("p1", query());

        verify(queueManager).enqueue(eq("p1"), eq("Location"), anyList(), any(), eq("user-b"));
        verify(queueManager, never()).enqueue(anyString(), anyString(), any(), any(), eq("user-a"));
    }
}
