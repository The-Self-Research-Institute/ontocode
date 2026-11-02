package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.ProjectStatus;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ImportWorkerDispatcher;
import self.research.ontology.owlEditor.service.OntologyExportJobService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyPreparseService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectLoadControllerDeleteAuthTest {

    private SparqlDatasetService datasetService;
    private ProjectMetadataService metadataService;
    private ProjectAccessService access;
    private ProjectLoadController controller;

    @BeforeEach
    void setUp() {
        datasetService = mock(SparqlDatasetService.class);
        metadataService = mock(ProjectMetadataService.class);
        access = mock(ProjectAccessService.class);
        when(metadataService.readStatus("p1")).thenReturn(Optional.of(mock(ProjectStatus.class)));
        controller = new ProjectLoadController(mock(StorageManager.class), metadataService,
                mock(ProjectImportService.class), mock(GridFSFileService.class), mock(ProjectShareService.class),
                mock(DraftTrackingService.class), mock(DraftCopyService.class), mock(OntologyHistoryService.class),
                mock(DraftChangeRepository.class), mock(SimpMessagingTemplate.class), datasetService,
                mock(ProjectRepository.class), mock(OntologyPreparseService.class), mock(ImportWorkerDispatcher.class),
                mock(MongoTemplate.class), mock(OntologyExportJobService.class), mock(CodeViewReimportPipeline.class),
                new ProjectWriteLockRegistry(), new ProjectAuthorization(access, false));
    }

    private static MockHttpServletRequest signedIn(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\",\"userId\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void aMemberWhoIsNotTheOwnerCannotDeleteTheProject() {
        when(access.isOwner("p1", "u-member", "member@x.org")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteProject("p1", signedIn("member@x.org", "u-member"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(datasetService, never()).clearDataset(anyString());
    }

    @Test
    void aCallerWithoutATokenCannotDeleteTheProject() {
        ResponseEntity<?> response = controller.deleteProject("p1", new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(datasetService, never()).clearDataset(anyString());
    }

    @Test
    void theOwnerCanDeleteTheProject() {
        when(access.isOwner("p1", "u-owner", "owner@x.org")).thenReturn(true);

        ResponseEntity<?> response = controller.deleteProject("p1", signedIn("owner@x.org", "u-owner"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(datasetService).clearDataset("p1");
    }
}
