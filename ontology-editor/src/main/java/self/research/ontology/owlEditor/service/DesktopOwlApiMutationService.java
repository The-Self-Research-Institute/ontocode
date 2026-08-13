package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.io.IOException;
import java.util.List;

/**
 * Desktop OWLAPI-first mutation path: direct axiom patch when possible, otherwise
 * in-memory SPARQL UPDATE — never requires Fuseki for structured editor mutations.
 */
@Service
@Conditional(FastOpenCondition.class)
public class DesktopOwlApiMutationService {

    private static final Logger log = LoggerFactory.getLogger(DesktopOwlApiMutationService.class);

    private final ProjectOntologyCache ontologyCache;
    private final OwlApiMutationPatcher patcher;
    private final InMemorySparqlOntologyMutator sparqlMutator;
    private final StorageManager storageManager;
    @Nullable
    private final DesktopOntologyLoader desktopOntologyLoader;
    @Nullable
    private final ProjectImportService projectImportService;

    @Nullable
    private final DesktopFusekiSyncScheduler fusekiSyncScheduler;

    @Value("${ontocode.desktop.owlapi-first:false}")
    private boolean owlApiFirst;

    public DesktopOwlApiMutationService(ProjectOntologyCache ontologyCache,
                                        OwlApiMutationPatcher patcher,
                                        InMemorySparqlOntologyMutator sparqlMutator,
                                        StorageManager storageManager,
                                        @Nullable DesktopOntologyLoader desktopOntologyLoader,
                                        @Nullable ProjectImportService projectImportService,
                                        @Nullable DesktopFusekiSyncScheduler fusekiSyncScheduler) {
        this.ontologyCache = ontologyCache;
        this.patcher = patcher;
        this.sparqlMutator = sparqlMutator;
        this.storageManager = storageManager;
        this.desktopOntologyLoader = desktopOntologyLoader;
        this.projectImportService = projectImportService;
        this.fusekiSyncScheduler = fusekiSyncScheduler;
    }

    /**
     * @return true when the mutation was applied entirely in OWLAPI (no Fuseki).
     */
    public boolean tryApply(String projectId, List<OntologyMutationService.MutationOp> ops, String sparqlUpdate) {
        if (!owlApiFirst || ops == null || ops.isEmpty() || !ontologyCache.has(projectId)) {
            return false;
        }

        boolean patched = patcher.tryPatch(projectId, ops);
        if (!patched) {
            patched = sparqlMutator.tryApply(projectId, sparqlUpdate);
        }
        if (!patched) {
            return false;
        }

        try {
            if (desktopOntologyLoader != null) {
                desktopOntologyLoader.persistToDisk(projectId);
            }
            if (projectImportService != null) {
                projectImportService.markFusekiSyncPendingPublic(projectId);
            }
            if (fusekiSyncScheduler != null) {
                fusekiSyncScheduler.scheduleAfterMutation(projectId);
            }
            // OntologyMutationService's full mutation path always calls this after a
            // real mutation (see its invalidatePublicCodeViewCache) — this fast path
            // bypasses that service entirely, and until now skipped this call too.
            // Left uninvalidated, /api/ontology/export/{projectId} keeps serving the
            // stale cached document forever (Fuseki and the live OWLAPI model both
            // update correctly; only this file-backed export cache didn't), which is
            // exactly what SWRL fetches its ontology from — so anything added through
            // this fast path (e.g. new individuals) was invisible to SWRL rules.
            storageManager.clearCodeViewCache(projectId);
        } catch (IOException e) {
            log.warn("[OwlApiDesktop] Persist after mutation failed for {}: {}", projectId, e.getMessage());
            return false;
        }

        log.info("[OwlApiDesktop] Mutation applied via OWLAPI for project={} (Fuseki sync deferred)", projectId);
        return true;
    }
}
