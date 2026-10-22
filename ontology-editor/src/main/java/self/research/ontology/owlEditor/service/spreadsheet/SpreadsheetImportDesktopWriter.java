package self.research.ontology.owlEditor.service.spreadsheet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.service.DesktopFusekiSyncScheduler;
import self.research.ontology.owlEditor.service.DesktopOntologyLoader;
import self.research.ontology.owlEditor.service.InMemorySparqlOntologyMutator;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.io.IOException;
import java.util.List;

@Component
public class SpreadsheetImportDesktopWriter {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetImportDesktopWriter.class);

    private final ObjectProvider<ProjectOntologyCache> cache;
    private final ObjectProvider<InMemorySparqlOntologyMutator> mutator;
    private final ObjectProvider<DesktopOntologyLoader> loader;
    private final ObjectProvider<ProjectImportService> projectImports;
    private final ObjectProvider<DesktopFusekiSyncScheduler> syncScheduler;
    private final ObjectProvider<OntologyMutationService> mutations;
    private final StorageManager storageManager;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Value("${ontocode.desktop.owlapi-first:false}")
    private boolean owlApiFirst;

    public SpreadsheetImportDesktopWriter(ObjectProvider<ProjectOntologyCache> cache,
                                          ObjectProvider<InMemorySparqlOntologyMutator> mutator,
                                          ObjectProvider<DesktopOntologyLoader> loader,
                                          ObjectProvider<ProjectImportService> projectImports,
                                          ObjectProvider<DesktopFusekiSyncScheduler> syncScheduler,
                                          ObjectProvider<OntologyMutationService> mutations,
                                          StorageManager storageManager) {
        this.cache = cache;
        this.mutator = mutator;
        this.loader = loader;
        this.projectImports = projectImports;
        this.syncScheduler = syncScheduler;
        this.mutations = mutations;
        this.storageManager = storageManager;
    }

    public boolean apply(String projectId, List<String> updates) {
        if (!desktopMode || !owlApiFirst || updates == null || updates.isEmpty()) {
            return false;
        }
        ProjectOntologyCache ontologies = cache.getIfAvailable();
        InMemorySparqlOntologyMutator sparql = mutator.getIfAvailable();
        if (ontologies == null || sparql == null || !ontologies.has(projectId)) {
            return false;
        }
        if (!sparql.tryApply(projectId, String.join(" ;\n", updates))) {
            return false;
        }
        try {
            DesktopOntologyLoader desktop = loader.getIfAvailable();
            if (desktop != null) {
                desktop.persistToDisk(projectId);
            }
        } catch (IOException e) {
            log.warn("[SpreadsheetImport] Could not write the desktop draft file for {}: {}", projectId, e.getMessage());
        }
        ProjectImportService imports = projectImports.getIfAvailable();
        if (imports != null) {
            imports.markFusekiSyncPendingPublic(projectId);
        }
        DesktopFusekiSyncScheduler scheduler = syncScheduler.getIfAvailable();
        if (scheduler != null) {
            scheduler.scheduleAfterMutation(projectId);
        }
        storageManager.clearCodeViewCache(projectId);
        OntologyMutationService mutationService = mutations.getIfAvailable();
        if (mutationService != null) {
            mutationService.invalidateEntityCaches(projectId, null, false);
            mutationService.bumpMainGraphRevision(projectId);
            mutationService.invalidateReasonerCaches(projectId);
        }
        log.info("[SpreadsheetImport] Applied import undo/redo to the desktop ontology for {} ({} update(s))",
            projectId, updates.size());
        return true;
    }

    public void syncBeforeRead(String projectId) {
        if (!desktopMode) {
            return;
        }
        ProjectImportService imports = projectImports.getIfAvailable();
        if (imports != null && imports.isFusekiSyncPending(projectId)) {
            imports.syncProjectToFuseki(projectId);
        }
    }
}
