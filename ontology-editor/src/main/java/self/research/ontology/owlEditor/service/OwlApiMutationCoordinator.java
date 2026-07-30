package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.util.List;
import java.util.Map;

/**
 * Keeps the OWLAPI in-memory model in sync with Fuseki after mutations.
 * Structured ops from {@link OntologyMutationService} are patched in-place when
 * possible; all other writes bump the MongoDB version and evict the cache.
 */
@Service
@Conditional(FastOpenCondition.class)
public class OwlApiMutationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OwlApiMutationCoordinator.class);

    private final ProjectOntologyCache ontologyCache;
    private final ProjectMetadataService metadataService;
    private final OwlApiMutationPatcher patcher;
    private final DesktopOntologyLoader desktopOntologyLoader;
    private final SparqlDatasetService datasetService;
    private final boolean desktopMode;

    public OwlApiMutationCoordinator(ProjectOntologyCache ontologyCache,
                                     ProjectMetadataService metadataService,
                                     OwlApiMutationPatcher patcher,
                                     @Lazy DesktopOntologyLoader desktopOntologyLoader,
                                     SparqlDatasetService datasetService,
                                     @Value("${ontocode.desktop.mode:false}") boolean desktopMode) {
        this.ontologyCache = ontologyCache;
        this.metadataService = metadataService;
        this.patcher = patcher;
        this.desktopOntologyLoader = desktopOntologyLoader;
        this.datasetService = datasetService;
        this.desktopMode = desktopMode;
    }

    /**
     * Called at the end of every {@link SparqlDatasetService#execUpdate}.
     *
     * @param structuredOps non-null when the write originated from {@link OntologyMutationService#apply}
     */
    public void afterMutation(String projectId, @Nullable List<OntologyMutationService.MutationOp> structuredOps) {
        long version = metadataService.incrementMutationVersion(projectId);
        if (structuredOps != null && patcher.tryPatch(projectId, structuredOps)) {
            ontologyCache.updateCachedVersion(projectId, version);
            log.debug("[OwlApiCoord] Patched in-memory model for project {} at version {}", projectId, version);
        } else {
            ontologyCache.evict(projectId);
            if (structuredOps != null) {
                log.info("[OwlApiCoord] Evicted OWLAPI cache for project {} (non-patchable ops)", projectId);
            }
            boolean desktopOwlApiFirst = desktopOntologyLoader != null && desktopOntologyLoader.isOwlApiFirst();
            if (!desktopOwlApiFirst) {
             // Only mark dirty when Fuseki is the source of truth (web/cloud).
             // On desktop OWLAPI-first, the in-memory model is newer than Fuseki
              // (sync is deferred) — re-exporting from Fuseki here would regress it.
              datasetService.markProjectDirty(projectId);
            }
            Map<String, Object> warm = desktopOntologyLoader.warmProject(projectId, 5_000);
            if (!Boolean.TRUE.equals(warm.get("ready"))) {
                log.warn("[OwlApiCoord] Rewarm after mutation not ready in time for {}: {}", projectId, warm);
        }
    }
    }

    /**
     * Before serving from the OWLAPI fast path, evict if another writer bumped the version.
     *
     * No-op on desktop: this process is the only writer there (single user, single cache),
     * and every write already goes through afterMutation() above, which patches the cache
     * in-place or proactively evicts+rewarms right when a non-patchable write happens — there
     * is no other-instance scenario for a read to reactively catch here. Checking anyway reads
     * MongoDB's version and the cache's version as two separate, non-atomic steps; if a read
     * lands between them it can see a spurious mismatch and evict a cache entry that was
     * actually already correct, sending the next read to whatever is mid-rewarm.
     * Cloud/web IS multi-instance, so this check stays load-bearing there.
     */
    public void ensureFreshForRead(String projectId) {
        if (desktopMode) {
            return;
        }
        if (!ontologyCache.has(projectId)) {
            return;
        }
        long mongoVersion;
        try {
            mongoVersion = metadataService.getMutationVersion(projectId);
        } catch (Exception e) {
            log.warn("[OwlApiCoord] Could not read mutation version for {} — assuming match: {}",
                    projectId, e.getMessage());
            return;
        }
        long cachedVersion = ontologyCache.getCachedVersion(projectId);
         log.info("[TRACE] project={}, mongoVersion={}, cachedVersion={}",
        projectId, mongoVersion, cachedVersion);
        if (cachedVersion >= 0 && mongoVersion > cachedVersion) {
            log.info("[TRACE] Evicting ontology cache for {}", projectId);
            log.info("[OwlApiCoord] Version mismatch for project {} (mongo={}, cached={}) — evicting OWLAPI cache",
                    projectId, mongoVersion, cachedVersion);
            ontologyCache.evict(projectId);
            desktopOntologyLoader.warmProject(projectId, 5_000);
        }
    }
}
