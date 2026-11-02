package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class JobOwnerRegistry {

    public static final int MAX_ENTRIES = 20_000;
    public static final Duration TTL = Duration.ofHours(12);

    public record Entry(String projectId, Set<String> owners) {
    }

    private record Stored(String projectId, Set<String> owners, Instant touched) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Stored> entries;

    public JobOwnerRegistry() {
        this(Clock.systemUTC(), TTL, MAX_ENTRIES);
    }

    public JobOwnerRegistry(Clock clock, Duration ttl, int maxEntries) {
        this.clock = clock;
        this.ttl = ttl;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Stored> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized void register(String jobId, String projectId, String ownerId) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        Stored existing = entries.get(jobId);
        Set<String> owners = existing != null && !isExpired(existing) ? new HashSet<>(existing.owners()) : new HashSet<>();
        if (ownerId != null && !ownerId.isBlank()) {
            owners.add(ownerId);
        }
        String project = existing != null && existing.projectId() != null ? existing.projectId() : projectId;
        entries.put(jobId, new Stored(project, owners, clock.instant()));
    }

    public synchronized Optional<Entry> find(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        Stored stored = entries.get(jobId);
        if (stored == null) {
            return Optional.empty();
        }
        if (isExpired(stored)) {
            entries.remove(jobId);
            return Optional.empty();
        }
        return Optional.of(new Entry(stored.projectId(), Collections.unmodifiableSet(new HashSet<>(stored.owners()))));
    }

    private boolean isExpired(Stored stored) {
        return stored.touched().plus(ttl).isBefore(clock.instant());
    }
}
