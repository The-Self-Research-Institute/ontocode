package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.delta.DeltaBatches;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.List;
import java.util.UUID;

@Component
public class DraftMergeApplier {

    public static final String SESSION_PREFIX = "merge-draft-";

    private final OntologyMutationService mutations;
    private final DraftTrackingService draftTracking;
    private final OntologyHistoryService history;
    private final DraftCopyService draftCopies;
    private final ProjectWriteLockRegistry locks;

    public DraftMergeApplier(OntologyMutationService mutations, DraftTrackingService draftTracking,
                             OntologyHistoryService history, DraftCopyService draftCopies,
                             ProjectWriteLockRegistry locks) {
        this.mutations = mutations;
        this.draftTracking = draftTracking;
        this.history = history;
        this.draftCopies = draftCopies;
        this.locks = locks;
    }

    public static boolean isMergeSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(SESSION_PREFIX);
    }

    public void requireReady(String projectId, String userId) {
        if (!draftCopies.isReady(projectId, userId)) {
            throw new DraftNotReadyException();
        }
    }

    public String apply(String projectId, String userId, String username, ExactDelta delta, String summary) {
        requireReady(projectId, userId);
        String entity = OntologyMergeService.MERGE_IRI_PREFIX + UUID.randomUUID();
        ChangeOrigin origin = ChangeOrigin.manual().withExact(new ExactCarrier(delta));
        return locks.exclusive(projectId, () -> {
            for (ExactDelta batch : DeltaBatches.split(delta, Limit.DELTA_BATCH_TRIPLES.value())) {
                mutations.applyRawUpdate(projectId, DeltaSparql.forward(batch), true, userId);
            }
            history.recordEdit(projectId, userId, username, OntologyMergeService.MERGE_OPERATION, entity,
                    "Ontology merge", null, null, summary, null, null, true, origin);
            draftTracking.recordDrafts(projectId, userId, username, List.of(marker(entity)),
                    SESSION_PREFIX + UUID.randomUUID());
            return origin.changeSetId();
        });
    }

    private static MutationOp marker(String entity) {
        return new MutationOp(OntologyMergeService.MERGE_OPERATION, entity, "Ontology merge", null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
