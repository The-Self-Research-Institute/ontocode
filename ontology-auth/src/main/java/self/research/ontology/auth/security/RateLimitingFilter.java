package self.research.ontology.auth.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting filter to prevent abuse
 * Implements token bucket algorithm with per-IP rate limiting
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    @Value("${security.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${security.rate-limit.requests-per-minute:100}")
    private int requestsPerMinute;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    // Default: 100 requests per minute per IP
    private static final int CAPACITY = 100;
    private static final Duration REFILL_DURATION = Duration.ofMinutes(1);

    // Stricter limits for authentication endpoints
    private static final int AUTH_CAPACITY = 10;
    private static final Duration AUTH_REFILL_DURATION = Duration.ofMinutes(1);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // Desktop mode / disabled: skip rate limiting entirely
        if (!rateLimitEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIP(request);
        String requestUri = request.getRequestURI();

        // Determine rate limit based on endpoint
        Bucket bucket;
        if (isAuthEndpoint(requestUri)) {
            bucket = buckets.computeIfAbsent(clientIp + ":auth", k -> createAuthBucket());
        } else {
            bucket = buckets.computeIfAbsent(clientIp, k -> createBucket());
        }

        // Try to consume a token
        if (bucket.tryConsume(1)) {
            // Add rate limit headers
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(bucket.getAvailableTokens()));
            filterChain.doFilter(request, response);
        } else {
            // Rate limit exceeded
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json");
            response.addHeader("X-Rate-Limit-Retry-After-Seconds", "60");
            response.getWriter().write("{\"error\":\"Rate limit exceeded. Please try again later.\"}");
        }
    }

    /**
     * Create bucket for regular endpoints
     */
    private Bucket createBucket() {
        Bandwidth limit = Bandwidth.classic(CAPACITY, Refill.intervally(CAPACITY, REFILL_DURATION));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * Create bucket for authentication endpoints (stricter)
     */
    private Bucket createAuthBucket() {
        Bandwidth limit = Bandwidth.classic(AUTH_CAPACITY, Refill.intervally(AUTH_CAPACITY, AUTH_REFILL_DURATION));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * Check if the endpoint is an authentication endpoint
     */
    private boolean isAuthEndpoint(String uri) {
        return uri.contains("/auth/login") || 
               uri.contains("/auth/register") || 
               uri.contains("/auth/forgot-password") ||
               uri.contains("/auth/reset-password");
    }

    /**
     * Get client IP address, considering proxy headers
     */
    private String getClientIP(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
