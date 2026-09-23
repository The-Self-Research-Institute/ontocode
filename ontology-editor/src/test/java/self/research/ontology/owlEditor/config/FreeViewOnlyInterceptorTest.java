package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class FreeViewOnlyInterceptorTest {

    @Mock
    private WorkspaceOwnershipService workspaceOwnershipService;

    private FreeViewOnlyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        interceptor = new FreeViewOnlyInterceptor(workspaceOwnershipService);
        when(workspaceOwnershipService.resolveProjectIdFromRequestPath(anyString())).thenReturn(Optional.empty());
        when(workspaceOwnershipService.isViewerInProject(anyString(), anyString())).thenReturn(false);
        when(workspaceOwnershipService.isDraftEditorInProject(anyString(), anyString())).thenReturn(false);
        when(workspaceOwnershipService.isFreePlanUserOwner(anyString(), anyString(), any())).thenReturn(false);
    }

    private MockHttpServletRequest postRequest(String path, String plan) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"plan\":\"" + plan + "\",\"userId\":\"u1\"}").getBytes());
        request.addHeader("Authorization", "Bearer header." + payload + ".sig");
        return request;
    }

    @Test
    void freeUserCanCreateAssistantSession() throws Exception {
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions", "FREE");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void freeUserCanCallReadContext() throws Exception {
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/tools/read_context", "FREE");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void freeUserCanCallRunSparql() throws Exception {
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/tools/run_sparql", "FREE");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void freeUserBlockedFromPropose() throws Exception {
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/propose", "FREE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void freeUserBlockedFromApply() throws Exception {
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/groups/g1/apply", "FREE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void proUserAllowedOnPropose() throws Exception {
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/propose", "PRO");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void proUserAllowedOnApply() throws Exception {
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/groups/g1/apply", "PRO");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }
}
