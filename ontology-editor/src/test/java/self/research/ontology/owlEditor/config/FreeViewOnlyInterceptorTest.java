package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
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

    @Mock
    private AssistantSessionRepository assistantSessionRepository;

    @Mock
    private AssistantEditGroupRepository assistantEditGroupRepository;

    private FreeViewOnlyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        interceptor = new FreeViewOnlyInterceptor(workspaceOwnershipService, assistantSessionRepository,
                assistantEditGroupRepository);
        when(workspaceOwnershipService.resolveProjectIdFromRequestPath(anyString())).thenReturn(Optional.empty());
        when(workspaceOwnershipService.isViewerInProject(anyString(), anyString())).thenReturn(false);
        when(workspaceOwnershipService.isDraftEditorInProject(anyString(), anyString())).thenReturn(false);
        when(workspaceOwnershipService.isFreePlanUserOwner(anyString(), anyString(), any())).thenReturn(false);
        when(assistantSessionRepository.findById(anyString())).thenReturn(Optional.empty());
        when(assistantEditGroupRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    private AssistantEditGroupDocument groupFor(String sessionId, String projectId) {
        return AssistantEditGroupDocument.builder().id("g1").sessionId(sessionId).projectId(projectId).build();
    }

    private MockHttpServletRequest postRequest(String path, String plan) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"plan\":\"" + plan + "\",\"userId\":\"u1\"}").getBytes());
        request.addHeader("Authorization", "Bearer header." + payload + ".sig");
        return request;
    }

    private AssistantSessionDocument sessionFor(String projectId) {
        return AssistantSessionDocument.builder().id("s1").projectId(projectId).build();
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

    @Test
    void viewerOnResolvedProjectBlockedFromPropose() throws Exception {
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-77")));
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-77")).thenReturn(true);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/propose", "PRO");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("viewOnly"));
    }

    @Test
    void viewerOnResolvedProjectBlockedFromApply() throws Exception {
        when(assistantEditGroupRepository.findById("g1")).thenReturn(Optional.of(groupFor("s1", "proj-77")));
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-77")).thenReturn(true);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/groups/g1/apply", "PRO");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("viewOnly"));
    }

    @Test
    void draftEditorOnResolvedProjectBlockedFromPropose() throws Exception {
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-77")));
        when(workspaceOwnershipService.isDraftEditorInProject("u1", "proj-77")).thenReturn(true);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/propose", "PRO");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("draftAllowed"));
    }

    @Test
    void draftEditorOnResolvedProjectBlockedFromApply() throws Exception {
        when(assistantEditGroupRepository.findById("g1")).thenReturn(Optional.of(groupFor("s1", "proj-77")));
        when(workspaceOwnershipService.isDraftEditorInProject("u1", "proj-77")).thenReturn(true);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/groups/g1/apply", "PRO");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void normalEditorOnResolvedProjectAllowedOnProposeAndApply() throws Exception {
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-77")));
        when(assistantEditGroupRepository.findById("g1")).thenReturn(Optional.of(groupFor("s1", "proj-77")));

        MockHttpServletRequest proposeRequest = postRequest("/api/v1/code-assistant/sessions/s1/propose", "PRO");
        assertTrue(interceptor.preHandle(proposeRequest, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest applyRequest = postRequest("/api/v1/code-assistant/sessions/s1/groups/g1/apply", "PRO");
        assertTrue(interceptor.preHandle(applyRequest, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void unknownSessionIdOnProposeDoesNotErrorAndFallsThroughToPlanCheck() throws Exception {
        when(assistantSessionRepository.findById("does-not-exist")).thenReturn(Optional.empty());
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/does-not-exist/propose", "PRO");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void viewerStillAllowedOnReadOnlyEndpointsEvenWhenSessionResolvesToTheirProject() throws Exception {
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-77")));
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-77")).thenReturn(true);

        MockHttpServletRequest readContext = postRequest("/api/v1/code-assistant/sessions/s1/tools/read_context", "PRO");
        assertTrue(interceptor.preHandle(readContext, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest runSparql = postRequest("/api/v1/code-assistant/sessions/s1/tools/run_sparql", "PRO");
        assertTrue(interceptor.preHandle(runSparql, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest sessionCreate = postRequest("/api/v1/code-assistant/sessions", "PRO");
        assertTrue(interceptor.preHandle(sessionCreate, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void applyRoleCheckUsesTheGroupsProjectNotTheUrlSessionsProject() throws Exception {
        when(assistantSessionRepository.findById("s-editor")).thenReturn(Optional.of(sessionFor("proj-editor")));
        when(assistantEditGroupRepository.findById("g1")).thenReturn(Optional.of(groupFor("s-viewer", "proj-demoted")));
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-demoted")).thenReturn(true);
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-editor")).thenReturn(false);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s-editor/groups/g1/apply", "PRO");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("viewOnly"));
    }

    @Test
    void applyForUnknownGroupFallsThroughWithoutConsultingTheUrlSession() throws Exception {
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-77")));
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-77")).thenReturn(true);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/groups/missing/apply", "PRO");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }
}
