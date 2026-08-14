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

    // Billing mutation endpoints — financial operations, per-user stricter limit
    private static final int BILLING_CAPACITY = 15;
    private static final Duration BILLING_REFILL_DURATION = Duration.ofMinutes(1);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

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
        } else if (isBillingMutationEndpoint(request)) {
            bucket = buckets.computeIfAbsent(clientIp + ":billing", k -> createBillingBucket());
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
     * Billing mutation endpoints — financial operations warrant a tighter per-IP limit
     * than the general API bucket (15/min vs 100/min).
     */
    private boolean isBillingMutationEndpoint(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return false;
        String uri = request.getRequestURI();
        return uri.startsWith("/api/billing/") && !uri.equals("/api/billing/webhook");
    }

    /**
     * Create bucket for billing mutation endpoints
     */
    private Bucket createBillingBucket() {
        Bandwidth limit = Bandwidth.classic(BILLING_CAPACITY, Refill.intervally(BILLING_CAPACITY, BILLING_REFILL_DURATION));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * Get client IP address from the request.
     * Uses X-Forwarded-For only when the direct connection comes from a known private/loopback
     * address (i.e. a trusted reverse proxy). Taking the raw first XFF value is spoofable —
     * an attacker can set "X-Forwarded-For: 1.2.3.4" to bypass per-IP rate limiting.
     */
    private String getClientIP(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return remoteAddr;
        }
        // Only trust the XFF header when the TCP connection is from a private/loopback address
        // (meaning a legitimate reverse proxy is in front, not the public internet).
        if (isTrustedProxy(remoteAddr)) {
            // Take the last entry added by our trusted proxy — not the user-controlled first entry.
            String[] parts = xfHeader.split(",");
            return parts[parts.length - 1].trim();
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String addr) {
        return addr != null && (
                addr.startsWith("10.")         ||
                addr.startsWith("172.16.")     || addr.startsWith("172.17.") ||
                addr.startsWith("172.18.")     || addr.startsWith("172.19.") ||
                addr.startsWith("172.20.")     || addr.startsWith("172.21.") ||
                addr.startsWith("172.22.")     || addr.startsWith("172.23.") ||
                addr.startsWith("172.24.")     || addr.startsWith("172.25.") ||
                addr.startsWith("172.26.")     || addr.startsWith("172.27.") ||
                addr.startsWith("172.28.")     || addr.startsWith("172.29.") ||
                addr.startsWith("172.30.")     || addr.startsWith("172.31.") ||
                addr.startsWith("192.168.")    ||
                addr.equals("127.0.0.1")       ||
                addr.equals("0:0:0:0:0:0:0:1") || addr.equals("::1")
        );
    }
}
