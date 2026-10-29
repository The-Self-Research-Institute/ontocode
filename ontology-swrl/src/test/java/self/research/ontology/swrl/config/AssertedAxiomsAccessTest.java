package self.research.ontology.swrl.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AssertedAxiomsAccessTest {

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

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/swrl/proj-1/axioms/asserted");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void aCallerWithoutATokenCannotListTheAxioms() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(null), response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aCallerWithoutAccessToTheProjectCannotListTheAxioms() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-1/draft/settings")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("Bearer abc"), response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aCallerWithAccessReachesTheList() throws Exception {
        editor.expect(requestTo("http://editor:8083/api/ontology/proj-1/draft/settings")).andRespond(withSuccess());

        filter.doFilter(request("Bearer abc"), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        editor.verify();
    }
}
