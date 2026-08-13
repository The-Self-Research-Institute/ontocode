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

@Component
public class GatewayAccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccessLogFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNs = System.nanoTime();
        ServerHttpRequest req = exchange.getRequest();

        Identifiers ids = extract(req);

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

    @Override
    public int getOrder() {
        return -200;
    }

    private Identifiers extract(ServerHttpRequest req) {
        Identifiers ids = new Identifiers();

        List<String> auth = req.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (auth != null && !auth.isEmpty()) {
            String header = auth.get(0);
            if (header != null && header.startsWith("Bearer ")) {
                decodeJwt(header.substring(7), ids);
            }
        }

        if (ids.workspaceId == null) {
            String fromQuery = singleQueryParam(req, "workspaceId");
            if (fromQuery != null) ids.workspaceId = fromQuery;
        }

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

                if ("workspaces".equals(seg) && i + 1 < segments.length
                        && ids.workspaceId == null) {
                    String next = segments[i + 1];
                    if (!next.isEmpty() && !"my".equals(next) && !"create".equals(next)) {
                        ids.workspaceId = next;
                    }
                }
            }
        }

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
