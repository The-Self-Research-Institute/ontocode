package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OntologyCache {

    private static final Logger log = LoggerFactory.getLogger(OntologyCache.class);

    private record CachedEntry(long revision, OWLOntologyManager manager, OWLOntology ontology) {}

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public Optional<OWLOntology> get(String projectId, long revision) {
        CachedEntry entry = cache.get(projectId);
        if (entry != null && entry.revision() == revision) {
            log.info("[OntologyCache] HIT project={} revision={} axioms={}",
                    projectId, revision, entry.ontology().getAxiomCount());
            return Optional.of(entry.ontology());
        }
        if (entry != null) {
            log.info("[OntologyCache] STALE project={} cached={} current={} — evicting",
                    projectId, entry.revision(), revision);
            cache.remove(projectId, entry);
            safeEvict(entry);
        }
        return Optional.empty();
    }

    public void put(String projectId, long revision, OWLOntologyManager manager, OWLOntology ontology) {
        CachedEntry newEntry = new CachedEntry(revision, manager, ontology);
        CachedEntry old = cache.put(projectId, newEntry);
        if (old != null && old.revision() != revision) {
            safeEvict(old);
        }
        log.info("[OntologyCache] STORED project={} revision={} axioms={}",
                projectId, revision, ontology.getAxiomCount());
    }

    public void invalidate(String projectId) {
        CachedEntry old = cache.remove(projectId);
        if (old != null) {
            safeEvict(old);
            log.info("[OntologyCache] INVALIDATED project={}", projectId);
        }
    }

    private void safeEvict(CachedEntry entry) {
        try {
            entry.manager().removeOntology(entry.ontology());
        } catch (Exception ignored) {}
    }
}
