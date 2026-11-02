package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.service.delta.CanonicalGraph;

@Slf4j
@Component
public class RawUpdateHistory {

    private final SparqlDatasetService datasetService;

    @Autowired(required = false) @Nullable
    private OntologyHistoryService historyService;

    @Autowired(required = false) @Lazy @Nullable
    private DraftTrackingService draftTrackingService;

    @Autowired(required = false) @Nullable
    private DesktopOwlApiMutationService desktopOwlApi;

    @Autowired(required = false) @Nullable
    private ProjectOntologyCache ontologyCache;

    public RawUpdateHistory(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public boolean enabled() {
        return historyService != null;
    }

    public Model snapshot(String projectId, boolean draft, String userId) {
        return snapshot(projectId, draft, userId, false);
    }

    public Model snapshot(String projectId, boolean draft, String userId, boolean unbounded) {
        if (historyService == null || draft && (userId == null || userId.isBlank())) {
            return null;
        }
        try {
            if (!draft && desktopOwlApi != null && desktopOwlApi.isAuthoritative(projectId)) {
                return OwlApiModelSnapshot.of(ontologyCache, projectId).orElse(null);
            }
            return draft ? readDraft(projectId, userId, unbounded) : readPublic(projectId);
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not read the graph before the raw update for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    public void record(String projectId, boolean draft, String userId, String username, Model before) {
        record(projectId, draft, userId, username, before, null);
    }

    public void record(String projectId, boolean draft, String userId, String username, Model before,
                       ChangeOrigin base) {
        if (before == null || historyService == null) {
            return;
        }
        boolean rule = base != null && base.ruleNote() != null;
        Model after = snapshot(projectId, draft, userId, rule);
        if (after == null) {
            return;
        }
        if (rule) {
            Model[] scoped = RuleGraphScope.pair(before, after);
            recordRule(projectId, draft, userId, username, scoped[0], scoped[1], base);
            return;
        }
        boolean stable = before.size() <= Limit.WHOLE_GRAPH_MAX_TRIPLES.value() && after.size() <= Limit.WHOLE_GRAPH_MAX_TRIPLES.value();
        Model oldSide = stable ? CanonicalGraph.canonicalise(before) : before;
        Model newSide = stable ? CanonicalGraph.canonicalise(after) : after;
        ChangeOrigin origin = ExactOrigins.withDelta(base == null ? ChangeOrigin.manual() : base, before, after);
        boolean changed = new CodeViewHistoryRecorder(historyService, draft && !DraftRecordScope.active() ? draftTrackingService : null)
                .record(projectId, userId, username, oldSide, newSide, draft, origin, false);
        DraftRecordScope.observed(origin.changeSetId(), changed || !stable);
    }

    private void recordRule(String projectId, boolean draft, String userId, String username, Model before, Model after,
                            ChangeOrigin base) {
        ChangeOrigin origin = ExactOrigins.withRuleDelta(base, before, after);
        boolean changed = new CodeViewHistoryRecorder(historyService, draft && !DraftRecordScope.active() ? draftTrackingService : null)
                .record(projectId, userId, username, CanonicalGraph.canonicalise(before), CanonicalGraph.canonicalise(after),
                        draft, origin, false);
        DraftRecordScope.observed(origin.changeSetId(), changed);
    }

    private Model readPublic(String projectId) {
        try (var result = datasetService.execConstructAll(projectId)) {
            return QueryResults.asModel(result);
        }
    }

    private Model readDraft(String projectId, String userId, boolean unbounded) {
        SparqlDatasetService.ProjectGraphTarget target = datasetService.graphTarget(projectId);
        String graph = datasetService.getDraftGraphUri(projectId, userId);
        String query = "CONSTRUCT { ?s ?p ?o } FROM <" + graph + "> WHERE { ?s ?p ?o }"
                + (unbounded ? "" : " LIMIT " + (Limit.WHOLE_GRAPH_MAX_TRIPLES.value() + 1));
        try (RepositoryConnection connection = TolerantRdfParsing.apply(target.repository().getConnection())) {
            return QueryResults.asModel(connection.prepareGraphQuery(query).evaluate());
        }
    }
}
