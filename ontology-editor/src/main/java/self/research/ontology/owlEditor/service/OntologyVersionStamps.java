package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.common.preview.RulesFingerprint;
import self.research.ontology.owlEditor.model.SwrlRuleEntity;
import self.research.ontology.owlEditor.repository.SwrlRuleRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class OntologyVersionStamps {

    private final MainGraphRevisionService revisions;
    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;

    @Autowired(required = false)
    private SwrlRuleRepository swrlRules;

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
        if (swrlRules == null) {
            return RulesFingerprint.NONE;
        }
        List<String[]> enabled = new ArrayList<>();
        for (SwrlRuleEntity rule : swrlRules.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (rule.isEnabled()) {
                enabled.add(new String[]{rule.getRuleName(), rule.getRuleText()});
            }
        }
        return RulesFingerprint.of(enabled);
    }

    public PreviewStamp current(String projectId, boolean draft, String userId) {
        return draft
                ? new PreviewStamp(null, draftVersion(projectId, userId), rulesFingerprint(projectId))
                : new PreviewStamp(publicRevision(projectId), null, rulesFingerprint(projectId));
    }
}
