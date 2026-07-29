package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OwlApiMutationCoordinator(ProjectOntologyCache ontologyCache,
                                     ProjectMetadataService metadataService,
                                     OwlApiMutationPatcher patcher,
                                     @Lazy DesktopOntologyLoader desktopOntologyLoader,
                                     @Lazy SparqlDatasetService datasetService) {
        this.ontologyCache = ontologyCache;
        this.metadataService = metadataService;
        this.patcher = patcher;
        this.desktopOntologyLoader = desktopOntologyLoader;
        this.datasetService = datasetService;
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
     */
    public void ensureFreshForRead(String projectId) {
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
