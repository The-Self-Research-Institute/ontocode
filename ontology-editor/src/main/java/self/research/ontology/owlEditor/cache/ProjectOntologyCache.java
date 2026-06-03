package self.research.ontology.owlEditor.cache;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Desktop-only in-memory cache of parsed OWLOntology + structural OWLReasoner.
 *
 * Bounded to MAX_PROJECTS entries (LRU eviction).  When an entry is evicted
 * the reasoner is disposed and the manager removes the ontology, freeing heap.
 *
 * Only active when ontocode.desktop.mode=true.  Cloud deployments use Fuseki
 * SPARQL exclusively so this bean is never created there.
 */
@Component
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class ProjectOntologyCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectOntologyCache.class);
    // Configurable via -Dontocode.desktop.cache.maxProjects=N (default 4).
    // Holds parsed model + reasoner per project; revisiting within this window
    // is instant instead of triggering a multi-second re-parse.
    private static final int MAX_PROJECTS =
        Math.max(1, Integer.getInteger("ontocode.desktop.cache.maxProjects", 4));

    public record CachedOntology(OWLOntology ontology, OWLReasoner reasoner, OWLOntologyManager manager) {
        void dispose() {
            try { reasoner.dispose(); } catch (Exception e) { /* ignore */ }
            try { manager.removeOntology(ontology); } catch (Exception e) { /* ignore */ }
        }
    }

    private final Map<String, CachedOntology> cache =
        Collections.synchronizedMap(new LinkedHashMap<>(MAX_PROJECTS + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedOntology> eldest) {
                if (size() > MAX_PROJECTS) {
                    log.info("[OntologyCache] Evicting project {} from OWLAPI cache", eldest.getKey());
                    eldest.getValue().dispose();
                    return true;
                }
                return false;
            }
        });

    public void put(String projectId, OWLOntology ontology, OWLReasoner reasoner, OWLOntologyManager manager) {
        CachedOntology existing = cache.remove(projectId);
        if (existing != null) existing.dispose();
        cache.put(projectId, new CachedOntology(ontology, reasoner, manager));
        log.info("[OntologyCache] Cached OWLAPI model for project {} ({} classes)",
            projectId, ontology.classesInSignature().count());
    }

    public Optional<CachedOntology> get(String projectId) {
        return Optional.ofNullable(cache.get(projectId));
    }

    public boolean has(String projectId) {
        return cache.containsKey(projectId);
    }

    public void evict(String projectId) {
        CachedOntology removed = cache.remove(projectId);
        if (removed != null) {
            removed.dispose();
            log.info("[OntologyCache] Evicted project {} from OWLAPI cache", projectId);
        }
    }

    public int size() { return cache.size(); }
}
