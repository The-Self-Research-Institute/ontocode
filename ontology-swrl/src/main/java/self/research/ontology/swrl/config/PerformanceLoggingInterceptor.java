package self.research.ontology.swrl.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Intercepts all HTTP requests and logs performance metrics.
 * Logs to the dedicated PERFORMANCE logger (writes to performance.log).
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

        String speedTag;
        if (durationMs >= VERY_SLOW_THRESHOLD_MS) {
            speedTag = "VERY_SLOW";
        } else if (durationMs >= SLOW_THRESHOLD_MS) {
            speedTag = "SLOW";
        } else {
            speedTag = "OK";
        }

        perfLog.info("[PERF] {} {} status={} duration={}ms tag={}", method, fullPath, status, durationMs, speedTag);

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
}
