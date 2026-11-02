package self.research.ontology.swrl.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class EngineCache<E> {

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean released;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        public E engine() {
            return entry.engine;
        }

        @Override
        public void close() {
            release(entry, this);
        }
    }

    private final class Entry {
        final E engine;
        long lastAccess = System.currentTimeMillis();
        int leases;
        boolean evicted;
        boolean disposed;

        Entry(E engine) {
            this.engine = engine;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Consumer<E> disposer;
    private int maxSize;

    public EngineCache(int maxSize, Consumer<E> disposer) {
        this.maxSize = Math.max(1, maxSize);
        this.disposer = disposer;
    }

    public synchronized void setMaxSize(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        trim();
    }

    public synchronized Lease acquire(String key, Supplier<E> factory) {
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(factory.get());
            entries.put(key, entry);
            trim();
        }
        entry.lastAccess = System.currentTimeMillis();
        entry.leases++;
        return new Lease(entry);
    }

    public synchronized boolean remove(String key) {
        Entry entry = entries.remove(key);
        if (entry == null) {
            return false;
        }
        retire(entry);
        return true;
    }

    public synchronized int evictOlderThan(long cutoffMillis) {
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.lastAccess < cutoffMillis && entry.leases == 0) {
                iterator.remove();
                retire(entry);
                removed++;
            }
        }
        return removed;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long oldestAgeMillis() {
        long oldest = Long.MAX_VALUE;
        for (Entry entry : entries.values()) {
            oldest = Math.min(oldest, entry.lastAccess);
        }
        return oldest == Long.MAX_VALUE ? 0 : System.currentTimeMillis() - oldest;
    }

    public synchronized List<String> keys() {
        return new ArrayList<>(entries.keySet());
    }

    private void trim() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > maxSize && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            iterator.remove();
            retire(entry);
        }
    }

    private void retire(Entry entry) {
        entry.evicted = true;
        if (entry.leases == 0) {
            dispose(entry);
        }
    }

    private synchronized void release(Entry entry, Lease lease) {
        if (lease.released) {
            return;
        }
        lease.released = true;
        entry.leases--;
        if (entry.evicted && entry.leases == 0) {
            dispose(entry);
        }
    }

    private void dispose(Entry entry) {
        if (entry.disposed) {
            return;
        }
        entry.disposed = true;
        if (disposer != null) {
            disposer.accept(entry.engine);
        }
    }
}
