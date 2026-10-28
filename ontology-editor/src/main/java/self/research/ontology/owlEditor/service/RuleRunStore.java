package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    public static final int MAX_CONCLUSIONS = 20_000;
    public static final int MAX_STRUCTURE = 50_000;
    public static final int MAX_STORED_AXIOMS = 150_000;

    public record Run(String id, String projectId, String ownerId, boolean draft, Instant createdAt,
                      List<OWLAxiom> conclusions, List<OWLAxiom> structure, boolean capped) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final int perUser;
    private final int total;
    private final int conclusionCap;
    private final int structureCap;
    private final int axiomBudget;
    private final Map<String, Run> runs = new LinkedHashMap<>();

    @Autowired
    public RuleRunStore() {
        this(Clock.systemUTC(), DEFAULT_TTL, MAX_RUNS_PER_USER, MAX_RUNS_TOTAL, MAX_CONCLUSIONS, MAX_STRUCTURE);
    }

    public RuleRunStore(Clock clock, Duration ttl, int perUser, int total, int conclusionCap, int structureCap) {
        this(clock, ttl, perUser, total, conclusionCap, structureCap, MAX_STORED_AXIOMS);
    }

    public RuleRunStore(Clock clock, Duration ttl, int perUser, int total, int conclusionCap, int structureCap, int axiomBudget) {
        this.clock = clock;
        this.ttl = ttl;
        this.perUser = perUser;
        this.total = total;
        this.conclusionCap = conclusionCap;
        this.structureCap = structureCap;
        this.axiomBudget = Math.max(axiomBudget, 1);
    }

    public synchronized String put(String projectId, String ownerId, boolean draft,
                                   List<OWLAxiom> conclusions, List<OWLAxiom> structure) {
        purgeExpired();
        boolean capped = conclusions.size() > conclusionCap || structure.size() > structureCap;
        int conclusionCount = Math.min(conclusions.size(), Math.min(conclusionCap, axiomBudget));
        int structureCount = Math.min(structure.size(), Math.min(structureCap, axiomBudget - conclusionCount));
        capped = capped || conclusionCount < conclusions.size() || structureCount < structure.size();
        List<OWLAxiom> keptConclusions = List.copyOf(conclusions.subList(0, conclusionCount));
        List<OWLAxiom> keptStructure = List.copyOf(structure.subList(0, structureCount));
        evictOldest(r -> ownerId.equals(r.ownerId()), perUser - 1);
        evictOldest(r -> true, total - 1);
        evictUntilFits(keptConclusions.size() + keptStructure.size());
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

    public synchronized int storedAxioms() {
        purgeExpired();
        return storedAxiomCount();
    }

    private int storedAxiomCount() {
        return runs.values().stream().mapToInt(r -> r.conclusions().size() + r.structure().size()).sum();
    }

    private void evictUntilFits(int incoming) {
        List<Run> oldestFirst = runs.values().stream().sorted(Comparator.comparing(Run::createdAt)).toList();
        int stored = storedAxiomCount();
        for (Run run : oldestFirst) {
            if (stored + incoming <= axiomBudget) {
                return;
            }
            runs.remove(run.id());
            stored -= run.conclusions().size() + run.structure().size();
        }
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
