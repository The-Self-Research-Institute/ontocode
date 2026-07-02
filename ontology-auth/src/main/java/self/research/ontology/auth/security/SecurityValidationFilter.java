package self.research.ontology.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Security filter to prevent SQL injection, XSS, and path traversal attacks
 */
@Component
public class SecurityValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityValidationFilter.class);

    // SQL Injection patterns
    // Note: bare "--" is NOT flagged because project IDs use double-dashes (e.g. proj-abc--uuid).
    // Only flag "--" when followed by a space and SQL keyword (the actual SQL comment attack pattern).
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "('.*(\\b(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|SCRIPT)\\b).*')|" +
        "(;\\s*(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|SCRIPT)\\s+)|" +
        "(--\\s+(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|OR|AND))|" +
        "(/\\*.*\\*/)",
        Pattern.CASE_INSENSITIVE
    );

    // XSS patterns
    private static final Pattern XSS_PATTERN = Pattern.compile(
        "<script|</script|javascript:|onerror=|onload=|<iframe|</iframe|eval\\(|alert\\(",
        Pattern.CASE_INSENSITIVE
    );

    // Path traversal patterns
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
        "\\.\\./|\\.\\\\|%2e%2e/|%2e%2e\\\\|\\.\\.%2f|\\.\\.%5c",
        Pattern.CASE_INSENSITIVE
    );

    // Null byte injection
    private static final Pattern NULL_BYTE_PATTERN = Pattern.compile("%00|\\x00");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // Skip validation for health check and actuator endpoints
        String requestUri = request.getRequestURI();
        if (requestUri.contains("/actuator/") || requestUri.equals("/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Validate query parameters
        if (request.getQueryString() != null) {
            String queryString = request.getQueryString();
            
            if (SQL_INJECTION_PATTERN.matcher(queryString).find()) {
                log.warn("SQL Injection attempt detected in query string: {}", sanitizeLog(queryString));
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid request: potential SQL injection detected\"}");
                return;
            }

            if (XSS_PATTERN.matcher(queryString).find()) {
                log.warn("XSS attempt detected in query string: {}", sanitizeLog(queryString));
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid request: potential XSS detected\"}");
                return;
            }

            if (PATH_TRAVERSAL_PATTERN.matcher(queryString).find()) {
                log.warn("Path traversal attempt detected in query string: {}", sanitizeLog(queryString));
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid request: potential path traversal detected\"}");
                return;
            }

            if (NULL_BYTE_PATTERN.matcher(queryString).find()) {
                log.warn("Null byte injection attempt detected in query string: {}", sanitizeLog(queryString));
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid request: null byte injection detected\"}");
                return;
            }
        }

        // Validate request path
        if (PATH_TRAVERSAL_PATTERN.matcher(requestUri).find()) {
            log.warn("Path traversal attempt detected in URI: {}", sanitizeLog(requestUri));
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid request path\"}");
            return;
        }

        // Validate request headers for XSS
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && XSS_PATTERN.matcher(userAgent).find()) {
            log.warn("XSS attempt detected in User-Agent header");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid User-Agent header\"}");
            return;
        }

        String referer = request.getHeader("Referer");
        if (referer != null && XSS_PATTERN.matcher(referer).find()) {
            log.warn("XSS attempt detected in Referer header");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid Referer header\"}");
            return;
        }

        // Continue with the filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Sanitize log messages so:
     * <ol>
     *   <li>CRLF can't be used to forge new log lines (log-injection),</li>
     *   <li>values of sensitive query parameters never appear in the log,
     *       even when this filter is logging the offending input as part
     *       of a security alert. Names of parameters are kept so that
     *       triage can still tell <em>which</em> sensitive params were
     *       sent &mdash; just not their contents.</li>
     * </ol>
     *
     * Kept in lock-step with
     * {@code PerformanceLoggingInterceptor.SENSITIVE_PARAM_NEEDLES}.
     */
    private String sanitizeLog(String input) {
        if (input == null) return "";
        String noCrlf = input.replaceAll("[\n\r]", "_");
        return redactSensitiveParams(noCrlf);
    }

    private static final String[] SENSITIVE_PARAM_NEEDLES = {
            "token", "password", "passwd", "secret", "apikey", "api_key",
            "key", "auth", "authorization", "code", "signature", "sig",
            "otp", "jwt", "session", "email", "card", "cvv", "ssn"
    };
    private static final String REDACTED = "***REDACTED***";

    private static String redactSensitiveParams(String query) {
        if (query == null || query.isEmpty()) return query;
        // Only treat it as a query string if it looks like one. Keeps the
        // method safe to call on non-query inputs (e.g. Referer header).
        if (query.indexOf('=') < 0) return query;
        String[] parts = query.split("&");
        StringBuilder out = new StringBuilder(query.length());
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
