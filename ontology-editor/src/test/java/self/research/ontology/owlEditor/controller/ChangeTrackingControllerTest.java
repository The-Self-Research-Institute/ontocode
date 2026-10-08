package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.HistorySyncService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.Base64;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeTrackingControllerTest {

    @Mock
    private HistorySyncService historySyncService;
    @Mock
    private WorkspaceOwnershipService workspaceOwnershipService;

    private ChangeTrackingController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ChangeTrackingController();
        ReflectionTestUtils.setField(controller, "historySyncService", historySyncService);
        ReflectionTestUtils.setField(controller, "rollbackSupport", new RollbackRequestSupport(workspaceOwnershipService));
        when(historySyncService.addComment(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private MockHttpServletRequest requestWithEmail(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"email\":\"" + email + "\"}").getBytes());
        request.addHeader("Authorization", "Bearer header." + payload + ".sig");
        return request;
    }

    @Test
    void addCommentUsesTheAuthenticatedUserNotTheRequestBody() {
        MockHttpServletRequest httpRequest = requestWithEmail("real.user@example.com");

        controller.addComment("proj-1", "c1", Map.of("text", "Looks good"), httpRequest);

        verify(historySyncService).addComment("c1", "real.user@example.com", "real.user@example.com", "Looks good");
    }

    @Test
    void addCommentFallsBackToSystemWithNoAuthHeader() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        controller.addComment("proj-1", "c1", Map.of("text", "Looks good"), httpRequest);

        verify(historySyncService).addComment("c1", "system", "System", "Looks good");
    }
}
