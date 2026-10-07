package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntityRenameServiceTest {

    @Mock
    private StorageManager storageManager;
    @Mock
    private OntologyMutationService ontologyMutationService;
    @Mock
    private DraftCopyService draftCopyService;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private HierarchyIndexService hierarchyIndexService;
    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private OntologyHistoryService historyService;

    private EntityRenameService service;

    private static final String OLD_IRI = "http://ex.org/Pizza";
    private static final String NEW_IRI = "http://ex.org/PizzaMargherita";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EntityRenameService(storageManager, ontologyMutationService, draftCopyService,
                metadataService, hierarchyIndexService, datasetService, historyService);
    }

    @Test
    void renameEntityRecordsAHistoryEntryOnSuccess() throws Exception {
        when(datasetService.execAsk(eq("proj-1"), anyString())).thenReturn(true, false);

        service.renameEntity("proj-1", OLD_IRI, NEW_IRI, "u1", "User One");

        verify(ontologyMutationService).applyRawUpdate(eq("proj-1"), anyString(), eq(false), isNull());
        verify(hierarchyIndexService).scheduleBuild("proj-1");
        verify(historyService).recordEdit("proj-1", "u1", "User One", "renameEntity", NEW_IRI, null,
                OLD_IRI, NEW_IRI, "Renamed " + OLD_IRI + " to " + NEW_IRI);
    }

    @Test
    void renameEntityDoesNotRecordWhenTheSourceEntityDoesNotExist() {
        when(datasetService.execAsk(eq("proj-1"), anyString())).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> service.renameEntity("proj-1", OLD_IRI, NEW_IRI, "u1", "User One"));

        verify(historyService, org.mockito.Mockito.never()).recordEdit(
                anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyString(), anyString());
    }

    @Test
    void renameEntityIsANoOpWhenOldAndNewIriAreIdentical() throws Exception {
        service.renameEntity("proj-1", OLD_IRI, OLD_IRI, "u1", "User One");

        verify(ontologyMutationService, org.mockito.Mockito.never())
                .applyRawUpdate(anyString(), anyString(), org.mockito.Mockito.anyBoolean(), org.mockito.ArgumentMatchers.any());
        verify(historyService, org.mockito.Mockito.never()).recordEdit(
                anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyString(), anyString());
    }
}
