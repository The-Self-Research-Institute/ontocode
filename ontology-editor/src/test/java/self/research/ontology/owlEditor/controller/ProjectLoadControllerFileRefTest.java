package self.research.ontology.owlEditor.controller;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectLoadControllerFileRefTest {

    private MongoTemplate mongoTemplate;
    private GridFSFileService gridFs;
    private ProjectLoadController controller;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class, Answers.RETURNS_DEEP_STUBS);
        gridFs = mock(GridFSFileService.class);
        when(gridFs.getFileById(anyString())).thenReturn(Optional.empty());
        controller = new ProjectLoadController(mock(StorageManager.class), mock(ProjectMetadataService.class),
                mock(ProjectImportService.class), gridFs, mock(ProjectShareService.class),
                mock(DraftTrackingService.class), mock(DraftCopyService.class), mock(OntologyHistoryService.class),
                mock(DraftChangeRepository.class), mock(SimpMessagingTemplate.class), mock(SparqlDatasetService.class),
                mock(ProjectRepository.class), mock(OntologyPreparseService.class), mock(ImportWorkerDispatcher.class),
                mongoTemplate, mock(OntologyExportJobService.class), mock(CodeViewReimportPipeline.class),
                new ProjectWriteLockRegistry(), mock(ProjectAuthorization.class));
    }

    private void fileBelongingTo(String parentProjectId) {
        Document meta = new Document("fileId", "f1").append("projectId", parentProjectId).append("gridfsId", "507f1f77bcf86cd799439011")
                .append("fileName", "a.owl");
        when(mongoTemplate.getDb().getCollection("file_metadata").find(any(Document.class)).first()).thenReturn(meta);
    }

    private ResponseEntity<Map<String, Object>> upload(String projectId, String parentProjectId) {
        return controller.uploadByFileRef(projectId, "f1", parentProjectId, "owner@x.org", null, null, null, null);
    }

    @Test
    void aFileOfAnotherProjectCannotBeImportedIntoThisOne() {
        fileBelongingTo("proj-victim");

        ResponseEntity<Map<String, Object>> response = upload("proj-mine--f1", "proj-mine");

        assertEquals(404, response.getStatusCode().value());
        assertEquals("File not found: f1", response.getBody().get("error"));
        verify(gridFs, never()).getFileById(anyString());
    }

    @Test
    void aParentThatDoesNotMatchThePathProjectIsRefused() {
        fileBelongingTo("proj-victim");

        ResponseEntity<Map<String, Object>> response = upload("proj-mine--f1", "proj-victim");

        assertEquals("File not found: f1", response.getBody().get("error"));
        verify(gridFs, never()).getFileById(anyString());
    }

    @Test
    void aFileOfTheSameProjectIsFound() {
        fileBelongingTo("proj-mine");

        ResponseEntity<Map<String, Object>> response = upload("proj-mine--f1", "proj-mine");

        assertEquals("File content not found in storage", response.getBody().get("error"));
        verify(gridFs).getFileById("507f1f77bcf86cd799439011");
    }
}
