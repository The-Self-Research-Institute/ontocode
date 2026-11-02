package self.research.ontology.swrl.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

@Component
public class SqwrlResultStore {

    public static final long BYTES_PER_CHAR = 2;
    public static final long BYTES_PER_CELL = 48;
    public static final long BYTES_PER_ROW = 120;

    public record Meta(String queryName, String queryText, long executionTimeMs, int totalRows, boolean totalRowsExact,
                       boolean truncated, int rowLimit, boolean bytesCapped) {
    }

    public record Entry(String id, String projectId, String ownerId, Instant createdAt, List<String> columns,
                        List<Map<String, String>> rows, long estimatedBytes, Meta meta) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final int perOwner;
    private final int total;
    private final long byteBudget;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Autowired
    public SqwrlResultStore(
            @Value("${ontocode.capacity.swrl-execute.sqwrl-result-ttl-seconds:900}") long ttlSeconds,
            @Value("${ontocode.capacity.swrl-execute.sqwrl-results-per-owner:5}") int perOwner,
            @Value("${ontocode.capacity.swrl-execute.sqwrl-results-total:50}") int total,
            @Value("${ontocode.capacity.swrl-execute.sqwrl-results-total-bytes:67108864}") long byteBudget) {
        this(Clock.systemUTC(), Duration.ofSeconds(ttlSeconds), perOwner, total, byteBudget);
    }

    public SqwrlResultStore(Clock clock, Duration ttl, int perOwner, int total, long byteBudget) {
        this.clock = clock;
        this.ttl = ttl;
        this.perOwner = Math.max(1, perOwner);
        this.total = Math.max(1, total);
        this.byteBudget = Math.max(1, byteBudget);
    }

    public static long estimateBytes(List<String> columns, List<Map<String, String>> rows) {
        long chars = 0;
        for (Map<String, String> row : rows) {
            for (String cell : row.values()) {
                chars += cell == null ? 0 : cell.length();
            }
        }
        return chars * BYTES_PER_CHAR + (long) rows.size() * columns.size() * BYTES_PER_CELL + (long) rows.size() * BYTES_PER_ROW;
    }

    public synchronized Optional<Entry> put(String projectId, String ownerId, List<String> columns,
                                            List<Map<String, String>> rows, Meta meta) {
        purgeExpired();
        long bytes = estimateBytes(columns, rows);
        if (bytes > byteBudget) {
            return Optional.empty();
        }
        String owner = ownerId == null ? "" : ownerId;
        evictOldest(e -> e.ownerId().equals(owner), perOwner - 1);
        evictOldest(e -> true, total - 1);
        evictUntilFits(bytes);
        Entry entry = new Entry(UUID.randomUUID().toString(), projectId, owner, clock.instant(), List.copyOf(columns),
                List.copyOf(rows), bytes, meta);
        entries.put(entry.id(), entry);
        return Optional.of(entry);
    }

    public synchronized Optional<Entry> find(String id, String projectId, String ownerId) {
        purgeExpired();
        Entry entry = id == null ? null : entries.get(id);
        String owner = ownerId == null ? "" : ownerId;
        if (entry == null || !entry.projectId().equals(projectId) || !entry.ownerId().equals(owner)) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public synchronized int size() {
        purgeExpired();
        return entries.size();
    }

    public synchronized long storedBytes() {
        purgeExpired();
        return entries.values().stream().mapToLong(Entry::estimatedBytes).sum();
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        entries.values().removeIf(e -> !e.createdAt().isAfter(cutoff));
    }

    private void evictOldest(Predicate<Entry> scope, int keep) {
        List<Entry> matching = entries.values().stream().filter(scope).sorted(Comparator.comparing(Entry::createdAt)).toList();
        int excess = matching.size() - Math.max(keep, 0);
        for (int i = 0; i < excess; i++) {
            entries.remove(matching.get(i).id());
        }
    }

    private void evictUntilFits(long incoming) {
        long stored = entries.values().stream().mapToLong(Entry::estimatedBytes).sum();
        for (Entry entry : entries.values().stream().sorted(Comparator.comparing(Entry::createdAt)).toList()) {
            if (stored + incoming <= byteBudget) {
                return;
            }
            entries.remove(entry.id());
            stored -= entry.estimatedBytes();
        }
    }
}
