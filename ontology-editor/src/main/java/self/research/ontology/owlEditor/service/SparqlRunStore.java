package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import self.research.ontology.common.admission.CacheAccount;
import self.research.ontology.common.admission.ReservationLedger;

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
import java.util.function.Predicate;

@Component
public class SparqlRunStore {

    public static final long BYTES_PER_CHAR = 2;
    public static final long BYTES_PER_CELL = 64;
    public static final long BYTES_PER_ROW = 32;
    public static final char SEPARATOR = '\u0000';

    public record Meta(long totalRows, boolean totalRowsExact, boolean truncated, int rowLimit, boolean bytesCapped,
                       long executionTimeMs) {
    }

    public record SortOrder(int column, int[] ascending) {
    }

    public static final class Entry {
        private final String id;
        private final String projectId;
        private final String ownerId;
        private final Instant createdAt;
        private final List<String> vars;
        private final String[][] rows;
        private final long estimatedBytes;
        private final Meta meta;
        private volatile SortOrder sortOrder;

        Entry(String id, String projectId, String ownerId, Instant createdAt, List<String> vars, String[][] rows,
              long estimatedBytes, Meta meta) {
            this.id = id;
            this.projectId = projectId;
            this.ownerId = ownerId;
            this.createdAt = createdAt;
            this.vars = vars;
            this.rows = rows;
            this.estimatedBytes = estimatedBytes;
            this.meta = meta;
        }

        public String id() {
            return id;
        }

        public String projectId() {
            return projectId;
        }

        public String ownerId() {
            return ownerId;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public List<String> vars() {
            return vars;
        }

        public String[][] rows() {
            return rows;
        }

        public long estimatedBytes() {
            return estimatedBytes;
        }

        public Meta meta() {
            return meta;
        }

        SortOrder sortOrder() {
            return sortOrder;
        }

        void sortOrder(SortOrder order) {
            this.sortOrder = order;
        }
    }

    private final Clock clock;
    private final Duration ttl;
    private final int perOwner;
    private final int total;
    private final long byteBudget;
    private final int runRows;
    private final long runBytes;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile CacheAccount account = RetainedMemory.detachedAccount("sparqlRuns");

    @Autowired(required = false)
    void setCapacityLedger(ReservationLedger ledger) {
        this.account = ledger.cacheAccount("sparqlRuns");
    }

    @Autowired
    public SparqlRunStore(
            @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.sparql-run-ttl-seconds:900}") long ttlSeconds,
            @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.sparql-runs-per-owner:5}") int perOwner,
            @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.sparql-runs-total:50}") int total,
            @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.sparql-runs-total-bytes:67108864}") long byteBudget,
            @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.sparql-run-rows:10000}") int runRows,
            @org.springframework.beans.factory.annotation.Value("${ontocode.capacity.sparql-run-bytes:16777216}") long runBytes) {
        this(Clock.systemUTC(), Duration.ofSeconds(ttlSeconds), perOwner, total, byteBudget, runRows, runBytes);
    }

    public SparqlRunStore(Clock clock, Duration ttl, int perOwner, int total, long byteBudget, int runRows,
                          long runBytes) {
        this.clock = clock;
        this.ttl = ttl;
        this.perOwner = Math.max(1, perOwner);
        this.total = Math.max(1, total);
        this.byteBudget = Math.max(1, byteBudget);
        this.runRows = Math.max(1, runRows);
        this.runBytes = Math.max(1, Math.min(runBytes, this.byteBudget));
    }

    public SparqlResultLimits retention() {
        return new SparqlResultLimits(runRows, runBytes);
    }

    public Entry put(String projectId, String ownerId, List<String> vars, List<BindingSet> source, long totalRows,
                     boolean engineTruncated, long executionTimeMs) {
        List<String> names = List.copyOf(vars);
        List<String[]> encoded = new ArrayList<>(Math.min(source.size(), runRows));
        long bytes = 0;
        for (BindingSet row : source) {
            if (encoded.size() >= runRows) {
                break;
            }
            String[] cells = new String[names.size()];
            long rowBytes = BYTES_PER_ROW;
            for (int i = 0; i < cells.length; i++) {
                String cell = encode(row.getValue(names.get(i)));
                cells[i] = cell;
                rowBytes += BYTES_PER_CELL + (cell == null ? 0 : cell.length() * BYTES_PER_CHAR);
            }
            if (bytes + rowBytes > runBytes && !encoded.isEmpty()) {
                break;
            }
            bytes += rowBytes;
            encoded.add(cells);
        }
        int stored = encoded.size();
        boolean truncated = engineTruncated || stored < totalRows;
        Meta meta = new Meta(Math.max(totalRows, stored), !engineTruncated, truncated, runRows,
                truncated && stored < runRows, executionTimeMs);
        String owner = ownerId == null ? "" : ownerId;
        synchronized (this) {
            purgeExpired();
            evictOldest(e -> e.ownerId().equals(owner), perOwner - 1);
            evictOldest(e -> true, total - 1);
            evictUntilFits(bytes);
            Entry entry = new Entry(UUID.randomUUID().toString(), projectId, owner, clock.instant(), names,
                    encoded.toArray(new String[0][]), bytes, meta);
            entries.put(entry.id(), entry);
            RetainedMemory.retainUntilCollected(account, entry, bytes);
            return entry;
        }
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

    public static String encode(Value value) {
        if (value == null) {
            return null;
        }
        if (value.isIRI()) {
            return "u" + value.stringValue();
        }
        if (value.isBNode()) {
            return "b" + ((BNode) value).getID();
        }
        Literal literal = (Literal) value;
        if (literal.getLanguage().isPresent()) {
            return "g" + literal.getLanguage().get() + SEPARATOR + literal.getLabel();
        }
        if (XSD.STRING.equals(literal.getDatatype())) {
            return "l" + literal.getLabel();
        }
        return "d" + literal.getDatatype().stringValue() + SEPARATOR + literal.getLabel();
    }

    public static int displayStart(String cell) {
        char type = cell.charAt(0);
        if (type == 'g' || type == 'd') {
            return cell.indexOf(SEPARATOR) + 1;
        }
        return 1;
    }

    public static String display(String cell) {
        if (cell == null) {
            return null;
        }
        if (cell.charAt(0) == 'b') {
            return "_:" + cell.substring(1);
        }
        return cell.substring(displayStart(cell));
    }

    public static Value decode(String cell) {
        if (cell == null) {
            return null;
        }
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        switch (cell.charAt(0)) {
            case 'u':
                return vf.createIRI(cell.substring(1));
            case 'b':
                return vf.createBNode(cell.substring(1));
            case 'g': {
                int split = cell.indexOf(SEPARATOR);
                return vf.createLiteral(cell.substring(split + 1), cell.substring(1, split));
            }
            case 'd': {
                int split = cell.indexOf(SEPARATOR);
                IRI datatype = vf.createIRI(cell.substring(1, split));
                return vf.createLiteral(cell.substring(split + 1), datatype);
            }
            default:
                return vf.createLiteral(cell.substring(1));
        }
    }
}
