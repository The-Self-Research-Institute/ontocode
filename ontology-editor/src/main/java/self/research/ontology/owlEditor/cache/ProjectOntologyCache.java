package self.research.ontology.owlEditor.cache;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import self.research.ontology.common.admission.CacheAccount;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.service.RetainedMemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of parsed OWLOntology + optional structural OWLReasoner.
 *
 * Active in desktop mode and in cloud when fast-open is enabled (default).
 * Cloud fast-open uses asserted hierarchy (no reasoner precompute) for open times.
 */
@Component
@Conditional(FastOpenCondition.class)
public class ProjectOntologyCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectOntologyCache.class);

    public static final class CachedOntology {

        private final OWLOntology ontology;
        private final OWLReasoner reasoner;
        private final OWLOntologyManager manager;
        private final boolean assertedHierarchyOnly;
        private final CacheAccount account;
        private final long accountedBytes;
        private OWLReasoner structural;
        private boolean disposed;

        public CachedOntology(OWLOntology ontology, OWLReasoner reasoner, OWLOntologyManager manager,
                              boolean assertedHierarchyOnly) {
            this(ontology, reasoner, manager, assertedHierarchyOnly, null);
        }

        CachedOntology(OWLOntology ontology, OWLReasoner reasoner, OWLOntologyManager manager,
                       boolean assertedHierarchyOnly, CacheAccount account) {
            this.ontology = ontology;
            this.reasoner = reasoner;
            this.manager = manager;
            this.assertedHierarchyOnly = assertedHierarchyOnly;
            this.account = account;
            this.accountedBytes = account == null ? 0 : RetainedMemory.ontologyBytes(ontology.getAxiomCount());
            if (account != null) {
                account.add(accountedBytes);
            }
        }

        public OWLOntology ontology() { return ontology; }

        public OWLReasoner reasoner() { return reasoner; }

        public OWLOntologyManager manager() { return manager; }

        public boolean assertedHierarchyOnly() { return assertedHierarchyOnly; }

        public synchronized OWLReasoner structuralReasoner() {
            if (reasoner != null) {
                return reasoner;
            }
            if (structural == null) {
                structural = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
            }
            return structural;
        }

        synchronized void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            if (account != null) {
                account.remove(accountedBytes);
            }
            if (reasoner != null) {
                try { reasoner.dispose(); } catch (Exception e) { /* ignore */ }
            }
            if (structural != null) {
                try { structural.dispose(); } catch (Exception e) { /* ignore */ }
            }
            try { manager.removeOntology(ontology); } catch (Exception e) { /* ignore */ }
        }
    }

    private final Map<String, Long> cachedMutationVersions = new ConcurrentHashMap<>();

    // Configurable via -Dontocode.desktop.cache.maxProjects=N. Default is 1 on desktop
    // (single user working on one file at a time; entries always carry a precomputed
    // reasoner — heavy) and 4 in cloud/webapp (this cache is shared across all
    // concurrent users on the instance, and fast-open entries there skip the
    // reasoner — lighter per entry).
    private final int maxProjects;

    private final Map<String, CachedOntology> cache;

    private volatile CacheAccount account = RetainedMemory.detachedAccount("projectOntologies");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCapacityLedger(ReservationLedger ledger) {
        this.account = ledger.cacheAccount("projectOntologies");
    }

    public CacheAccount account() {
        return account;
    }

    public ProjectOntologyCache(@Value("${ontocode.desktop.mode:false}") boolean desktopMode) {
        int defaultMaxProjects = desktopMode ? 1 : 4;
        this.maxProjects = Math.max(1,
            Integer.getInteger("ontocode.desktop.cache.maxProjects", defaultMaxProjects));
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(this.maxProjects + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedOntology> eldest) {
                if (size() > maxProjects) {
                    log.info("[OntologyCache] Evicting project {} from OWLAPI cache", eldest.getKey());
                    eldest.getValue().dispose();
                    return true;
                }
                return false;
            }
        });
    }

    /** How many projects this cache can hold at once — pre-warming more than this is pure waste. */
    public int getMaxProjects() {
        return maxProjects;
    }

    public void put(String projectId, OWLOntology ontology, OWLReasoner reasoner,
                    OWLOntologyManager manager, boolean assertedHierarchyOnly) {
        CachedOntology existing = cache.remove(projectId);
        if (existing != null) existing.dispose();
        cache.put(projectId, new CachedOntology(ontology, reasoner, manager, assertedHierarchyOnly, account));
        log.info("[OntologyCache] Cached OWLAPI model for project {} ({} classes)",
            projectId, ontology.classesInSignature().count());
    }

    public void setCachedVersion(String projectId, long mutationVersion) {
        if (cache.containsKey(projectId)) {
            cachedMutationVersions.put(projectId, mutationVersion);
        }
    }

    public void updateCachedVersion(String projectId, long mutationVersion) {
        cachedMutationVersions.put(projectId, mutationVersion);
    }

    public long getCachedVersion(String projectId) {
        return cachedMutationVersions.getOrDefault(projectId, -1L);
    }

    public Optional<CachedOntology> get(String projectId) {
        return Optional.ofNullable(cache.get(projectId));
    }

    public boolean has(String projectId) {
        return cache.containsKey(projectId);
    }

    public void evict(String projectId) {
        cachedMutationVersions.remove(projectId);
        CachedOntology removed = cache.remove(projectId);
        if (removed != null) {
            removed.dispose();
            log.info("[OntologyCache] Evicted project {} from OWLAPI cache", projectId);
        }
    }

    public int size() { return cache.size(); }
}
