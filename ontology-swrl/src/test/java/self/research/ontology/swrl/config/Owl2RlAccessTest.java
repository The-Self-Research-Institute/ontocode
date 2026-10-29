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
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Owl2RlAccessTest {

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

    private static MockHttpServletRequest request(String method, String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void theRunAndThePagingRoutesRefuseACallerWithoutAToken() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                request("POST", "/api/swrl/proj-1/owl2rl", null),
                request("GET", "/api/swrl/proj-1/owl2rl/runs/abc", null)}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertEquals(401, response.getStatus());
        }
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aCallerWithoutAccessToTheProjectIsRefusedWith403AndNothingRuns() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-1/draft/settings"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/swrl/proj-1/owl2rl", "Bearer other"), response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
        editor.verify();
    }

    @Test
    void aCallerWithAccessIsPassedOn() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-1/draft/settings")).andRespond(withSuccess());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/swrl/proj-1/owl2rl", "Bearer ok"), response, chain);

        verify(chain).doFilter(any(), any());
    }
}
