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
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "('.*(\\b(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|SCRIPT)\\b).*')|" +
        "(;\\s*(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|SCRIPT)\\s+)|" +
        "(--)|(/\\*.*\\*/)",
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
     * Sanitize log messages to prevent log injection
     */
    private String sanitizeLog(String input) {
        if (input == null) return "";
        return input.replaceAll("[\n\r]", "_");
    }
}
