package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.delta.CanonicalGraph;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.List;
import java.util.concurrent.Callable;

@Component
public class RuleImportLedger implements RuleService.ImportSupport {

    private final HistoryChangeRepository history;
    private final RawUpdateHistory raw;
    private final OntologyMutationService mutations;
    private final ProjectWriteLockRegistry locks;
    private final OntologyVersionStamps stamps;

    @Autowired
    public RuleImportLedger(HistoryChangeRepository history, RawUpdateHistory raw,
                            @Lazy OntologyMutationService mutations, ProjectWriteLockRegistry locks,
                            @Autowired(required = false) OntologyVersionStamps stamps) {
        this.history = history;
        this.raw = raw;
        this.mutations = mutations;
        this.locks = locks;
        this.stamps = stamps;
    }

    @Override
    public <T> T exclusive(String projectId, Callable<T> work) throws Exception {
        return locks.runExclusive(projectId, work);
    }

    @Override
    public List<HistoryChange> byImportId(String projectId, String importId) {
        return history.findByProjectIdAndImportId(projectId, importId);
    }

    @Override
    public boolean recorded(String projectId, String changeSetId) {
        List<HistoryChange> entries = history.findByProjectIdAndChangeSetIdOrderByTimestampDesc(projectId, changeSetId);
        return !entries.isEmpty() && entries.stream().anyMatch(HistoryChange::carriesExactDelta);
    }

    @Override
    public Model snapshot(String projectId, boolean draft, String userId) {
        return raw.snapshot(projectId, draft, userId, true);
    }

    @Override
    public void restore(String projectId, boolean draft, String userId, Model before) {
        Model after = raw.snapshot(projectId, draft, userId, true);
        if (after == null) {
            throw new IllegalStateException("The graph could not be read to put it back");
        }
        Model[] scoped = RuleGraphScope.pair(before, after);
        ExactDelta delta = ExactDelta.between(CanonicalGraph.canonicalise(scoped[0]), CanonicalGraph.canonicalise(scoped[1]));
        if (!delta.isEmpty()) {
            mutations.applyRawUpdate(projectId, DeltaSparql.reverse(delta), draft, userId);
        }
        Model again = raw.snapshot(projectId, draft, userId, true);
        Model[] check = RuleGraphScope.pair(before, again);
        if (!Models.isomorphic(CanonicalGraph.canonicalise(check[0]), CanonicalGraph.canonicalise(check[1]))) {
            throw new IllegalStateException("The graph could not be put back exactly");
        }
    }

    @Override
    public PreviewStamp stamp(String projectId, boolean draft, String userId) {
        return stamps == null ? null : stamps.current(projectId, draft, userId);
    }
}
