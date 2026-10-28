package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RuleRunStore {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    public static final int MAX_RUNS_PER_USER = 5;
    public static final int MAX_RUNS_TOTAL = 50;
    public static final int MAX_CONCLUSIONS = 50_000;
    public static final int MAX_STRUCTURE = 200_000;

    public record Run(String id, String projectId, String ownerId, boolean draft, Instant createdAt,
                      List<OWLAxiom> conclusions, List<OWLAxiom> structure, boolean capped) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final int perUser;
    private final int total;
    private final int conclusionCap;
    private final int structureCap;
    private final Map<String, Run> runs = new LinkedHashMap<>();

    @Autowired
    public RuleRunStore() {
        this(Clock.systemUTC(), DEFAULT_TTL, MAX_RUNS_PER_USER, MAX_RUNS_TOTAL, MAX_CONCLUSIONS, MAX_STRUCTURE);
    }

    public RuleRunStore(Clock clock, Duration ttl, int perUser, int total, int conclusionCap, int structureCap) {
        this.clock = clock;
        this.ttl = ttl;
        this.perUser = perUser;
        this.total = total;
        this.conclusionCap = conclusionCap;
        this.structureCap = structureCap;
    }

    public synchronized String put(String projectId, String ownerId, boolean draft,
                                   List<OWLAxiom> conclusions, List<OWLAxiom> structure) {
        purgeExpired();
        boolean capped = conclusions.size() > conclusionCap || structure.size() > structureCap;
        List<OWLAxiom> keptConclusions = new ArrayList<>(conclusions.subList(0, Math.min(conclusions.size(), conclusionCap)));
        List<OWLAxiom> keptStructure = new ArrayList<>(structure.subList(0, Math.min(structure.size(), structureCap)));
        evictOldest(r -> ownerId.equals(r.ownerId()), perUser - 1);
        evictOldest(r -> true, total - 1);
        String id = UUID.randomUUID().toString();
        runs.put(id, new Run(id, projectId, ownerId, draft, clock.instant(), keptConclusions, keptStructure, capped));
        return id;
    }

    public synchronized Optional<Run> find(String id) {
        purgeExpired();
        return id == null ? Optional.empty() : Optional.ofNullable(runs.get(id));
    }

    public synchronized int size() {
        purgeExpired();
        return runs.size();
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        runs.values().removeIf(r -> !r.createdAt().isAfter(cutoff));
    }

    private void evictOldest(java.util.function.Predicate<Run> scope, int keep) {
        List<Run> matching = runs.values().stream().filter(scope).sorted(Comparator.comparing(Run::createdAt)).toList();
        int excess = matching.size() - Math.max(keep, 0);
        for (int i = 0; i < excess; i++) {
            runs.remove(matching.get(i).id());
        }
    }
}
