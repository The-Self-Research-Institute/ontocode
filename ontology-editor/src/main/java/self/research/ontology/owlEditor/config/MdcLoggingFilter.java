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
 * Populates SLF4J MDC for every request so logs can be correlated by
 * user / workspace / project / file.
 *
 * <p>Fields written to MDC:
 * <ul>
 *   <li>{@code userEmail}  — JWT {@code email} claim (or {@code sub} as fallback).
 *       This is what operators search by &mdash; "user id means user email".</li>
 *   <li>{@code workspaceId} — JWT claim or {@code ?workspaceId=} query param.</li>
 *   <li>{@code projectId}   — URI path segment starting with {@code proj-},
 *       de-mangled if it carried an embedded file id (e.g.
 *       {@code proj-abc--file-xyz} &rarr; projectId=proj-abc, fileId=file-xyz).</li>
 *   <li>{@code fileId}      — URI path segment starting with {@code file-},
 *       or the suffix after {@code --} in the projectId, or {@code ?fileId=}.</li>
 *   <li>{@code ctx}         — single rendered identifier string for the log
 *       pattern, computed with a cascade so the most specific identifier we
 *       have is always shown:
 *       <pre>
 *       email + file       most specific
 *       email + project    file unknown
 *       email + workspace  project unknown
 *       email              nothing else known
 *       (anon)             user not authenticated
 *       </pre></li>
 * </ul>
 *
 * <p>The individual fields are still in MDC alongside {@code ctx} so that
 * dashboards / grep can filter by exact id when needed
 * (e.g. {@code zgrep 'projectId=proj-abc'}).
 */
@Component
@Order(1)
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            extractUserFromJwt(request.getHeader("Authorization"));
            extractWorkspaceFromRequest(request);
            extractProjectAndFileFromUri(request.getRequestURI());
            extractFileFromQuery(request);
            // Cascade rendered last so it sees every other field.
            MDC.put("ctx", buildContext());
            filterChain.doFilter(request, response);
        } finally {
            // Clear everything we wrote so async appenders don't leak MDC
            // across requests (the ASYNC appender copies MDC at enqueue time
            // — clearing here is safe).
            MDC.clear();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Extraction
    // ─────────────────────────────────────────────────────────────────

    private void extractUserFromJwt(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return;
        String token = authHeader.substring(7);
        String[] parts = token.split("\\.");
        if (parts.length != 3) return;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = MAPPER.readTree(decoded);

            // Email is the operator-facing identity. Prefer the explicit
            // `email` claim; fall back to `sub` (which is the username, but
            // many of our users sign up with their email as the username).
            String email = textClaim(claims, "email");
            if (email == null) email = textClaim(claims, "sub");
            if (email != null) MDC.put("userEmail", email);

            // Stable internal id, kept for joining against Mongo if needed.
            String userId = textClaim(claims, "userId");
            if (userId != null) MDC.put("userId", userId);

            // Workspace from the JWT (set when the user has selected one).
            String wsId = textClaim(claims, "workspaceId");
            if (wsId != null) MDC.put("workspaceId", wsId);
        } catch (Exception ignored) {
            // malformed token — leave MDC fields blank, no logging here to
            // avoid recursion on the very first log statement.
        }
    }

    private void extractWorkspaceFromRequest(HttpServletRequest request) {
        if (MDC.get("workspaceId") != null) return; // JWT already populated
        String fromQuery = request.getParameter("workspaceId");
        if (fromQuery != null && !fromQuery.isBlank()) {
            MDC.put("workspaceId", fromQuery);
        }
    }

    /**
     * URI conventions used by ontology-editor:
     *   /api/ontology/{projectId}/...                     ← project-scoped
     *   /api/ontology/{projectId}--{fileId}/...           ← project + file
     *   /api/projects/{projectId}/files/{fileId}/...      ← projects controller
     *   /api/citations/{projectId}/insert                 ← citations
     *
     * Project ids are conventionally prefixed with {@code proj-} and file
     * ids with {@code file-}. We also recognise the {@code --} separator
     * used by the editor when a single path segment carries both.
     */
    private void extractProjectAndFileFromUri(String uri) {
        if (uri == null) return;
        String[] segments = uri.split("/");
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isEmpty()) continue;

            // Combined "<project>--<file>" segment — split it.
            if (seg.startsWith("proj-") && seg.contains("--")) {
                int dash = seg.indexOf("--");
                String projectPart = seg.substring(0, dash);
                String filePart = seg.substring(dash + 2);
                MDC.put("projectId", projectPart);
                if (!filePart.isEmpty()) MDC.put("fileId", filePart);
                continue;
            }

            if (seg.startsWith("proj-")) {
                MDC.put("projectId", seg);
                continue;
            }

            if (seg.startsWith("file-")) {
                MDC.put("fileId", seg);
                continue;
            }

            // .../files/{fileId}/...
            if ("files".equals(seg) && i + 1 < segments.length) {
                String next = segments[i + 1];
                if (!next.isEmpty() && MDC.get("fileId") == null) {
                    MDC.put("fileId", next);
                }
            }
        }
    }

    private void extractFileFromQuery(HttpServletRequest request) {
        if (MDC.get("fileId") != null) return;
        String fromQuery = request.getParameter("fileId");
        if (fromQuery != null && !fromQuery.isBlank()) {
            MDC.put("fileId", fromQuery);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Cascade
    // ─────────────────────────────────────────────────────────────────

    /**
     * Render the cascading identifier string. Picks the most specific
     * identifier available and pairs it with the user's email so that one
     * grep is enough to find every line for a user's most recent activity.
     *
     * Examples:
     *   email=alice@x.com file=file-123
     *   email=alice@x.com proj=proj-abc
     *   email=alice@x.com ws=ws-xyz
     *   email=alice@x.com
     *   (anon) ws=ws-xyz                  ← rare: pre-auth path with workspace
     *   (anon)                            ← truly unauthenticated
     */
    private String buildContext() {
        String email = MDC.get("userEmail");
        String fileId = MDC.get("fileId");
        String projectId = MDC.get("projectId");
        String workspaceId = MDC.get("workspaceId");

        StringBuilder sb = new StringBuilder();
        if (email != null && !email.isBlank()) {
            sb.append("email=").append(email);
        } else {
            sb.append("(anon)");
        }

        // Append the most specific scope we have. Cascading:
        //   file > project > workspace.
        if (fileId != null && !fileId.isBlank()) {
            sb.append(" file=").append(fileId);
        } else if (projectId != null && !projectId.isBlank()) {
            sb.append(" proj=").append(projectId);
        } else if (workspaceId != null && !workspaceId.isBlank()) {
            sb.append(" ws=").append(workspaceId);
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private static String textClaim(JsonNode claims, String key) {
        if (!claims.has(key)) return null;
        JsonNode node = claims.get(key);
        if (node == null || node.isNull()) return null;
        String text = node.asText();
        return (text == null || text.isBlank()) ? null : text;
    }
}
