package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.ProjectAccessService;

import javax.crypto.SecretKey;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EditorApiAuthInterceptorTest {

    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private ProjectAccessService projectAccessService;
    private AssistantSessionRepository sessionRepository;
    private EditorApiAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        projectAccessService = mock(ProjectAccessService.class);
        sessionRepository = mock(AssistantSessionRepository.class);
        interceptor = new EditorApiAuthInterceptor(projectAccessService, sessionRepository);
        ReflectionTestUtils.setField(interceptor, "jwtSecret", Encoders.BASE64.encode(key.getEncoded()));
        ReflectionTestUtils.setField(interceptor, "requireJwt", true);
        ReflectionTestUtils.setField(interceptor, "desktopMode", false);
    }

    private MockHttpServletRequest request(String method, String uri, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Authorization", "Bearer " + Jwts.builder().subject(email).signWith(key).compact());
        return request;
    }

    private void sessionOnProject(String sessionId, String projectId) {
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(AssistantSessionDocument.builder().id(sessionId).projectId(projectId).build()));
    }

    @Test
    void assistantToolCallOnAnotherUsersProjectIsForbidden() throws Exception {
        sessionOnProject("s1", "proj-victim");
        when(projectAccessService.hasProjectAccess("proj-victim", "intruder@x.com")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(
                request("POST", "/api/v1/code-assistant/sessions/s1/tools/read_context", "intruder@x.com"),
                response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void assistantToolCallOnAnAccessibleProjectPasses() throws Exception {
        sessionOnProject("s1", "proj-1");
        when(projectAccessService.hasProjectAccess("proj-1", "owner@x.com")).thenReturn(true);

        boolean allowed = interceptor.preHandle(
                request("POST", "/api/v1/code-assistant/sessions/s1/groups/g1/apply", "owner@x.com"),
                new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void sessionCreateIsLeftToTheControllerWithTheVerifiedEmailRecorded() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/code-assistant/sessions", "owner@x.com");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals("owner@x.com", request.getAttribute(EditorApiAuthInterceptor.VERIFIED_EMAIL_ATTRIBUTE));
        verify(projectAccessService, never()).hasProjectAccess(any(), any());
    }

    @Test
    void unknownSessionIsLeftToTheControllerToReject() throws Exception {
        when(sessionRepository.findById("missing")).thenReturn(Optional.empty());

        boolean allowed = interceptor.preHandle(
                request("POST", "/api/v1/code-assistant/sessions/missing/propose", "owner@x.com"),
                new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        verify(projectAccessService, never()).hasProjectAccess(any(), any());
    }

    @Test
    void projectPathVariableStillTakesPrecedence() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v1/code-assistant/projects/proj-2/recovery", "u@x.com");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "proj-2"));
        when(projectAccessService.hasProjectAccess("proj-2", "u@x.com")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void nothingIsCheckedWhenJwtEnforcementIsOff() throws Exception {
        ReflectionTestUtils.setField(interceptor, "requireJwt", false);

        assertTrue(interceptor.preHandle(
                request("POST", "/api/v1/code-assistant/sessions/s1/tools/run_sparql", "anyone@x.com"),
                new MockHttpServletResponse(), new Object()));
        verify(sessionRepository, never()).findById(any());
    }
}
