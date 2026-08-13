package self.research.ontology.owlEditor.cache;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Conditional(FastOpenCondition.class)
public class ProjectOntologyCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectOntologyCache.class);

    public record CachedOntology(
            OWLOntology ontology,
            OWLReasoner reasoner,
            OWLOntologyManager manager,
            boolean assertedHierarchyOnly) {

        void dispose() {
            if (reasoner != null) {
                try { reasoner.dispose(); } catch (Exception e) {  }
            }
            try { manager.removeOntology(ontology); } catch (Exception e) {  }
        }
    }

    private final Map<String, Long> cachedMutationVersions = new ConcurrentHashMap<>();

    private final int maxProjects;

    private final Map<String, CachedOntology> cache;

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

    public int getMaxProjects() {
        return maxProjects;
    }

    public void put(String projectId, OWLOntology ontology, OWLReasoner reasoner,
                    OWLOntologyManager manager, boolean assertedHierarchyOnly) {
        CachedOntology existing = cache.remove(projectId);
        if (existing != null) existing.dispose();
        cache.put(projectId, new CachedOntology(ontology, reasoner, manager, assertedHierarchyOnly));
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
