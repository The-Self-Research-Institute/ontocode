package self.research.ontology.reasoner.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalTokenFilterTest {

    private final InternalTokenFilter filter = new InternalTokenFilter("s3cret-token");

    private MockHttpServletResponse call(String method, String uri, String token) throws Exception {
        return call(filter, method, uri, token, new MockFilterChain());
    }

    private MockHttpServletResponse call(InternalTokenFilter f, String method, String uri, String token,
                                         MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (token != null) {
            request.addHeader(InternalTokenFilter.HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(request, response, chain);
        return response;
    }

    @Test
    void correctTokenPassesToTheEndpoint() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call(filter, "POST", "/api/reasoning/jobs", "s3cret-token", chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void missingTokenIsRefusedOnEveryJobRoute() throws Exception {
        for (String[] route : new String[][]{
                {"POST", "/api/reasoning/jobs"}, {"GET", "/api/reasoning/jobs/j1"},
                {"DELETE", "/api/reasoning/jobs/j1"}, {"GET", "/api/reasoning/stats"}}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = call(filter, route[0], route[1], null, chain);

            assertEquals(401, response.getStatus(), route[0] + " " + route[1]);
            assertNull(chain.getRequest());
        }
    }

    @Test
    void wrongTokenIsRefused() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call(filter, "GET", "/api/reasoning/jobs/j1", "nope", chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void healthEndpointIsOpen() throws Exception {
        assertEquals(200, call("GET", "/actuator/health", null).getStatus());
        assertEquals(200, call("GET", "/actuator/health/liveness", null).getStatus());
    }

    @Test
    void pathThatOnlyMentionsHealthIsNotOpen() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call(filter, "GET", "/api/reasoning/jobs/x/actuator/health", null, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void emptyConfiguredTokenRefusesEverythingInsteadOfAcceptingAnything() throws Exception {
        InternalTokenFilter noToken = new InternalTokenFilter("");

        assertEquals(401, call(noToken, "GET", "/api/reasoning/jobs/j1", "", new MockFilterChain()).getStatus());
        assertEquals(401, call(noToken, "GET", "/api/reasoning/jobs/j1", null, new MockFilterChain()).getStatus());
    }
}
