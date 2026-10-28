package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.common.preview.RulesFingerprint;

@Service
public class OntologyVersionStamps {

    private final MainGraphRevisionService revisions;
    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;

    public OntologyVersionStamps(MainGraphRevisionService revisions, StorageManager storageManager,
                                 SparqlDatasetService datasetService) {
        this.revisions = revisions;
        this.storageManager = storageManager;
        this.datasetService = datasetService;
    }

    public long publicRevision(String projectId) {
        return revisions.getRevision(projectId);
    }

    public String draftVersion(String projectId, String userId) {
        return storageManager.getDraftGraphVersion(projectId, userId) + "." + datasetService.getDraftWriteStamp(projectId, userId);
    }

    public String rulesFingerprint(String projectId) {
        return RulesFingerprint.NONE;
    }

    public PreviewStamp current(String projectId, boolean draft, String userId) {
        return draft
                ? new PreviewStamp(null, draftVersion(projectId, userId), rulesFingerprint(projectId))
                : new PreviewStamp(publicRevision(projectId), null, rulesFingerprint(projectId));
    }
}
