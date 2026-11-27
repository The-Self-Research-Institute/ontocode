package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;

final class UndoBatches {

    private UndoBatches() {
    }

    static void apply(OntologyMutationService mutations, String projectId, HistoryChange entry, List<String> updates,
                      boolean draft, boolean wholeEntry) {
        boolean track = wholeEntry && entry.carriesExactDelta() && !updates.isEmpty();
        int batchTriples = Limit.DELTA_BATCH_TRIPLES.value();
        for (int i = 0; i < updates.size(); i++) {
            if (track) {
                UndoOperationContext.batchProgress(entry.getId(), i, batchTriples, true);
            }
            mutations.applyRawUpdate(projectId, updates.get(i), draft, entry.getUserId());
        }
        if (track) {
            UndoOperationContext.batchProgress(entry.getId(), updates.size(), batchTriples, false);
        }
    }
}
