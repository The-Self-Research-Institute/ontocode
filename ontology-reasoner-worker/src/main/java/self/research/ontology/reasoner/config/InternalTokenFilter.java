package self.research.ontology.reasoner.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class InternalTokenFilter implements Filter {

    public static final String HEADER = "X-Ontocode-Internal-Token";

    private final byte[] expected;

    public InternalTokenFilter(String expectedToken) {
        this.expected = expectedToken == null || expectedToken.isEmpty()
                ? null
                : expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if (isHealth(req.getRequestURI()) || presentedMatches(req.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse res = (HttpServletResponse) response;
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"success\":false,\"error\":\"Unauthorized\"}");
    }

    private static boolean isHealth(String uri) {
        return uri != null && (uri.equals("/actuator/health") || uri.startsWith("/actuator/health/"));
    }

    private boolean presentedMatches(String presented) {
        return expected != null && presented != null
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
