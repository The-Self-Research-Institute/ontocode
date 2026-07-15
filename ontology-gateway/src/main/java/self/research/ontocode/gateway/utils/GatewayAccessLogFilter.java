package self.research.ontocode.gateway.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;

/**
 * Reactive access log + MDC enrichment for the gateway.
 *
 * <p>Spring Cloud Gateway is reactive, so a servlet-style
 * {@code OncePerRequestFilter} doesn't apply &mdash; ThreadLocal MDC isn't
 * propagated across Reactor schedulers. Instead we extract the
 * identifiers up-front, run the chain, and emit a single structured
 * access-log line at completion with MDC populated &mdash; that one log
 * call is on a known thread so MDC works for it.
 *
 * <p>Cascade rendered into the {@code ctx} field, identical to the
 * downstream services so a single grep correlates gateway and service
 * logs:
 * <pre>
 *   email + file       most specific
 *   email + project    file unknown
 *   email + workspace  project unknown
 *   email              nothing else known
 *   (anon)             unauthenticated
 * </pre>
 *
 * <p>Individual MDC fields ({@code userEmail}, {@code workspaceId},
 * {@code projectId}, {@code fileId}) are also set so structured-log
 * processors can filter by exact id.
 */
@Component
public class GatewayAccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccessLogFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNs = System.nanoTime();
        ServerHttpRequest req = exchange.getRequest();

        // Identifiers are pure functions of the incoming request, so
        // extract them once. They feed both the access-log line below
        // and (when present) downstream services' own MDC filters.
        Identifiers ids = extract(req);

        // Capture request metadata up-front so the doFinally callback
        // doesn't need to traverse the (possibly closed) request again.
        String reqCt = nullToDash(req.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        long reqLen = req.getHeaders().getContentLength();
        String reqLenStr = reqLen >= 0 ? reqLen + "B" : "-";

        return chain.filter(exchange).doFinally(signal -> {
            ServerHttpResponse res = exchange.getResponse();
            int status = res.getStatusCode() != null ? res.getStatusCode().value() : 0;
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

            String resCt = nullToDash(res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
            long resLen = res.getHeaders().getContentLength();
            String resLenStr = resLen >= 0 ? resLen + "B" : "-";

            // Set MDC just for this single log call; clear immediately
            // afterwards. We're on the doFinally thread, so MDC is
            // consistent here even though it wasn't across the chain.
            MDC.put("ctx", ids.context());
            if (ids.userEmail != null) MDC.put("userEmail", ids.userEmail);
            if (ids.workspaceId != null) MDC.put("workspaceId", ids.workspaceId);
            if (ids.projectId != null) MDC.put("projectId", ids.projectId);
            if (ids.fileId != null) MDC.put("fileId", ids.fileId);
            try {
                log.info("[REQ] {} {} req_ct={} req_len={} -> {} res_ct={} res_len={} ({}ms)",
                        req.getMethod(),
                        req.getURI().getRawPath(),
                        reqCt,
                        reqLenStr,
                        status,
                        resCt,
                        resLenStr,
                        elapsedMs);
            } finally {
                MDC.clear();
            }
        });
    }

    private static String nullToDash(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }

    /** Run before the auth filter so identifiers are derived from the
     *  incoming token, not from a possibly-rewritten downstream form. */
    @Override
    public int getOrder() {
        return -200;
    }

    // ─────────────────────────────────────────────────────────────────
    // Extraction
    // ─────────────────────────────────────────────────────────────────

    private Identifiers extract(ServerHttpRequest req) {
        Identifiers ids = new Identifiers();

        // 1. JWT: email + workspaceId from claims.
        List<String> auth = req.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (auth != null && !auth.isEmpty()) {
            String header = auth.get(0);
            if (header != null && header.startsWith("Bearer ")) {
                decodeJwt(header.substring(7), ids);
            }
        }

        // 2. Workspace from query param (covers paths that don't carry
        //    a JWT yet, e.g. /api/billing/webhook is irrelevant here but
        //    plan-pricing pre-auth calls might).
        if (ids.workspaceId == null) {
            String fromQuery = singleQueryParam(req, "workspaceId");
            if (fromQuery != null) ids.workspaceId = fromQuery;
        }

        // 3. Project / file from URI path. Same conventions as
        //    ontology-editor's MdcLoggingFilter:
        //      proj-x--file-y  → split into project + file
        //      proj-x          → project only
        //      file-x          → file only
        //      .../files/{id}  → file id by position
        String path = req.getURI().getRawPath();
        if (path != null) {
            String[] segments = path.split("/");
            for (int i = 0; i < segments.length; i++) {
                String seg = segments[i];
                if (seg.isEmpty()) continue;

                if (seg.startsWith("proj-") && seg.contains("--")) {
                    int dash = seg.indexOf("--");
                    ids.projectId = seg.substring(0, dash);
                    String filePart = seg.substring(dash + 2);
                    if (!filePart.isEmpty()) ids.fileId = filePart;
                    continue;
                }
                if (seg.startsWith("proj-")) {
                    ids.projectId = seg;
                    continue;
                }
                if (seg.startsWith("file-")) {
                    ids.fileId = seg;
                    continue;
                }
                if ("files".equals(seg) && i + 1 < segments.length
                        && ids.fileId == null) {
                    String next = segments[i + 1];
                    if (!next.isEmpty()) ids.fileId = next;
                }
                // /api/workspaces/{wsId}/...
                if ("workspaces".equals(seg) && i + 1 < segments.length
                        && ids.workspaceId == null) {
                    String next = segments[i + 1];
                    if (!next.isEmpty() && !"my".equals(next) && !"create".equals(next)) {
                        ids.workspaceId = next;
                    }
                }
            }
        }

        // 4. File id from query string fallback.
        if (ids.fileId == null) {
            String fromQuery = singleQueryParam(req, "fileId");
            if (fromQuery != null) ids.fileId = fromQuery;
        }

        return ids;
    }

    private void decodeJwt(String token, Identifiers ids) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = MAPPER.readTree(decoded);
            String email = textClaim(claims, "email");
            if (email == null) email = textClaim(claims, "sub");
            if (email != null) ids.userEmail = email;
            String wsId = textClaim(claims, "workspaceId");
            if (wsId != null) ids.workspaceId = wsId;
        } catch (Exception ignored) {
            // Malformed token — leave identifiers blank, no recursive log.
        }
    }

    private static String singleQueryParam(ServerHttpRequest req, String name) {
        List<String> values = req.getQueryParams().get(name);
        if (values == null || values.isEmpty()) return null;
        String v = values.get(0);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String textClaim(JsonNode claims, String key) {
        if (!claims.has(key)) return null;
        JsonNode node = claims.get(key);
        if (node == null || node.isNull()) return null;
        String text = node.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    // ─────────────────────────────────────────────────────────────────
    // Cascade
    // ─────────────────────────────────────────────────────────────────

    private static final class Identifiers {
        String userEmail;
        String workspaceId;
        String projectId;
        String fileId;

        String context() {
            StringBuilder sb = new StringBuilder();
            sb.append(userEmail != null && !userEmail.isBlank()
                    ? "email=" + userEmail
                    : "(anon)");
            if (fileId != null && !fileId.isBlank()) {
                sb.append(" file=").append(fileId);
            } else if (projectId != null && !projectId.isBlank()) {
                sb.append(" proj=").append(projectId);
            } else if (workspaceId != null && !workspaceId.isBlank()) {
                sb.append(" ws=").append(workspaceId);
            }
            return sb.toString();
        }
    }
}
