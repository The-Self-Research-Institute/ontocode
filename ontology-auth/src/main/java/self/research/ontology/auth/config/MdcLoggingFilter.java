package self.research.ontology.auth.config;

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

@Component("authMdcLoggingFilter")
@Order(1)
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            extractUserFromJwt(request.getHeader("Authorization"));
            extractWorkspaceFromRequest(request);
            extractProjectFromUri(request.getRequestURI());
            MDC.put("ctx", buildContext());
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
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

            String email = textClaim(claims, "email");
            if (email == null) email = textClaim(claims, "sub");
            if (email != null) MDC.put("userEmail", email);

            String userId = textClaim(claims, "userId");
            if (userId != null) MDC.put("userId", userId);

            String wsId = textClaim(claims, "workspaceId");
            if (wsId != null) MDC.put("workspaceId", wsId);
        } catch (Exception ignored) {

        }
    }

    private void extractWorkspaceFromRequest(HttpServletRequest request) {
        if (MDC.get("workspaceId") != null) return;
        String fromQuery = request.getParameter("workspaceId");
        if (fromQuery != null && !fromQuery.isBlank()) {
            MDC.put("workspaceId", fromQuery);
            return;
        }

        String uri = request.getRequestURI();
        if (uri == null) return;
        String[] segments = uri.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("workspaces".equals(segments[i])) {
                String next = segments[i + 1];
                if (!next.isEmpty() && !"my".equals(next) && !"create".equals(next)) {
                    MDC.put("workspaceId", next);
                    return;
                }
            }
        }
    }

    private void extractProjectFromUri(String uri) {
        if (uri == null) return;
        for (String seg : uri.split("/")) {
            if (seg.startsWith("proj-")) {
                int dash = seg.indexOf("--");
                MDC.put("projectId", dash > 0 ? seg.substring(0, dash) : seg);
                return;
            }
        }
    }

    private String buildContext() {
        String email = MDC.get("userEmail");
        String projectId = MDC.get("projectId");
        String workspaceId = MDC.get("workspaceId");

        StringBuilder sb = new StringBuilder();
        if (email != null && !email.isBlank()) {
            sb.append("email=").append(email);
        } else {
            sb.append("(anon)");
        }

        if (projectId != null && !projectId.isBlank()) {
            sb.append(" proj=").append(projectId);
        } else if (workspaceId != null && !workspaceId.isBlank()) {
            sb.append(" ws=").append(workspaceId);
        }
        return sb.toString();
    }

    private static String textClaim(JsonNode claims, String key) {
        if (!claims.has(key)) return null;
        JsonNode node = claims.get(key);
        if (node == null || node.isNull()) return null;
        String text = node.asText();
        return (text == null || text.isBlank()) ? null : text;
    }
}
