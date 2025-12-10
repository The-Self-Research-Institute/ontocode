package self.research.ontology.auth.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting service to prevent brute force attacks.
 * Uses Bucket4j token bucket algorithm.
 */
@Service
public class RateLimitService {

    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    /**
     * Get or create a rate limit bucket for a given key (IP address).
     * Limit: 5 requests per minute
     */
    public Bucket resolveBucket(String key) {
        return cache.computeIfAbsent(key, k -> createNewBucket());
    }

    private Bucket createNewBucket() {
        // Allow 5 requests per minute
        Bandwidth limit = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * Clear rate limit for a specific key (e.g., after successful login)
     */
    public void clearLimit(String key) {
        cache.remove(key);
    }

    /**
     * Periodic cleanup of old entries (call this from scheduled task)
     */
    public void cleanup() {
        // In production, implement more sophisticated cleanup
        // For now, we let ConcurrentHashMap handle it
        if (cache.size() > 10000) {
            cache.clear();
        }
    }
}