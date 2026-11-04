package self.research.ontology.owlEditor.cache;

import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.common.admission.CacheAccount;
import self.research.ontology.owlEditor.service.RetainedMemory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BoundedOntologyLru {

    public interface Loader {
        OWLOntology load() throws Exception;
    }

    private record Entry(OWLOntology ontology, long bytes) {}

    private final int maxEntries;
    private final String accountName;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(8, 0.75f, true);
    private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();
    private volatile CacheAccount account;

    public BoundedOntologyLru(int maxEntries, String accountName) {
        this.maxEntries = Math.max(1, maxEntries);
        this.accountName = accountName;
        this.account = RetainedMemory.detachedAccount(accountName);
    }

    public void bindAccount(CacheAccount account) {
        synchronized (this) {
            long carried = 0;
            for (Entry e : entries.values()) {
                carried += e.bytes();
            }
            this.account.remove(carried);
            this.account = account;
            account.add(carried);
        }
    }

    public CacheAccount account() {
        return account;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized OWLOntology get(String key) {
        Entry e = entries.get(key);
        return e == null ? null : e.ontology();
    }

    public OWLOntology getOrLoad(String key, Loader loader) throws Exception {
        OWLOntology cached = get(key);
        if (cached != null) {
            return cached;
        }
        Object lock = loadLocks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                cached = get(key);
                if (cached != null) {
                    return cached;
                }
                OWLOntology loaded = loader.load();
                put(key, loaded);
                return loaded;
            }
        } finally {
            loadLocks.remove(key, lock);
        }
    }

    public void put(String key, OWLOntology ontology) {
        List<Entry> dropped = new ArrayList<>();
        synchronized (this) {
            Entry fresh = new Entry(ontology, RetainedMemory.ontologyBytes(ontology.getAxiomCount()));
            Entry previous = entries.put(key, fresh);
            account.add(fresh.bytes());
            if (previous != null) {
                dropped.add(previous);
            }
            while (entries.size() > maxEntries) {
                Map.Entry<String, Entry> eldest = entries.entrySet().iterator().next();
                dropped.add(eldest.getValue());
                entries.remove(eldest.getKey());
            }
            for (Entry e : dropped) {
                account.remove(e.bytes());
            }
        }
    }

    public void remove(String key) {
        synchronized (this) {
            Entry removed = entries.remove(key);
            if (removed != null) {
                account.remove(removed.bytes());
            }
        }
    }

    public void clear() {
        List<Entry> all;
        synchronized (this) {
            all = new ArrayList<>(entries.values());
            entries.clear();
            for (Entry e : all) {
                account.remove(e.bytes());
            }
        }
    }
}
