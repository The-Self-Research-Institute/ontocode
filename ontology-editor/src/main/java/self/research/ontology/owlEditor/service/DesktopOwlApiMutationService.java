package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Conditional(FastOpenCondition.class)
public class DesktopOwlApiMutationService {

    private static final Logger log = LoggerFactory.getLogger(DesktopOwlApiMutationService.class);

    private final ProjectOntologyCache ontologyCache;
    private final OwlApiMutationPatcher patcher;
    private final InMemorySparqlOntologyMutator sparqlMutator;
    private final StorageManager storageManager;
    @Nullable
    private final ProjectImportService projectImportService;

    @Nullable
    private final DesktopFusekiSyncScheduler fusekiSyncScheduler;
    @Nullable
    private final DesktopDraftSaver draftSaver;

    @Autowired(required = false) @Nullable
    private DesktopEditJournal editJournal;
    @Autowired(required = false) @Nullable
    private DesktopDraftCompactor compactor;

    @Value("${ontocode.desktop.owlapi-first:false}")
    private boolean owlApiFirst;

    public DesktopOwlApiMutationService(ProjectOntologyCache ontologyCache,
                                        OwlApiMutationPatcher patcher,
                                        InMemorySparqlOntologyMutator sparqlMutator,
                                        StorageManager storageManager,
                                        @Nullable ProjectImportService projectImportService,
                                        @Nullable DesktopFusekiSyncScheduler fusekiSyncScheduler,
                                        @Nullable DesktopDraftSaver draftSaver) {
        this.ontologyCache = ontologyCache;
        this.patcher = patcher;
        this.sparqlMutator = sparqlMutator;
        this.storageManager = storageManager;
        this.projectImportService = projectImportService;
        this.fusekiSyncScheduler = fusekiSyncScheduler;
        this.draftSaver = draftSaver;
    }

    public void refuseIfTripleStoreIsBehind(String projectId) {
        if (owlApiFirst && projectImportService != null && projectImportService.isFusekiSyncPending(projectId)) {
            throw new IllegalStateException("This ontology is still being prepared on this computer, so the change "
                    + "was not applied. Wait a moment and try again.");
        }
    }

    public boolean isAuthoritative(String projectId) {
        return owlApiFirst && ontologyCache.has(projectId);
    }

    public boolean exportLiveModel(String projectId, java.nio.file.Path target) {
        if (!isAuthoritative(projectId)) {
            return false;
        }
        return ontologyCache.get(projectId).map(entry -> {
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(target)) {
                entry.manager().saveOntology(entry.ontology(), new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(), out);
                return true;
            } catch (Exception e) {
                log.warn("[OwlApiDesktop] Could not export the live model for {}: {}", projectId, e.getMessage());
                return false;
            }
        }).orElse(false);
    }

    public java.util.Optional<Boolean> declaresEntity(String projectId, String iri) {
        if (!isAuthoritative(projectId)) {
            return java.util.Optional.empty();
        }
        return ontologyCache.get(projectId).map(entry -> entry.ontology().containsEntityInSignature(
                org.semanticweb.owlapi.model.IRI.create(iri), org.semanticweb.owlapi.model.parameters.Imports.INCLUDED));
    }

    public boolean tryApply(String projectId, List<OntologyMutationService.MutationOp> ops, String sparqlUpdate) {
        if (!owlApiFirst || ops == null || ops.isEmpty() || !ontologyCache.has(projectId)) {
            return false;
        }

        editStarting(projectId);
        try {
            Optional<EnactedChanges> watched = EnactedChanges.watch(ontologyCache, projectId);
            boolean patched;
            try {
                patched = patcher.tryPatch(projectId, ops);
            } finally {
                watched.ifPresent(EnactedChanges::close);
            }
            if (patched) {
                return afterApplied(projectId, watched.filter(EnactedChanges::sameOntology).orElse(null));
            }
            return sparqlMutator.tryApply(projectId, sparqlUpdate) && afterApplied(projectId, null);
        } finally {
            editFinished(projectId);
        }
    }

    public boolean tryApplyRaw(String projectId, String sparqlUpdate) {
        if (!owlApiFirst || sparqlUpdate == null || sparqlUpdate.isBlank() || !ontologyCache.has(projectId)) {
            return false;
        }
        editStarting(projectId);
        try {
            return sparqlMutator.tryApply(projectId, sparqlUpdate) && afterApplied(projectId, null);
        } finally {
            editFinished(projectId);
        }
    }

    private boolean afterApplied(String projectId, @Nullable EnactedChanges watched) {
        if (watched != null && watched.changes().isEmpty()) {
            return true;
        }
        if (!journal(projectId, watched) && draftSaver != null) {
            draftSaver.save(projectId);
        }
        if (projectImportService != null) {
            projectImportService.markFusekiSyncPendingPublic(projectId);
        }
        if (fusekiSyncScheduler != null) {
            fusekiSyncScheduler.scheduleAfterMutation(projectId);
        }
        storageManager.clearCodeViewCache(projectId);
        log.info("[OwlApiDesktop] Mutation applied via OWLAPI for project={} (Fuseki sync deferred)", projectId);
        return true;
    }

    private boolean journal(String projectId, @Nullable EnactedChanges watched) {
        if (watched == null || editJournal == null || draftSaver != null && draftSaver.failure(projectId).isPresent()) {
            return false;
        }
        List<OWLOntologyChange> changes = watched.changes();
        try {
            if (!editJournal.append(projectId, watched.ontology(), changes)) {
                return false;
            }
        } catch (IOException e) {
            List<OWLOntologyChange> reverse = new ArrayList<>();
            for (int i = changes.size() - 1; i >= 0; i--) {
                reverse.add(changes.get(i).reverseChange());
            }
            watched.manager().applyChanges(reverse);
            throw new IllegalStateException("This change could not be written to disk, so it was not applied: "
                    + e.getMessage(), e);
        }
        if (compactor != null) {
            compactor.scheduleAfterEdit(projectId);
        }
        return true;
    }

    private void editStarting(String projectId) {
        if (compactor != null) {
            compactor.editStarting(projectId);
        }
    }

    private void editFinished(String projectId) {
        if (compactor != null) {
            compactor.editFinished(projectId);
        }
    }
}
