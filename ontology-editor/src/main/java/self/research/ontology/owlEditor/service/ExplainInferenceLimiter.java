package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.common.admission.RefusalReason;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class ExplainInferenceLimiter {

    static final Duration HOLD_LIMIT = Duration.ofMinutes(15);
    static final long RETRY_AFTER_MILLIS = 5_000;

    private final Clock clock;
    private final int maxActivePerUser;
    private final Map<String, Map<String, Instant>> activeByUser = new HashMap<>();

    @Autowired
    public ExplainInferenceLimiter(@Value("${ontocode.explain.max-active-per-user:2}") int maxActivePerUser) {
        this(Clock.systemUTC(), maxActivePerUser);
    }

    ExplainInferenceLimiter(Clock clock, int maxActivePerUser) {
        this.clock = clock;
        this.maxActivePerUser = maxActivePerUser;
    }

    public synchronized void requireRoom(String owner) {
        if (maxActivePerUser <= 0) {
            return;
        }
        Map<String, Instant> mine = activeByUser.get(owner);
        if (mine == null) {
            return;
        }
        prune(mine);
        if (mine.size() >= maxActivePerUser) {
            throw new BusyException(RefusalReason.OWNER_LIMIT,
                    "You already have " + maxActivePerUser + " explanations running. "
                            + "Wait for one to finish or cancel it, then try again.",
                    RETRY_AFTER_MILLIS);
        }
    }

    public synchronized void started(String owner, String jobId) {
        if (jobId == null) {
            return;
        }
        if (activeByUser.size() > 500) {
            activeByUser.values().forEach(this::prune);
            activeByUser.values().removeIf(Map::isEmpty);
        }
        activeByUser.computeIfAbsent(owner, key -> new HashMap<>()).put(jobId, clock.instant());
    }

    public synchronized void finished(String owner, String jobId) {
        Map<String, Instant> mine = activeByUser.get(owner);
        if (mine == null) {
            return;
        }
        mine.remove(jobId);
        if (mine.isEmpty()) {
            activeByUser.remove(owner);
        }
    }

    public synchronized int active(String owner) {
        Map<String, Instant> mine = activeByUser.get(owner);
        if (mine == null) {
            return 0;
        }
        prune(mine);
        return mine.size();
    }

    private void prune(Map<String, Instant> mine) {
        Instant cutoff = clock.instant().minus(HOLD_LIMIT);
        Iterator<Map.Entry<String, Instant>> it = mine.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isBefore(cutoff)) {
                it.remove();
            }
        }
    }
}
