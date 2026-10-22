package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OntologyCache {

    private static final Logger log = LoggerFactory.getLogger(OntologyCache.class);

    private record CachedEntry(long revision, long tripleCount, OWLOntologyManager manager, OWLOntology ontology) {}

    @Value("${ontocode.reasoner.ontology-cache-max-projects:20}")
    private int maxProjects;

    private final LinkedHashMap<String, CachedEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<OWLOntology, AtomicInteger> useCount = new IdentityHashMap<>();
    private final Map<OWLOntology, CachedEntry> pendingEviction = new IdentityHashMap<>();
    private final Object useLock = new Object();

    public void beginUse(OWLOntology ontology) {
        synchronized (useLock) {
            useCount.computeIfAbsent(ontology, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    public void endUse(OWLOntology ontology) {
        CachedEntry toEvict = null;
        synchronized (useLock) {
            AtomicInteger count = useCount.get(ontology);
            if (count != null && count.decrementAndGet() <= 0) {
                useCount.remove(ontology);
                toEvict = pendingEviction.remove(ontology);
            }
        }
        if (toEvict != null) {
            safeEvict(toEvict);
        }
    }

    public Optional<OWLOntology> getAndBeginUse(String projectId, long revision, long tripleCount) {
        return lookup(projectId, revision, tripleCount, true);
    }

    public Optional<OWLOntology> get(String projectId, long revision, long tripleCount) {
        return lookup(projectId, revision, tripleCount, false);
    }

    private Optional<OWLOntology> lookup(String projectId, long revision, long tripleCount, boolean beginUse) {
        CachedEntry staleEntry = null;
        OWLOntology hit = null;
        List<CachedEntry> toEvict = new ArrayList<>();
        synchronized (useLock) {
            CachedEntry entry = cache.get(projectId);
            if (entry != null && entry.revision() == revision && entry.tripleCount() == tripleCount) {
                hit = entry.ontology();
                if (beginUse) {
                    useCount.computeIfAbsent(hit, k -> new AtomicInteger()).incrementAndGet();
                }
            } else if (entry != null) {
                cache.remove(projectId);
                staleEntry = entry;
                evictOrDeferLocked(entry, toEvict);
            }
        }
        toEvict.forEach(this::safeEvict);
        if (hit != null) {
            log.info("[OntologyCache] HIT project={} revision={} axioms={}", projectId, revision, hit.getAxiomCount());
            return Optional.of(hit);
        }
        if (staleEntry != null) {
            log.info("[OntologyCache] STALE project={} cached={}/{} triples current={}/{} triples — evicting",
                    projectId, staleEntry.revision(), staleEntry.tripleCount(), revision, tripleCount);
        }
        return Optional.empty();
    }

    public void put(String projectId, long revision, long tripleCount, OWLOntologyManager manager, OWLOntology ontology) {
        store(projectId, revision, tripleCount, manager, ontology, false);
    }

    public void putAndBeginUse(String projectId, long revision, long tripleCount, OWLOntologyManager manager,
                               OWLOntology ontology) {
        store(projectId, revision, tripleCount, manager, ontology, true);
    }

    private void store(String projectId, long revision, long tripleCount, OWLOntologyManager manager,
                       OWLOntology ontology, boolean beginUse) {
        CachedEntry newEntry = new CachedEntry(revision, tripleCount, manager, ontology);
        List<CachedEntry> toEvict = new ArrayList<>();
        List<String> lruEvicted = new ArrayList<>();
        synchronized (useLock) {
            if (beginUse) {
                useCount.computeIfAbsent(ontology, k -> new AtomicInteger()).incrementAndGet();
            }
            CachedEntry old = cache.put(projectId, newEntry);
            if (old != null && old.ontology() != ontology) {
                evictOrDeferLocked(old, toEvict);
            }
            int limit = Math.max(1, maxProjects);
            Iterator<Map.Entry<String, CachedEntry>> it = cache.entrySet().iterator();
            while (cache.size() > limit && it.hasNext()) {
                Map.Entry<String, CachedEntry> eldest = it.next();
                if (eldest.getKey().equals(projectId)) {
                    continue;
                }
                it.remove();
                lruEvicted.add(eldest.getKey());
                evictOrDeferLocked(eldest.getValue(), toEvict);
            }
        }
        toEvict.forEach(this::safeEvict);
        for (String evictedProject : lruEvicted) {
            log.info("[OntologyCache] LRU evicted project={} (over {} project limit)", evictedProject, maxProjects);
        }
        log.info("[OntologyCache] STORED project={} revision={} axioms={}",
                projectId, revision, ontology.getAxiomCount());
    }

    public void invalidate(String projectId) {
        List<CachedEntry> toEvict = new ArrayList<>();
        CachedEntry old;
        synchronized (useLock) {
            old = cache.remove(projectId);
            if (old != null) {
                evictOrDeferLocked(old, toEvict);
            }
        }
        toEvict.forEach(this::safeEvict);
        if (old != null) {
            log.info("[OntologyCache] INVALIDATED project={}", projectId);
        }
    }

    private void evictOrDeferLocked(CachedEntry entry, List<CachedEntry> toEvict) {
        AtomicInteger count = useCount.get(entry.ontology());
        if (count != null && count.get() > 0) {
            pendingEviction.put(entry.ontology(), entry);
            return;
        }
        toEvict.add(entry);
    }

    private void safeEvict(CachedEntry entry) {
        try {
            entry.manager().removeOntology(entry.ontology());
        } catch (Exception ignored) {}
    }
}
