package self.research.ontology.owlEditor.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.config.CallerIdentity;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.model.ExportJob;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline;
import self.research.ontology.owlEditor.service.DLQueryQueueManager;
import self.research.ontology.owlEditor.service.DLQueryService;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.EditorJobResultRetention;
import self.research.ontology.owlEditor.service.ExportScopeResolver;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ImportQueueManager;
import self.research.ontology.owlEditor.service.ImportWorkerDispatcher;
import self.research.ontology.owlEditor.service.JobAccess;
import self.research.ontology.owlEditor.service.JobOwnerRegistry;
import self.research.ontology.owlEditor.service.OntologyExportJobService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyPreparseService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;
import self.research.ontology.owlEditor.service.RuleRunAccess;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobRouteOwnershipTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes());

    private final Map<String, DLQueryJob> retained = new HashMap<>();
    private ProjectAccessService projects;
    private JobOwnerRegistry registry;
    private JobAccess jobAccess;
    private ReasonerWorkerClient worker;
    private ReasoningJobRelayService relay;

    @BeforeEach
    void setUp() {
        JwtIdentityExtractor.requireSignature(KEY);
        projects = mock(ProjectAccessService.class);
        when(projects.hasProjectAccess(anyString(), anyString())).thenReturn(false);
        when(projects.hasProjectAccess("p1", "ann@x.org")).thenReturn(true);
        when(projects.hasProjectAccess("p1", "bob@x.org")).thenReturn(true);
        when(projects.hasProjectAccess("p1", "owner@x.org")).thenReturn(true);
        when(projects.isProjectOwner("p1", "owner@x.org")).thenReturn(true);
        registry = new JobOwnerRegistry();
        jobAccess = new JobAccess(new RuleRunAccess(projects, false), registry, projects);

        EditorJobResultRetention retention = mock(EditorJobResultRetention.class);
        when(retention.get(anyString())).thenAnswer(inv -> Optional.ofNullable(retained.get(inv.getArgument(0))));
        doAnswer(inv -> {
            DLQueryJob job = inv.getArgument(0);
            retained.put(job.getJobId(), job);
            return null;
        }).when(retention).retain(any());
        relay = new ReasoningJobRelayService(mock(SimpMessagingTemplate.class), retention);
        worker = mock(ReasonerWorkerClient.class);
        when(worker.getJob("j1")).thenReturn(Map.of("jobId", "j1", "projectId", "p1", "status", "PROCESSING"));
        when(worker.getJob("nope")).thenReturn(Map.of("success", false, "error", "404 Not Found"));
        when(worker.cancelJob("j1")).thenReturn(Map.of("success", true, "cancelled", true));
        relay.rememberSubmittedJob("j1", "p1", "Person", "DL_QUERY", Map.of("status", "PROCESSING"));
        registry.register("j1", "p1", "u-ann");
    }

    @AfterEach
    void tearDown() {
        JwtIdentityExtractor.requireSignature(null);
    }

    private MockHttpServletRequest as(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Authorization", "Bearer "
                + Jwts.builder().subject(email).claim("email", email).claim("userId", userId).signWith(KEY).compact());
        return request;
    }

    private MockHttpServletRequest anonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        return request;
    }

    private DLQueryJobController dlJobs() {
        DLQueryJobController controller = new DLQueryJobController(mock(DLQueryQueueManager.class));
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", worker);
        ReflectionTestUtils.setField(controller, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        ReflectionTestUtils.setField(controller, "jobAccess", jobAccess);
        return controller;
    }

    private ReasonerController reasoner() {
        ReasonerController controller = new ReasonerController();
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", worker);
        ReflectionTestUtils.setField(controller, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        ReflectionTestUtils.setField(controller, "jobAccess", jobAccess);
        return controller;
    }

    @Test
    void dlQueryJobStatusMatrix() {
        DLQueryJobController controller = dlJobs();

        assertEquals(200, controller.getJob("j1", as("ann@x.org", "u-ann")).getStatusCode().value());
        assertEquals(404, controller.getJob("j1", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.getJob("j1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.getJob("j1", anonymous()).getStatusCode().value());
        assertEquals(404, controller.getJob("nope", as("ann@x.org", "u-ann")).getStatusCode().value());
    }

    @Test
    void projectOwnerMaySeeAMembersJob() {
        assertEquals(200, dlJobs().getJob("j1", as("owner@x.org", "u-owner")).getStatusCode().value());
    }

    @Test
    void jobWithNoRecordedOwnerFallsBackToProjectAccess() {
        when(worker.getJob("old")).thenReturn(Map.of("jobId", "old", "projectId", "p1", "status", "COMPLETED"));
        DLQueryJobController controller = dlJobs();

        assertEquals(200, controller.getJob("old", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.getJob("old", as("eve@x.org", "u-eve")).getStatusCode().value());
    }

    @Test
    void dlQueryCancelMatrix() {
        DLQueryJobController controller = dlJobs();

        assertEquals(404, controller.cancelJob("j1", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.cancelJob("j1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.cancelJob("j1", anonymous()).getStatusCode().value());
        verify(worker, never()).cancelJob("j1");

        assertEquals(200, controller.cancelJob("j1", as("ann@x.org", "u-ann")).getStatusCode().value());
        verify(worker).cancelJob("j1");
    }

    @Test
    void reasonerJobStatusMatrix() {
        ReasonerController controller = reasoner();

        assertEquals(200, controller.getReasonerJob("p1", "j1", as("ann@x.org", "u-ann")).getStatusCode().value());
        assertEquals(404, controller.getReasonerJob("p1", "j1", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.getReasonerJob("p1", "j1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.getReasonerJob("p1", "j1", anonymous()).getStatusCode().value());
        assertEquals(404, controller.getReasonerJob("p1", "nope", as("ann@x.org", "u-ann")).getStatusCode().value());
    }

    @Test
    void reasonerJobRouteWithAnotherProjectIsNotFound() {
        when(projects.hasProjectAccess("p2", "ann@x.org")).thenReturn(true);

        assertEquals(404, reasoner().getReasonerJob("p2", "j1", as("ann@x.org", "u-ann")).getStatusCode().value());
    }

    @Test
    void reasonerJobCancelMatrix() {
        ReasonerController controller = reasoner();

        assertEquals(404, controller.cancelReasonerJob("p1", "j1", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.cancelReasonerJob("p1", "j1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.cancelReasonerJob("p1", "j1", anonymous()).getStatusCode().value());
        verify(worker, never()).cancelJob("j1");

        assertEquals(200, controller.cancelReasonerJob("p1", "j1", as("ann@x.org", "u-ann")).getStatusCode().value());
        verify(worker).cancelJob("j1");
    }

    @Test
    void submittingADlQueryRecordsTheCallerAsOwner() throws Exception {
        when(worker.submitJob(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Map.of("success", true, "jobId", "w9"));
        DLQueryController controller = new DLQueryController();
        ReflectionTestUtils.setField(controller, "dlQueryService", mock(DLQueryService.class));
        ReflectionTestUtils.setField(controller, "dlQueryQueueManager", mock(DLQueryQueueManager.class));
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", worker);
        ReflectionTestUtils.setField(controller, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        ReflectionTestUtils.setField(controller, "jobAccess", jobAccess);
        DLQueryController.DLQueryRequest body = new DLQueryController.DLQueryRequest();
        body.setExpression("Location");
        body.setQueryTypes(List.of("instances"));

        MockHttpServletRequest bobRequest = as("bob@x.org", "u-bob");
        controller.executeDLQuery("p1", body, CallerIdentity.of(bobRequest), bobRequest);

        assertEquals(Optional.of("p1"), registry.find("w9").map(JobOwnerRegistry.Entry::projectId));
        assertTrue(registry.find("w9").orElseThrow().owners().contains("u-bob"));
    }

    private ProjectLoadController loadController(OntologyExportJobService exportJobs) {
        ProjectLoadController controller = new ProjectLoadController(mock(StorageManager.class),
                mock(ProjectMetadataService.class), mock(ProjectImportService.class), mock(GridFSFileService.class),
                mock(ProjectShareService.class), mock(DraftTrackingService.class), mock(DraftCopyService.class),
                mock(OntologyHistoryService.class), mock(DraftChangeRepository.class), mock(SimpMessagingTemplate.class),
                mock(SparqlDatasetService.class), mock(ProjectRepository.class), mock(OntologyPreparseService.class),
                mock(ImportWorkerDispatcher.class), mock(MongoTemplate.class), exportJobs,
                mock(CodeViewReimportPipeline.class), new ProjectWriteLockRegistry(),
                mock(self.research.ontology.owlEditor.service.ProjectAuthorization.class));
        ReflectionTestUtils.setField(controller, "jobAccess", jobAccess);
        ReflectionTestUtils.setField(controller, "exportScopeResolver", new ExportScopeResolver());
        return controller;
    }

    private ExportJob exportJob(String id, ExportJob.Status status) {
        return ExportJob.builder().jobId(id).projectId("p1").format("rdfxml").status(status)
                .createdAt(Instant.now()).build();
    }

    @Test
    void exportJobStatusAndDownloadMatrix() {
        OntologyExportJobService exportJobs = mock(OntologyExportJobService.class);
        when(exportJobs.getStatus("e1")).thenReturn(Optional.of(exportJob("e1", ExportJob.Status.PROCESSING)));
        when(exportJobs.getStatus("missing")).thenReturn(Optional.empty());
        registry.register("e1", "p1", "u-ann");
        ProjectLoadController controller = loadController(exportJobs);

        assertEquals(200, controller.exportJobStatus("e1", as("ann@x.org", "u-ann")).getStatusCode().value());
        assertEquals(404, controller.exportJobStatus("e1", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.exportJobStatus("e1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.exportJobStatus("e1", anonymous()).getStatusCode().value());
        assertEquals(404, controller.exportJobStatus("missing", as("ann@x.org", "u-ann")).getStatusCode().value());

        assertEquals(409, controller.downloadExportJobResult("e1", as("ann@x.org", "u-ann")).getStatusCode().value());
        assertEquals(404, controller.downloadExportJobResult("e1", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.downloadExportJobResult("e1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.downloadExportJobResult("e1", anonymous()).getStatusCode().value());
        assertEquals(404, controller.downloadExportJobResult("missing", as("ann@x.org", "u-ann")).getStatusCode().value());
    }

    @Test
    void submittingAnExportRecordsTheCallerAsOwner() {
        OntologyExportJobService exportJobs = mock(OntologyExportJobService.class);
        when(exportJobs.submit(anyString(), anyString(), any())).thenReturn(exportJob("e2", ExportJob.Status.PENDING));
        ProjectLoadController controller = loadController(exportJobs);

        ResponseEntity<Map<String, Object>> response =
                controller.submitExportJob("p1", "rdfxml", false, null, as("bob@x.org", "u-bob"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(registry.find("e2").orElseThrow().owners().contains("u-bob"));
    }

    @Test
    void importQueueRoutesNeedProjectAccess() {
        ImportQueueController controller = new ImportQueueController(mock(ImportQueueManager.class), jobAccess);

        assertEquals(200, controller.getQueueStatus("p1", as("bob@x.org", "u-bob")).getStatusCode().value());
        assertEquals(404, controller.getQueueStatus("p1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.getQueueStatus("p1", anonymous()).getStatusCode().value());
        assertEquals(200, controller.getQueuePosition("p1", as("ann@x.org", "u-ann")).getStatusCode().value());
        assertEquals(404, controller.getQueuePosition("p1", as("eve@x.org", "u-eve")).getStatusCode().value());
        assertEquals(401, controller.getQueuePosition("p1", anonymous()).getStatusCode().value());
    }
}
