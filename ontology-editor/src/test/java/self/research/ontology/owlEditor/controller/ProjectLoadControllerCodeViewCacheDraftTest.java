package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProjectLoadControllerCodeViewCacheDraftTest {

    private StorageManager storageManager;
    private ProjectLoadController controller;

    @BeforeEach
    void setUp() {
        storageManager = mock(StorageManager.class);
        controller = new ProjectLoadController(storageManager, mock(ProjectMetadataService.class),
                mock(ProjectImportService.class), mock(GridFSFileService.class), mock(ProjectShareService.class),
                mock(DraftTrackingService.class), mock(DraftCopyService.class), mock(OntologyHistoryService.class),
                mock(DraftChangeRepository.class), mock(SimpMessagingTemplate.class), mock(SparqlDatasetService.class),
                mock(ProjectRepository.class), mock(OntologyPreparseService.class), mock(ImportWorkerDispatcher.class),
                mock(MongoTemplate.class), mock(OntologyExportJobService.class), mock(CodeViewReimportPipeline.class),
                new ProjectWriteLockRegistry());
    }

    @Test
    void aPublicStoreWritesTheSharedCache() throws Exception {
        ResponseEntity<Map<String, Object>> response = controller.storeCodeViewCache("p1",
                Map.of("content", "<rdf/>", "format", "rdfxml"));

        verify(storageManager).storeCodeViewCache("p1", "<rdf/>", "rdfxml");
        assertEquals(true, response.getBody().get("stored"));
    }

    @Test
    void aDraftStoreLeavesTheSharedCacheAlone() throws Exception {
        ResponseEntity<Map<String, Object>> response = controller.storeCodeViewCache("p1",
                Map.of("content", "<rdf/>", "format", "rdfxml", "draft", "true", "userId", "me@x.org"));

        verify(storageManager, never()).storeCodeViewCache(anyString(), anyString(), anyString());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(false, response.getBody().get("stored"));
    }

    @Test
    void aDraftStoreStillRemembersWhichEntityACitationBelongsTo() throws Exception {
        controller.storeCodeViewCache("p1", Map.of("content", "<rdf/>", "format", "rdfxml", "draft", true,
                "citationUrn", "urn:citation:a", "referencedEntity", "http://x/E"));

        verify(storageManager).storeCitationEntityMapping("p1", "urn:citation:a", "http://x/E");
    }

    @Test
    void aPublicClearEmptiesTheSharedCache() {
        controller.clearCodeViewCache("p1", null, false);
        controller.clearCodeViewCache("p1", "rdfxml", false);

        verify(storageManager).clearCodeViewCache("p1");
        verify(storageManager).clearCodeViewCacheFormat("p1", "rdfxml");
    }

    @Test
    void aDraftClearLeavesTheSharedCacheAlone() {
        ResponseEntity<Map<String, Object>> response = controller.clearCodeViewCache("p1", null, true);

        verify(storageManager, never()).clearCodeViewCache(anyString());
        verify(storageManager, never()).clearCodeViewCacheFormat(anyString(), anyString());
        assertEquals(true, response.getBody().get("success"));
    }
}
