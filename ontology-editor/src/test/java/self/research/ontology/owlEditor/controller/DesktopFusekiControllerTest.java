package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.service.DesktopOntologyLoader;
import self.research.ontology.owlEditor.service.DesktopOpenMetricsService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopFusekiControllerTest {

    @Mock
    private ProjectImportService projectImportService;
    @Mock
    private DesktopOpenMetricsService openMetricsService;
    @Mock
    private DesktopOntologyLoader desktopOntologyLoader;
    @Mock
    private StorageManager storageManager;
    @Mock
    private DraftTrackingService draftTrackingService;

    private DesktopFusekiController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new DesktopFusekiController(projectImportService, openMetricsService,
                null, desktopOntologyLoader, storageManager, draftTrackingService);
    }

    @Test
    void successfulSaveClearsTheDesktopUsersStaleDraftTracking() throws Exception {
        when(desktopOntologyLoader.saveProject("proj-1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.saveProject("proj-1");

        assertEquals(true, response.getBody().get("saved"));
        verify(draftTrackingService).discardDrafts("proj-1", "desktop-user-local");
    }

    @Test
    void noOpSaveLeavesDraftTrackingAlone() throws Exception {
        when(desktopOntologyLoader.saveProject("proj-1")).thenReturn(false);

        controller.saveProject("proj-1");

        verify(draftTrackingService, never()).discardDrafts("proj-1", "desktop-user-local");
    }

    @Test
    void fastOpenDisabledReturns503WithoutTouchingDraftTracking() {
        controller = new DesktopFusekiController(projectImportService, openMetricsService,
                null, null, storageManager, draftTrackingService);

        ResponseEntity<Map<String, Object>> response = controller.saveProject("proj-1");

        assertEquals(503, response.getStatusCode().value());
        verify(draftTrackingService, never()).discardDrafts(anyString(), anyString());
    }
}
