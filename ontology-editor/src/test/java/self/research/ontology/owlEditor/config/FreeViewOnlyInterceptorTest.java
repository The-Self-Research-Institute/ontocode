package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.ProjectRecoveryLockService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreeViewOnlyInterceptorTest {

    @Mock
    private WorkspaceOwnershipService workspaceOwnershipService;

    @Mock
    private AssistantSessionRepository assistantSessionRepository;

    @Mock
    private AssistantEditGroupRepository assistantEditGroupRepository;

    @Mock
    private ProjectRecoveryLockService recoveryLockService;

    private FreeViewOnlyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        interceptor = new FreeViewOnlyInterceptor(workspaceOwnershipService, assistantSessionRepository,
                assistantEditGroupRepository, recoveryLockService);
        when(recoveryLockService.isLocked(anyString())).thenReturn(false);
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

    private MockHttpServletRequest requestWithoutJwt(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private void assertRecoveryLocked(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(423, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"errorCode\":\"PROJECT_RECOVERY_LOCKED\""));
        assertTrue(body.contains("\"recoveryLocked\":true"));
        assertTrue(body.contains("\"error\":"));
        assertTrue(body.contains("\"ok\":false"));
    }

    @Test
    void mutatingRequestOnLockedProjectIsRejectedWith423() throws Exception {
        when(recoveryLockService.isLocked("proj-9")).thenReturn(true);
        when(workspaceOwnershipService.resolveProjectIdFromRequestPath(contains("/proj-9/")))
                .thenReturn(Optional.of("proj-9"));

        assertRecoveryLocked(postRequest("/api/ontology/proj-9/classes", "PRO"));

        MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/api/ontology/classes");
        put.setParameter("projectId", "proj-9");
        assertRecoveryLocked(put);

        assertRecoveryLocked(new MockHttpServletRequest("DELETE", "/api/projects/proj-9/files/a.ttl"));
        assertRecoveryLocked(new MockHttpServletRequest("PATCH", "/api/projects/proj-9/settings"));
    }

    @Test
    void assistantApplyAndProposeOnLockedProjectAreRejectedWith423() throws Exception {
        when(recoveryLockService.isLocked("proj-9")).thenReturn(true);
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-9")));
        when(assistantEditGroupRepository.findById("g1")).thenReturn(Optional.of(groupFor("s1", "proj-9")));

        assertRecoveryLocked(postRequest("/api/v1/code-assistant/sessions/s1/propose", "PRO"));
        assertRecoveryLocked(postRequest("/api/v1/code-assistant/sessions/s1/groups/g1/apply", "PRO"));
    }

    @Test
    void lockIsCheckedBeforeTheDesktopLocalhostBypassWithoutAJwt() throws Exception {
        ReflectionTestUtils.setField(interceptor, "desktopMode", true);
        when(recoveryLockService.isLocked("proj-9")).thenReturn(true);
        when(workspaceOwnershipService.resolveProjectIdFromRequestPath("/api/ontology/proj-9/classes"))
                .thenReturn(Optional.of("proj-9"));

        assertRecoveryLocked(requestWithoutJwt("POST", "/api/ontology/proj-9/classes"));
    }

    @Test
    void desktopLocalhostBypassStillAppliesWhenTheProjectIsNotLocked() throws Exception {
        ReflectionTestUtils.setField(interceptor, "desktopMode", true);
        MockHttpServletRequest request = postRequest("/api/ontology/proj-9/classes", "FREE");
        request.setRemoteAddr("127.0.0.1");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void readsAndReadOnlyPostsKeepWorkingOnALockedProject() throws Exception {
        when(recoveryLockService.isLocked(anyString())).thenReturn(true);
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-9")));

        assertTrue(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/ontology/proj-9/classes"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(postRequest("/api/v1/code-assistant/sessions/s1/tools/read_context", "PRO"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(postRequest("/api/sparql/query/proj-9", "PRO"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(postRequest("/api/v1/code-assistant/sessions/s1/provider-call", "PRO"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void recoveryEndpointsAreNotBlockedByTheLockTheyResolve() throws Exception {
        when(recoveryLockService.isLocked("proj-9")).thenReturn(true);

        assertTrue(interceptor.preHandle(postRequest("/api/v1/code-assistant/projects/proj-9/recovery/restore", "PRO"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(postRequest("/api/v1/code-assistant/projects/proj-9/recovery/clear", "PRO"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void recoveryEndpointsWorkInDesktopModeOnALockedProject() throws Exception {
        ReflectionTestUtils.setField(interceptor, "desktopMode", true);
        when(recoveryLockService.isLocked("proj-9")).thenReturn(true);

        assertTrue(interceptor.preHandle(requestWithoutJwt("POST", "/api/v1/code-assistant/projects/proj-9/recovery/restore"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void unlockedProjectIsNotBlocked() throws Exception {
        assertTrue(interceptor.preHandle(postRequest("/api/ontology/proj-9/classes", "PRO"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void viewerIsForbiddenFromRecoveryRestoreAndClear() throws Exception {
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-9")).thenReturn(true);

        for (String action : new String[]{"restore", "clear"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(
                    postRequest("/api/v1/code-assistant/projects/proj-9/recovery/" + action, "PRO"), response, new Object());
            assertFalse(allowed);
            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains("viewOnly"));
        }
    }

    @Test
    void draftEditorIsForbiddenFromRecoveryEvenWithTheDraftFlag() throws Exception {
        when(workspaceOwnershipService.isDraftEditorInProject("u1", "proj-9")).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/projects/proj-9/recovery/restore", "PRO");
        request.setParameter("draft", "true");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());

        MockHttpServletResponse clearResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                postRequest("/api/v1/code-assistant/projects/proj-9/recovery/clear", "PRO"), clearResponse, new Object()));
        assertEquals(403, clearResponse.getStatus());
    }

    @Test
    void draftEditorCannotApplyAssistantEditsByAddingTheDraftFlag() throws Exception {
        when(assistantEditGroupRepository.findById("g1")).thenReturn(Optional.of(groupFor("s1", "proj-77")));
        when(workspaceOwnershipService.isDraftEditorInProject("u1", "proj-77")).thenReturn(true);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/sessions/s1/groups/g1/apply", "PRO");
        request.setParameter("useDraft", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void recoveryRoleCheckUsesThePathProjectNotAQueryParameter() throws Exception {
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-9")).thenReturn(true);
        MockHttpServletRequest request = postRequest("/api/v1/code-assistant/projects/proj-9/recovery/clear", "PRO");
        request.setParameter("projectId", "proj-mine");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void editorIsAllowedOnRecoveryEndpoints() throws Exception {
        assertTrue(interceptor.preHandle(postRequest("/api/v1/code-assistant/projects/proj-9/recovery/restore", "PRO"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void viewerCanUseManagedProviderCallAndUsageReporting() throws Exception {
        when(assistantSessionRepository.findById("s1")).thenReturn(Optional.of(sessionFor("proj-77")));
        when(workspaceOwnershipService.isViewerInProject("u1", "proj-77")).thenReturn(true);

        assertTrue(interceptor.preHandle(postRequest("/api/v1/code-assistant/sessions/s1/provider-call", "FREE"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(postRequest("/api/v1/code-assistant/sessions/s1/usage", "FREE"),
                new MockHttpServletResponse(), new Object()));
        verify(workspaceOwnershipService, never()).isViewerInProject(anyString(), anyString());
    }
}
