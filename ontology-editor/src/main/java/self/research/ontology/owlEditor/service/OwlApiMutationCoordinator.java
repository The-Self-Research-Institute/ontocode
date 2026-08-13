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

@Service
@Conditional(FastOpenCondition.class)
public class OwlApiMutationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OwlApiMutationCoordinator.class);

    private final ProjectOntologyCache ontologyCache;
    private final ProjectMetadataService metadataService;
    private final OwlApiMutationPatcher patcher;
    private final DesktopOntologyLoader desktopOntologyLoader;
    private final boolean desktopMode;

    public OwlApiMutationCoordinator(ProjectOntologyCache ontologyCache,
                                     ProjectMetadataService metadataService,
                                     OwlApiMutationPatcher patcher,
                                     @Lazy DesktopOntologyLoader desktopOntologyLoader,
                                     @Value("${ontocode.desktop.mode:false}") boolean desktopMode) {
        this.ontologyCache = ontologyCache;
        this.metadataService = metadataService;
        this.patcher = patcher;
        this.desktopOntologyLoader = desktopOntologyLoader;
        this.desktopMode = desktopMode;
    }

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
            desktopOntologyLoader.scheduleRewarm(projectId);
        }
    }

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
        if (cachedVersion >= 0 && mongoVersion > cachedVersion) {
            log.info("[OwlApiCoord] Version mismatch for project {} (mongo={}, cached={}) — evicting OWLAPI cache",
                    projectId, mongoVersion, cachedVersion);
            ontologyCache.evict(projectId);
            desktopOntologyLoader.scheduleRewarm(projectId);
        }
    }
}
