package self.research.ontology.owlEditor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;

/**
 * Populates SLF4J MDC with userId and projectId for every request.
 * All downstream log statements automatically include these fields,
 * allowing log filtering by user or file when debugging reported issues.
 *
 * userId  — extracted from JWT claim "userId" (MongoDB ObjectId)
 * userName— extracted from JWT "sub" (username/email)
 * projectId — extracted from the URI path segment starting with "proj-"
 */
@Component
@Order(1)
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            extractProjectId(request.getRequestURI());
            extractUserFromJwt(request.getHeader("Authorization"));
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private void extractProjectId(String uri) {
        if (uri == null) return;
        for (String segment : uri.split("/")) {
            if (segment.startsWith("proj-")) {
                MDC.put("projectId", segment);
                return;
            }
        }
    }

    private void extractUserFromJwt(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return;
        String token = authHeader.substring(7);
        String[] parts = token.split("\\.");
        if (parts.length != 3) return;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = MAPPER.readTree(decoded);
            if (claims.has("userId")) {
                MDC.put("userId", claims.get("userId").asText());
            }
            if (claims.has("sub")) {
                MDC.put("userName", claims.get("sub").asText());
            }
        } catch (Exception ignored) {
            // malformed token — MDC fields simply stay empty
        }
    }
}
