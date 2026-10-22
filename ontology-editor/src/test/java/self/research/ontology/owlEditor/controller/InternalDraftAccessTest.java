package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.ImportQueueManager;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class InternalDraftAccessTest {

    private InternalReasoningSupportController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalReasoningSupportController(mock(SparqlDatasetService.class), mock(ImportQueueManager.class),
                mock(MainGraphRevisionService.class), mock(ProjectImportService.class), mock(ProjectAccessService.class));
        ReflectionTestUtils.setField(controller, "storageManager", mock(StorageManager.class));
        ReflectionTestUtils.setField(controller, "internalToken", "secret-token");
    }

    private static MockHttpServletRequest withToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ontocode-Internal-Token", token);
        return request;
    }

    @Test
    void theReasonerWorkerMayReadADraftWithTheInternalToken() {
        assertEquals(200, controller.draftVersion("proj-1", "user-1", withToken("secret-token")).getStatusCode().value());
    }

    @Test
    void aWrongInternalTokenStillNeedsTheUsersLogin() {
        assertEquals(403, controller.draftVersion("proj-1", "user-1", withToken("guess")).getStatusCode().value());
        assertEquals(403, controller.draftVersion("proj-1", "user-1", new MockHttpServletRequest()).getStatusCode().value());
    }
}
