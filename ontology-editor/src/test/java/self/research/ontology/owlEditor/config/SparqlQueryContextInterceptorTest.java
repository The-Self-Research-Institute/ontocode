package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

import javax.crypto.SecretKey;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlQueryContextInterceptorTest {

    private final SecretKey key = Jwts.SIG.HS256.key().build();

    @AfterEach
    void clear() {
        SparqlQueryContext.clear();
    }

    private SparqlQueryContextInterceptor interceptor(boolean desktopMode) {
        SparqlQueryContextInterceptor interceptor = new SparqlQueryContextInterceptor();
        ReflectionTestUtils.setField(interceptor, "desktopMode", desktopMode);
        return interceptor;
    }

    private String token(Map<String, Object> claims) {
        return "Bearer " + Jwts.builder().claims(claims).signWith(key).compact();
    }

    private MockHttpServletRequest draftRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sparql/query/p1");
        request.addParameter("draft", "true");
        return request;
    }

    @Test
    void webIgnoresAnotherUsersIdInParameterAndHeader() {
        MockHttpServletRequest request = draftRequest();
        request.addHeader("Authorization", token(Map.of("userId", "user-b")));
        request.addParameter("userId", "user-a");
        request.addHeader("X-Ontocode-User-Id", "user-a");

        interceptor(false).preHandle(request, new MockHttpServletResponse(), null);

        assertEquals("user-b", SparqlQueryContext.getUserId());
        assertTrue(SparqlQueryContext.wantsDraft());
    }

    @Test
    void webWithoutTokenGetsNoDraftUser() {
        MockHttpServletRequest request = draftRequest();
        request.addParameter("userId", "user-a");
        request.addHeader("X-Ontocode-User-Id", "user-a");

        interceptor(false).preHandle(request, new MockHttpServletResponse(), null);

        assertNull(SparqlQueryContext.getUserId());
    }

    @Test
    void webFallsBackToTokenEmailWhenUserIdClaimIsMissing() {
        MockHttpServletRequest request = draftRequest();
        request.addHeader("Authorization", token(Map.of("email", "b@example.com")));
        request.addParameter("userId", "user-a");

        interceptor(false).preHandle(request, new MockHttpServletResponse(), null);

        assertEquals("b@example.com", SparqlQueryContext.getUserId());
    }

    @Test
    void desktopUsesParameterThenDefaultUser() {
        MockHttpServletRequest withParam = draftRequest();
        withParam.addParameter("userId", "desktop-user-local");
        interceptor(true).preHandle(withParam, new MockHttpServletResponse(), null);
        assertEquals("desktop-user-local", SparqlQueryContext.getUserId());

        SparqlQueryContext.clear();
        interceptor(true).preHandle(draftRequest(), new MockHttpServletResponse(), null);
        assertEquals("desktop-user-local", SparqlQueryContext.getUserId());
    }
}
