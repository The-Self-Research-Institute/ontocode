package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SparqlQueryContextInterceptorTest {

    private final SparqlQueryContextInterceptor interceptor = new SparqlQueryContextInterceptor();

    @AfterEach
    void clear() {
        SparqlQueryContext.clear();
    }

    private static MockHttpServletRequest withToken(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ontology/p1/classes");
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"owner@x.org\",\"userId\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void theTokensUserIdWinsOverAUserIdInTheQuery() {
        MockHttpServletRequest request = withToken("u-owner");
        request.setParameter("userId", "u-victim");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals("u-owner", SparqlQueryContext.getUserId());
    }

    @Test
    void theTokensUserIdWinsOverTheHeader() {
        MockHttpServletRequest request = withToken("u-owner");
        request.addHeader("X-Ontocode-User-Id", "u-victim");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals("u-owner", SparqlQueryContext.getUserId());
    }

    @Test
    void withoutATokenTheQueryParameterIsStillUsed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ontology/p1/classes");
        request.setParameter("userId", "u-claimed");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals("u-claimed", SparqlQueryContext.getUserId());
    }

    @Test
    void theDesktopAppFallsBackToItsLocalUser() {
        ReflectionTestUtils.setField(interceptor, "desktopMode", true);

        interceptor.preHandle(new MockHttpServletRequest("GET", "/api/ontology/p1/classes"),
                new MockHttpServletResponse(), new Object());

        assertEquals("desktop-user-local", SparqlQueryContext.getUserId());
    }
}
