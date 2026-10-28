package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.OWLOntology;
import org.swrlapi.core.SWRLRuleEngine;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

final class ValidationCache {

    static final int MAX_ENTRIES = 3;
    static final long TTL_MILLIS = 60_000;

    record Key(String projectId, String draftUserId, String owner) {
    }

    static final class Entry {
        final String stamp;
        final OWLOntology ontology;
        final SWRLRuleEngine engine;
        final long axioms;
        final long expiresAt;

        Entry(String stamp, OWLOntology ontology, SWRLRuleEngine engine, long expiresAt) {
            this.stamp = stamp;
            this.ontology = ontology;
            this.engine = engine;
            this.axioms = ontology.getAxiomCount();
            this.expiresAt = expiresAt;
        }
    }

    private final Map<Key, Entry> entries = new LinkedHashMap<>(8, 0.75f, true);
    private final int maxEntries;
    private final long ttlMillis;
    private final LongSupplier clock;

    ValidationCache() {
        this(MAX_ENTRIES, TTL_MILLIS, System::currentTimeMillis);
    }

    ValidationCache(int maxEntries, long ttlMillis, LongSupplier clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    synchronized Entry get(Key key, String stamp) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (stamp == null || !stamp.equals(entry.stamp) || clock.getAsLong() >= entry.expiresAt) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    synchronized Entry put(Key key, String stamp, OWLOntology ontology, SWRLRuleEngine engine, long axiomBudget) {
        Entry entry = new Entry(stamp, ontology, engine, clock.getAsLong() + ttlMillis);
        entries.remove(key);
        dropExpired();
        if (entry.axioms > axiomBudget) {
            return entry;
        }
        entries.put(key, entry);
        while (entries.size() > maxEntries || totalAxioms() > axiomBudget) {
            Iterator<Map.Entry<Key, Entry>> oldest = entries.entrySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
        return entry;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long totalAxioms() {
        long total = 0;
        for (Entry entry : entries.values()) {
            total += entry.axioms;
        }
        return total;
    }

    private void dropExpired() {
        long now = clock.getAsLong();
        entries.values().removeIf(entry -> now >= entry.expiresAt);
    }
}
