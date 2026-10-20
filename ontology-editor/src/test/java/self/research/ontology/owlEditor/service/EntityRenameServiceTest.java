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
        verify(historyService).recordEdit(eq("proj-1"), eq("u1"), eq("User One"), eq("renameEntity"), eq(NEW_IRI), isNull(),
                eq(OLD_IRI), eq(NEW_IRI), eq("Renamed " + OLD_IRI + " to " + NEW_IRI), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any(ChangeOrigin.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renameEntityIsCapturedAsAWholeGraphDeltaWhenTheRecorderIsPresent() throws Exception {
        ExactChangeRecorder recorder = org.mockito.Mockito.mock(ExactChangeRecorder.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "exactRecorder", recorder);
        when(datasetService.execAsk(eq("proj-1"), anyString())).thenReturn(true, false);
        when(recorder.recordAround(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("set-1");

        service.renameEntity("proj-1", OLD_IRI, NEW_IRI, "u1", "User One");

        org.mockito.ArgumentCaptor<java.util.List<OntologyMutationService.MutationOp>> ops =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(recorder).recordAround(eq("proj-1"), eq(false), eq("u1"), ops.capture(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.junit.jupiter.api.Assertions.assertEquals("renameEntity", ops.getValue().get(0).type());
        org.junit.jupiter.api.Assertions.assertTrue(
                self.research.ontology.owlEditor.service.delta.ExactSeeds.needsWholeGraph(ops.getValue()));
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

    @Test
    void renameChecksTheInMemoryModelOnDesktopSoAJustCreatedClassCanBeRenamed() throws Exception {
        DesktopOwlApiMutationService desktop = org.mockito.Mockito.mock(DesktopOwlApiMutationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "desktopMutation", desktop);
        when(desktop.declaresEntity("proj-1", OLD_IRI)).thenReturn(java.util.Optional.of(true));
        when(desktop.declaresEntity("proj-1", NEW_IRI)).thenReturn(java.util.Optional.of(false));

        service.renameEntity("proj-1", OLD_IRI, NEW_IRI, "u1", "User One");

        verify(ontologyMutationService).applyRawUpdate(eq("proj-1"), anyString(), eq(false), isNull());
        org.mockito.Mockito.verifyNoInteractions(datasetService);
    }

    @Test
    void renameRefusesAnUnknownClassOnDesktop() {
        DesktopOwlApiMutationService desktop = org.mockito.Mockito.mock(DesktopOwlApiMutationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "desktopMutation", desktop);
        when(desktop.declaresEntity("proj-1", OLD_IRI)).thenReturn(java.util.Optional.of(false));

        assertThrows(IllegalArgumentException.class, () -> service.renameEntity("proj-1", OLD_IRI, NEW_IRI, "u1", "User One"));
    }

    @Test
    void renameFallsBackToTheSearchIndexWhenTheModelIsNotInMemory() throws Exception {
        DesktopOwlApiMutationService desktop = org.mockito.Mockito.mock(DesktopOwlApiMutationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "desktopMutation", desktop);
        when(desktop.declaresEntity(anyString(), anyString())).thenReturn(java.util.Optional.empty());
        when(datasetService.execAsk(eq("proj-1"), anyString())).thenReturn(true, false);

        service.renameEntity("proj-1", OLD_IRI, NEW_IRI, "u1", "User One");

        verify(ontologyMutationService).applyRawUpdate(eq("proj-1"), anyString(), eq(false), isNull());
    }
}
