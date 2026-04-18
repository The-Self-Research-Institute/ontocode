package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Intercepts all HTTP requests and logs performance metrics.
 * Logs to the dedicated PERFORMANCE logger (writes to performance.log).
 *
 * Captures: method, URI, status, duration, query params, content length.
 */
@Component
public class PerformanceLoggingInterceptor implements HandlerInterceptor {

    private static final Logger perfLog = LoggerFactory.getLogger("PERFORMANCE");
    private static final Logger errorLog = LoggerFactory.getLogger(PerformanceLoggingInterceptor.class);

    private static final String START_TIME_ATTR = "perf_startTime";
    private static final long SLOW_THRESHOLD_MS = 1000;
    private static final long VERY_SLOW_THRESHOLD_MS = 5000;

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
        String query = request.getQueryString();
        String fullPath = query != null ? uri + "?" + query : uri;
        long contentLength = request.getContentLengthLong();

        // Categorize speed
        String speedTag;
        if (durationMs >= VERY_SLOW_THRESHOLD_MS) {
            speedTag = "VERY_SLOW";
        } else if (durationMs >= SLOW_THRESHOLD_MS) {
            speedTag = "SLOW";
        } else {
            speedTag = "OK";
        }

        // Performance log line: easily grep-able format
        perfLog.info("[PERF] {} {} {} status={} duration={}ms size={} tag={}",
                method, fullPath, buildProjectId(uri), status, durationMs,
                contentLength > 0 ? contentLength + "B" : "-", speedTag);

        // Also log slow requests as warnings in error log
        if (durationMs >= SLOW_THRESHOLD_MS) {
            errorLog.warn("[SLOW_REQUEST] {} {} took {}ms (status={})", method, fullPath, durationMs, status);
        }

        // Log exceptions
        if (ex != null) {
            errorLog.error("[REQUEST_EXCEPTION] {} {} failed after {}ms", method, fullPath, durationMs, ex);
        }

        // Log error status codes
        if (status >= 500) {
            errorLog.error("[SERVER_ERROR] {} {} returned {} after {}ms", method, fullPath, status, durationMs);
        } else if (status >= 400) {
            errorLog.warn("[CLIENT_ERROR] {} {} returned {} after {}ms", method, fullPath, status, durationMs);
        }
    }

    /**
     * Extract projectId from URI for easier log filtering.
     * URIs look like: /api/ontology/classes/top-level/{projectId}
     */
    private String buildProjectId(String uri) {
        if (uri == null) return "";
        // Find the last path segment which is typically the projectId
        String[] parts = uri.split("/");
        if (parts.length >= 5 && uri.startsWith("/api/")) {
            return "project=" + parts[parts.length - 1];
        }
        return "";
    }
}
