package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.ImportQueueManager;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalReasoningDraftVersionTest {

    private InternalReasoningSupportController controller;
    private SparqlDatasetService datasetService;
    private StorageManager storageManager;
    private ProjectAccessService access;

    @BeforeEach
    void setUp() {
        datasetService = mock(SparqlDatasetService.class);
        storageManager = mock(StorageManager.class);
        access = mock(ProjectAccessService.class);
        controller = new InternalReasoningSupportController(datasetService, mock(ImportQueueManager.class),
                mock(MainGraphRevisionService.class), mock(ProjectImportService.class), access);
        ReflectionTestUtils.setField(controller, "storageManager", storageManager);
    }

    private static MockHttpServletRequest requestAs(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer header." + payload + ".sig");
        return request;
    }

    @Test
    void draftVersionChangesWithEveryDraftWrite() {
        when(access.hasProjectAccess("proj-1", "ann@example.org")).thenReturn(true);
        when(storageManager.getDraftGraphVersion("proj-1", "user-1")).thenReturn(4L);
        when(datasetService.getDraftWriteStamp("proj-1", "user-1")).thenReturn(9L);

        ResponseEntity<Map<String, Object>> before = controller.draftVersion("proj-1", "user-1", requestAs("ann@example.org"));
        when(datasetService.getDraftWriteStamp("proj-1", "user-1")).thenReturn(10L);
        ResponseEntity<Map<String, Object>> after = controller.draftVersion("proj-1", "user-1", requestAs("ann@example.org"));

        assertEquals(200, before.getStatusCode().value());
        assertEquals(4L, before.getBody().get("draftVersion"));
        assertEquals(9L, before.getBody().get("writeStamp"));
        assertTrue(before.getBody().get("version").toString().endsWith(":4.9"));
        assertNotEquals(before.getBody().get("version"), after.getBody().get("version"));
    }

    @Test
    void callersWithoutProjectAccessAreRefusedLikeTheDraftExport() {
        when(access.hasProjectAccess("proj-1", "eve@example.org")).thenReturn(false);

        assertEquals(403, controller.draftVersion("proj-1", "user-1", requestAs("eve@example.org")).getStatusCode().value());
        assertEquals(403, controller.draftVersion("proj-1", "user-1", new MockHttpServletRequest()).getStatusCode().value());
    }
}
