package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OntologyCache {

    private static final Logger log = LoggerFactory.getLogger(OntologyCache.class);

    private record CachedEntry(long revision, OWLOntologyManager manager, OWLOntology ontology) {}

    @Value("${ontocode.reasoner.ontology-cache-max-projects:20}")
    private int maxProjects;

    private final Map<String, CachedEntry> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
                if (size() > maxProjects) {
                    log.info("[OntologyCache] LRU evicted project={} (over {} project limit)",
                            eldest.getKey(), maxProjects);
                    evictOrDefer(eldest.getValue());
                    return true;
                }
                return false;
            }
        }
    );
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

    public Optional<OWLOntology> getAndBeginUse(String projectId, long revision) {
        CachedEntry staleEntry = null;
        OWLOntology hit = null;
        synchronized (useLock) {
            synchronized (cache) {
                CachedEntry entry = cache.get(projectId);
                if (entry != null && entry.revision() == revision) {
                    hit = entry.ontology();
                } else if (entry != null) {
                    cache.remove(projectId);
                    staleEntry = entry;
                }
            }
            if (hit != null) {
                useCount.computeIfAbsent(hit, k -> new AtomicInteger()).incrementAndGet();
            }
        }
        if (hit != null) {
            log.info("[OntologyCache] HIT project={} revision={} axioms={}", projectId, revision, hit.getAxiomCount());
            return Optional.of(hit);
        }
        if (staleEntry != null) {
            log.info("[OntologyCache] STALE project={} cached={} current={} — evicting",
                    projectId, staleEntry.revision(), revision);
            evictOrDefer(staleEntry);
        }
        return Optional.empty();
    }

    public Optional<OWLOntology> get(String projectId, long revision) {
        CachedEntry staleEntry = null;
        CachedEntry entry;
        synchronized (cache) {
            entry = cache.get(projectId);
            if (entry != null && entry.revision() == revision) {
                log.info("[OntologyCache] HIT project={} revision={} axioms={}",
                        projectId, revision, entry.ontology().getAxiomCount());
                return Optional.of(entry.ontology());
            }
            if (entry != null) {
                cache.remove(projectId);
                staleEntry = entry;
            }
        }
        if (staleEntry != null) {
            log.info("[OntologyCache] STALE project={} cached={} current={} — evicting",
                    projectId, staleEntry.revision(), revision);
            evictOrDefer(staleEntry);
        }
        return Optional.empty();
    }

    public void put(String projectId, long revision, OWLOntologyManager manager, OWLOntology ontology) {
        CachedEntry newEntry = new CachedEntry(revision, manager, ontology);
        CachedEntry old = cache.put(projectId, newEntry);
        if (old != null && old.revision() != revision) {
            evictOrDefer(old);
        }
        log.info("[OntologyCache] STORED project={} revision={} axioms={}",
                projectId, revision, ontology.getAxiomCount());
    }

    public void invalidate(String projectId) {
        CachedEntry old = cache.remove(projectId);
        if (old != null) {
            evictOrDefer(old);
            log.info("[OntologyCache] INVALIDATED project={}", projectId);
        }
    }

    private void evictOrDefer(CachedEntry entry) {
        synchronized (useLock) {
            AtomicInteger count = useCount.get(entry.ontology());
            if (count != null && count.get() > 0) {
                pendingEviction.put(entry.ontology(), entry);
                return;
            }
        }
        safeEvict(entry);
    }

    private void safeEvict(CachedEntry entry) {
        try {
            entry.manager().removeOntology(entry.ontology());
        } catch (Exception ignored) {}
    }
}
