package self.research.ontology.owlEditor.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class AssistantProviderRateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long startMillis, int count) {
    }

    private final int maxRequests;
    private final long windowMillis;
    private final int sweepThreshold;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AssistantProviderRateLimiter(int maxRequests, Duration window, int sweepThreshold, Clock clock) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("maxRequests must be at least 1");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.sweepThreshold = Math.max(1, sweepThreshold);
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        long now = clock.millis();
        if (windows.size() >= sweepThreshold) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startMillis() >= windowMillis);
        }
        boolean[] allowed = new boolean[1];
        Window current = windows.compute(normalized, (k, existing) -> {
            if (existing == null || now - existing.startMillis() >= windowMillis) {
                allowed[0] = true;
                return new Window(now, 1);
            }
            if (existing.count() < maxRequests) {
                allowed[0] = true;
                return new Window(existing.startMillis(), existing.count() + 1);
            }
            allowed[0] = false;
            return existing;
        });
        if (allowed[0]) {
            return new Decision(true, 0);
        }
        long remainingMillis = Math.max(0, current.startMillis() + windowMillis - now);
        long retryAfter = Math.max(1, (remainingMillis + 999) / 1000);
        return new Decision(false, retryAfter);
    }

    int trackedKeys() {
        return windows.size();
    }
}
