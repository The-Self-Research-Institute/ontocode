package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Component
public class PerformanceLoggingInterceptor implements HandlerInterceptor {

    private static final Logger perfLog = LoggerFactory.getLogger("PERFORMANCE");
    private static final Logger errorLog = LoggerFactory.getLogger(PerformanceLoggingInterceptor.class);

    private static final String START_TIME_ATTR = "perf_startTime";
    private static final long SLOW_THRESHOLD_MS = 1000;
    private static final long VERY_SLOW_THRESHOLD_MS = 5000;

    private static final Set<String> SENSITIVE_PARAM_NEEDLES = Set.of(
            "token", "password", "passwd", "secret", "apikey", "api_key",
            "key", "auth", "authorization", "code", "signature", "sig",
            "otp", "jwt", "session", "email", "card", "cvv", "ssn"
    );
    private static final String REDACTED = "***REDACTED***";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        Long startNano = (Long) request.getAttribute(START_TIME_ATTR);
        if (startNano == null) return;

        long durationMs = (System.nanoTime() - startNano) / 1_000_000;
        int status = response.getStatus();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String safeQuery = redactQuery(request.getQueryString());
        String fullPath = safeQuery != null ? uri + "?" + safeQuery : uri;
        long contentLength = request.getContentLengthLong();

        String speedTag;
        if (durationMs >= VERY_SLOW_THRESHOLD_MS) {
            speedTag = "VERY_SLOW";
        } else if (durationMs >= SLOW_THRESHOLD_MS) {
            speedTag = "SLOW";
        } else {
            speedTag = "OK";
        }

        String projectId = MDC.get("projectId");
        perfLog.info("[PERF] {} {} project={} status={} duration={}ms size={} tag={}",
                method, fullPath, projectId != null ? projectId : "-", status, durationMs,
                contentLength > 0 ? contentLength + "B" : "-", speedTag);

        if (durationMs >= SLOW_THRESHOLD_MS) {
            errorLog.warn("[SLOW_REQUEST] {} {} took {}ms (status={})", method, fullPath, durationMs, status);
        }
        if (ex != null) {
            errorLog.error("[REQUEST_EXCEPTION] {} {} failed after {}ms", method, fullPath, durationMs, ex);
        }
        if (status >= 500) {
            errorLog.error("[SERVER_ERROR] {} {} returned {} after {}ms", method, fullPath, status, durationMs);
        } else if (status >= 400) {
            errorLog.warn("[CLIENT_ERROR] {} {} returned {} after {}ms", method, fullPath, status, durationMs);
        }
    }

    static String redactQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        String[] parts = rawQuery.split("&");
        StringBuilder out = new StringBuilder(rawQuery.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('&');
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq < 0) {

                out.append(part);
                continue;
            }
            String name = part.substring(0, eq);
            String lowered = name.toLowerCase();
            boolean sensitive = false;
            for (String needle : SENSITIVE_PARAM_NEEDLES) {
                if (lowered.contains(needle)) {
                    sensitive = true;
                    break;
                }
            }
            out.append(name).append('=');
            out.append(sensitive ? REDACTED : part.substring(eq + 1));
        }
        return out.toString();
    }
}
