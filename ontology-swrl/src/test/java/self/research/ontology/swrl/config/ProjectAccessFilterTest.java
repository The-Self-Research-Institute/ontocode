package self.research.ontology.swrl.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProjectAccessFilterTest {

    private MockRestServiceServer editor;
    private ProjectAccessFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new org.springframework.web.util.DefaultUriBuilderFactory("http://editor:8083"));
        editor = MockRestServiceServer.createServer(restTemplate);
        filter = new ProjectAccessFilter(restTemplate, true, "svc-token");
        chain = mock(FilterChain.class);
    }

    private static MockHttpServletRequest rulesRequest(String project, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/swrl/" + project + "/rules");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void aCallerWithAccessToTheProjectIsPassedOnAndTheTokenIsForwardedToTheEditor() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-1/draft/settings"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer abc"))
                .andRespond(withSuccess());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(rulesRequest("proj-1", "Bearer abc"), response, chain);

        verify(chain).doFilter(any(), any());
        editor.verify();
    }

    @Test
    void aCallerTheEditorRefusesIsForbiddenAndNeverReachesTheRules() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-9/draft/settings"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(rulesRequest("proj-9", "Bearer abc"), response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aRequestWithoutATokenIsUnauthorizedWithoutCallingTheEditor() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(rulesRequest("proj-1", null), response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
        editor.verify();
    }

    @Test
    void anEditorThatCannotBeReachedMeansAccessCannotBeVerified() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-1/draft/settings"))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException("down");
                });
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(rulesRequest("proj-1", "Bearer abc"), response, chain);

        assertEquals(503, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aGrantedAccessIsRememberedForAShortTime() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-1/draft/settings")).andRespond(withSuccess());

        filter.doFilter(rulesRequest("proj-1", "Bearer abc"), new MockHttpServletResponse(), chain);
        filter.doFilter(rulesRequest("proj-1", "Bearer abc"), new MockHttpServletResponse(), chain);

        verify(chain, times(2)).doFilter(any(), any());
        editor.verify();
    }

    @Test
    void anEncodedRouteIsCheckedLikeThePlainOne() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-9/draft/settings"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/%73wrl/proj-9/rules");
        request.addHeader("Authorization", "Bearer abc");

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
        editor.verify();
    }

    @Test
    void anotherServiceWithTheInternalTokenIsPassedOnWithoutAUserToken() throws Exception {
        MockHttpServletRequest request = rulesRequest("proj-1", null);
        request.addHeader("X-Ontocode-Internal-Token", "svc-token");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        editor.verify();
    }

    @Test
    void aWrongInternalTokenIsNotTrusted() throws Exception {
        MockHttpServletRequest request = rulesRequest("proj-1", null);
        request.addHeader("X-Ontocode-Internal-Token", "guess");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void routesThatAreNotProjectScopedAreLeftAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        editor.verify();
    }

    @Test
    void theCheckCanBeSwitchedOff() throws Exception {
        ProjectAccessFilter off = new ProjectAccessFilter(new RestTemplate(), false, "svc-token");

        off.doFilter(rulesRequest("proj-1", null), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void aBrowserPreflightWithoutAnAuthorizationHeaderIsPassedOn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/swrl/proj-1/rules");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(HttpStatus.OK.value(), response.getStatus());
    }
}
