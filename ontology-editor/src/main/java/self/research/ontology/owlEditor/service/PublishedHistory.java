package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class PublishedHistory {

    public static final String CHANGED_IN_PUBLISH = "This edit was published in a changed form, so it can't be undone exactly";

    private static final Logger log = LoggerFactory.getLogger(PublishedHistory.class);

    private final MongoTemplate mongoTemplate;
    private final SparqlDatasetService datasetService;
    private final OntologyHistoryService historyService;

    public PublishedHistory(MongoTemplate mongoTemplate, SparqlDatasetService datasetService,
                            OntologyHistoryService historyService) {
        this.mongoTemplate = mongoTemplate;
        this.datasetService = datasetService;
        this.historyService = historyService;
    }

    public Optional<DeltaCapture> begin(String projectId) {
        try {
            SparqlDatasetService.ProjectGraphTarget target = datasetService.graphTarget(projectId);
            return DeltaCapture.beginWholeGraph(target.repository(), target.graphUri());
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not read the public graph before publishing for project {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    public void reconcile(String projectId, String userId, Optional<DeltaCapture> capture) {
        if (capture.isEmpty()) {
            return;
        }
        try {
            DeltaCapture.Finish finished = capture.get().finishDetailed();
            ExactDelta committed = finished.unchanged() ? new ExactDelta(new LinkedHashModel(), new LinkedHashModel())
                    : finished.delta().orElse(null);
            if (committed != null) {
                apply(projectId, userId, committed);
            }
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not compare the published change with the drafts of project {}: {}", projectId, e.getMessage());
        }
    }

    private void apply(String projectId, String userId, ExactDelta committed) {
        List<HistoryChange> rows = mongoTemplate.find(Query.query(Criteria.where("projectId").is(projectId)
                .and("userId").is(userId).and("draft").is(true)), HistoryChange.class);
        List<HistoryChange> carriers = new ArrayList<>();
        for (HistoryChange row : rows) {
            if (row.getDocumentStore() != null || row.getCoveredBy() != null || row.isReverted()
                    || RollbackWorkSupport.isRollbackEntry(row)) {
                continue;
            }
            if (!row.carriesExactDelta()) {
                return;
            }
            carriers.add(row);
        }
        Model accountedDeleted = new LinkedHashModel();
        Model accountedInserted = new LinkedHashModel();
        List<HistoryChange> changed = new ArrayList<>();
        for (HistoryChange carrier : carriers) {
            ExactDelta delta = ExactReversal.deltaOf(carrier).orElse(null);
            if (delta != null && delta.coveredBy(committed)) {
                accountedDeleted.addAll(delta.deleted());
                accountedInserted.addAll(delta.inserted());
            } else {
                changed.add(carrier);
            }
        }
        for (HistoryChange row : changed) {
            block(row);
        }
        ExactDelta residual = committed.without(accountedDeleted, accountedInserted);
        if (!residual.isEmpty() && !residual.isTooLarge()) {
            recordResidual(projectId, userId, carriers, residual);
        }
    }

    private void block(HistoryChange row) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(row.getId())),
                new Update().set("undoBlockedReason", CHANGED_IN_PUBLISH).unset("exactDeleted").unset("exactInserted")
                        .set("exactCovers", 0), HistoryChange.class);
        mongoTemplate.updateMulti(Query.query(Criteria.where("coveredBy").is(row.getEditId())
                        .and("projectId").is(row.getProjectId())),
                new Update().set("undoBlockedReason", CHANGED_IN_PUBLISH).unset("coveredBy"), HistoryChange.class);
    }

    private void recordResidual(String projectId, String userId, List<HistoryChange> carriers, ExactDelta residual) {
        String username = carriers.isEmpty() ? userId : carriers.get(0).getUsername();
        ChangeOrigin origin = ChangeOrigin.manual().withExact(new ExactCarrier(residual));
        int added = residual.inserted().size();
        int removed = residual.deleted().size();
        historyService.recordEdit(projectId, userId, username, "publishDraft", null, "Published draft", null, null,
                "Published draft: " + added + " added, " + removed + " removed beyond your recorded edits", null, null,
                false, origin);
    }
}
