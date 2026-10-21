package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreeViewOnlyInterceptorDraftEditorTest {

    private static final String PROJECT = "proj-9";

    private final WorkspaceOwnershipService ownership = mock(WorkspaceOwnershipService.class);
    private FreeViewOnlyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        ProjectRecoveryLockService locks = mock(ProjectRecoveryLockService.class);
        AssistantSessionRepository sessions = mock(AssistantSessionRepository.class);
        AssistantEditGroupRepository groups = mock(AssistantEditGroupRepository.class);
        interceptor = new FreeViewOnlyInterceptor(ownership, sessions, groups, locks);
        when(locks.isLocked(anyString())).thenReturn(false);
        when(ownership.resolveProjectIdFromRequestPath(anyString())).thenReturn(Optional.of(PROJECT));
        when(ownership.isFreePlanUserOwner(anyString(), anyString(), any())).thenReturn(false);
        when(sessions.findById(anyString())).thenReturn(Optional.empty());
        when(groups.findById(anyString())).thenReturn(Optional.empty());
    }

    private void member(boolean viewer, boolean draftEditor) {
        when(ownership.isViewerInProject("u1", PROJECT)).thenReturn(viewer);
        when(ownership.isDraftEditorInProject("u1", PROJECT)).thenReturn(draftEditor);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"plan\":\"PRO\",\"userId\":\"u1\"}".getBytes());
        request.addHeader("Authorization", "Bearer header." + payload + ".sig");
        return request;
    }

    private boolean allowed(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        return interceptor.preHandle(request, response, new Object());
    }

    @ParameterizedTest
    @CsvSource({
            "PUT, /api/ontology/metadata/proj-9/iri",
            "POST, /api/ontology/metadata/proj-9/annotations",
            "PUT, /api/ontology/metadata/proj-9/annotations",
            "POST, /api/ontology/metadata/proj-9/imports",
            "POST, /api/ontology/metadata/proj-9/prefixes",
            "POST, /api/ontology/metadata/proj-9/gci",
            "PUT, /api/ontology/metadata/proj-9/gci/0"
    })
    void aDraftOnlyMemberSavesActiveOntologyEditsWhenTheUrlSaysDraft(String method, String path) throws Exception {
        member(false, true);

        MockHttpServletRequest draft = request(method, path);
        draft.setParameter("draft", "true");
        assertTrue(allowed(draft, new MockHttpServletResponse()));

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(allowed(request(method, path), blocked));
        assertEquals(403, blocked.getStatus());
        assertTrue(blocked.getContentAsString().contains("draftAllowed"));
    }

    @ParameterizedTest
    @CsvSource({
            "/api/ontology/proj-9/expression/parse",
            "/api/ontology/annotations/batch/proj-9",
            "/api/ontology/export-async/proj-9"
    })
    void readOnlyPostsAreNotTreatedAsEdits(String path) throws Exception {
        member(false, true);
        assertTrue(allowed(request("POST", path), new MockHttpServletResponse()));
        member(true, false);
        assertTrue(allowed(request("POST", path), new MockHttpServletResponse()));
    }

    @Test
    void aDraftOnlyMemberCanCommentOnAndResolveChangesButAViewerCannot() throws Exception {
        member(false, true);
        assertTrue(allowed(request("POST", "/api/ontology/proj-9/changes/c1/comments"), new MockHttpServletResponse()));
        assertTrue(allowed(request("POST", "/api/ontology/proj-9/changes/c1/resolve-conflict"), new MockHttpServletResponse()));

        member(true, false);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(allowed(request("POST", "/api/ontology/proj-9/changes/c1/comments"), blocked));
        assertEquals(403, blocked.getStatus());
    }

    @Test
    void aDraftOnlyMemberCanKeepSpreadsheetRuleSets() throws Exception {
        member(false, true);
        assertTrue(allowed(request("POST", "/api/ontology/spreadsheet-import/proj-9/rulesets"), new MockHttpServletResponse()));
        assertTrue(allowed(request("PUT", "/api/ontology/spreadsheet-import/proj-9/rulesets/r1"), new MockHttpServletResponse()));
        assertTrue(allowed(request("DELETE", "/api/ontology/spreadsheet-import/proj-9/rulesets/r1"), new MockHttpServletResponse()));
    }

    @Test
    void aDraftOnlyMemberStillCannotWriteToPublicWithoutTheDraftFlag() throws Exception {
        member(false, true);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(allowed(request("DELETE", "/api/citations/proj-9/urn:citation:x"), blocked));
        assertEquals(403, blocked.getStatus());

        MockHttpServletRequest draft = request("DELETE", "/api/citations/proj-9/urn:citation:x");
        draft.setParameter("draft", "true");
        assertTrue(allowed(draft, new MockHttpServletResponse()));
    }
}
