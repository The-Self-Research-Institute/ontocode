package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class AssistantAdmissionLimiter {

    public static final String SCOPE = "per-node";
    private static final long WINDOW_MILLIS = 60_000L;

    private final int maxConcurrentPerUser;
    private final int maxConcurrentPerProject;
    private final int maxConcurrentGlobal;
    private final int maxSessionCreatesPerMinute;
    private final int toolRetryAfterSeconds;
    private final Clock clock;

    private final ConcurrentMap<String, Integer> inFlightByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> inFlightByProject = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> inFlightGlobal = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<Long>> sessionCreatesByUser = new ConcurrentHashMap<>();

    @Autowired
    public AssistantAdmissionLimiter(
            @Value("${assistant.admission.tool.max-concurrent-per-user:4}") int maxConcurrentPerUser,
            @Value("${assistant.admission.tool.max-concurrent-per-project:8}") int maxConcurrentPerProject,
            @Value("${assistant.admission.tool.max-concurrent-global:32}") int maxConcurrentGlobal,
            @Value("${assistant.admission.session.max-creates-per-minute:30}") int maxSessionCreatesPerMinute,
            @Value("${assistant.admission.tool.retry-after-seconds:1}") int toolRetryAfterSeconds) {
        this(maxConcurrentPerUser, maxConcurrentPerProject, maxConcurrentGlobal, maxSessionCreatesPerMinute,
                toolRetryAfterSeconds, Clock.systemUTC());
    }

    AssistantAdmissionLimiter(int maxConcurrentPerUser, int maxConcurrentPerProject, int maxConcurrentGlobal,
                              int maxSessionCreatesPerMinute, int toolRetryAfterSeconds, Clock clock) {
        this.maxConcurrentPerUser = maxConcurrentPerUser;
        this.maxConcurrentPerProject = maxConcurrentPerProject;
        this.maxConcurrentGlobal = maxConcurrentGlobal;
        this.maxSessionCreatesPerMinute = maxSessionCreatesPerMinute;
        this.toolRetryAfterSeconds = Math.max(1, toolRetryAfterSeconds);
        this.clock = clock;
        log.info("[AssistantAdmission] Limits are enforced {} (in memory, not shared across instances): "
                        + "tool calls user={} project={} global={}, session creates per user per minute={}",
                SCOPE, maxConcurrentPerUser, maxConcurrentPerProject, maxConcurrentGlobal, maxSessionCreatesPerMinute);
    }

    public sealed interface ToolAdmission permits Admitted, Rejected {}

    public record Rejected(String limit, int retryAfterSeconds) implements ToolAdmission {}

    public final class Admitted implements ToolAdmission, AutoCloseable {
        private final String userKey;
        private final String projectKey;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Admitted(String userKey, String projectKey) {
            this.userKey = userKey;
            this.projectKey = projectKey;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                decrement(inFlightByUser, userKey);
                decrement(inFlightByProject, projectKey);
                decrement(inFlightGlobal, "global");
            }
        }
    }

    public ToolAdmission tryAcquireTool(String userEmail, String projectId) {
        String userKey = userEmail == null ? "" : userEmail;
        String projectKey = projectId == null ? "" : projectId;
        if (!tryIncrement(inFlightByUser, userKey, maxConcurrentPerUser)) {
            return new Rejected("user", toolRetryAfterSeconds);
        }
        if (!tryIncrement(inFlightByProject, projectKey, maxConcurrentPerProject)) {
            decrement(inFlightByUser, userKey);
            return new Rejected("project", toolRetryAfterSeconds);
        }
        if (!tryIncrement(inFlightGlobal, "global", maxConcurrentGlobal)) {
            decrement(inFlightByProject, projectKey);
            decrement(inFlightByUser, userKey);
            return new Rejected("global", toolRetryAfterSeconds);
        }
        return new Admitted(userKey, projectKey);
    }

    public Optional<Integer> tryAdmitSessionCreate(String userEmail) {
        String userKey = userEmail == null ? "" : userEmail;
        long now = clock.millis();
        Integer[] retryAfter = new Integer[1];
        sessionCreatesByUser.compute(userKey, (key, window) -> {
            Deque<Long> times = window == null ? new ArrayDeque<>() : window;
            while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= maxSessionCreatesPerMinute) {
                long waitMillis = times.peekFirst() + WINDOW_MILLIS - now;
                retryAfter[0] = (int) Math.max(1, (waitMillis + 999) / 1000);
            } else {
                times.addLast(now);
            }
            return times;
        });
        return Optional.ofNullable(retryAfter[0]);
    }

    int inFlightForUser(String userEmail) {
        return inFlightByUser.getOrDefault(userEmail, 0);
    }

    int inFlightForProject(String projectId) {
        return inFlightByProject.getOrDefault(projectId, 0);
    }

    int inFlightGlobal() {
        return inFlightGlobal.getOrDefault("global", 0);
    }

    private static boolean tryIncrement(ConcurrentMap<String, Integer> counters, String key, int limit) {
        boolean[] admitted = new boolean[1];
        counters.compute(key, (k, current) -> {
            int value = current == null ? 0 : current;
            if (value >= limit) {
                return current;
            }
            admitted[0] = true;
            return value + 1;
        });
        return admitted[0];
    }

    private static void decrement(ConcurrentMap<String, Integer> counters, String key) {
        counters.computeIfPresent(key, (k, current) -> current <= 1 ? null : current - 1);
    }
}
