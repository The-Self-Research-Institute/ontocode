package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;

final class UndoBatches {

    private UndoBatches() {
    }

    static void apply(OntologyMutationService mutations, String projectId, HistoryChange entry, List<String> updates,
                      boolean draft, boolean wholeEntry) {
        boolean track = wholeEntry && entry.carriesExactDelta() && updates.size() > 1;
        int batchTriples = Limit.DELTA_BATCH_TRIPLES.value();
        for (int i = 0; i < updates.size(); i++) {
            mutations.applyRawUpdate(projectId, updates.get(i), draft, entry.getUserId());
            if (track) {
                UndoOperationContext.batchApplied(entry.getId(), i + 1, batchTriples);
            }
        }
    }
}
