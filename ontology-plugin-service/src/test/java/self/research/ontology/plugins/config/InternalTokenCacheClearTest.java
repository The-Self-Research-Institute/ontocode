package self.research.ontology.plugins.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalTokenCacheClearTest {

    private static final String JWT_SECRET = "b250b2NvZGUtZGVza3RvcC1qd3Qtc2VjcmV0LWtleS12MQ==";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private JwtAuthenticationFilter filterConfiguredWith(String configuredToken) {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtSecret", JWT_SECRET);
        ReflectionTestUtils.setField(filter, "desktopMode", false);
        ReflectionTestUtils.setField(filter, "internalToken", configuredToken);
        return filter;
    }

    private Authentication authenticationSeenDownstream(JwtAuthenticationFilter filter, String method, String uri, String presentedToken) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("X-Ontocode-Internal-Token", presentedToken);
        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }

    @Test
    void editorsCacheClearWithTheDefaultTokenMustBeAuthenticated() throws Exception {
        Authentication auth = authenticationSeenDownstream(filterConfiguredWith("ontocode-internal"),
                "POST", "/api/reasoner/clear-cache/proj-1", "ontocode-internal");
        assertNotNull(auth, "Editor's cache-clear call with the default internal token (what default deployments send) "
                + "is NOT authenticated, so /api/reasoner/** answers 403 and the plugin cache is never cleared");
    }

    @Test
    void cacheClearWithANonDefaultTokenIsAuthenticated() throws Exception {
        Authentication auth = authenticationSeenDownstream(filterConfiguredWith("a-real-random-token-7f3c"),
                "POST", "/api/reasoner/clear-cache/proj-1", "a-real-random-token-7f3c");
        assertNotNull(auth, "non-default token should authenticate the cache-clear call");
    }

    @Test
    void internalTokenNeverAuthenticatesOtherPluginRoutes() throws Exception {
        assertNull(authenticationSeenDownstream(filterConfiguredWith("ontocode-internal"),
                "POST", "/api/plugins", "ontocode-internal"), "internal token must not log in to /api/plugins");
        assertNull(authenticationSeenDownstream(filterConfiguredWith("a-real-random-token-7f3c"),
                "POST", "/api/plugins", "a-real-random-token-7f3c"), "internal token must not log in to /api/plugins");
        assertNull(authenticationSeenDownstream(filterConfiguredWith("a-real-random-token-7f3c"),
                "GET", "/api/reasoner/proj-1/inferred-axioms", "a-real-random-token-7f3c"), "internal token must not log in to reasoning endpoints");
    }
}
