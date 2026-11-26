package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.RollbackGraphFacts.BatchState;
import self.research.ontology.owlEditor.service.delta.DeltaBatches;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PartialUndoRepair {

    enum Outcome { FINISH, ROLL_BACK, STUCK, UNKNOWN }

    record Decision(Outcome outcome, Runnable run) {
        static Decision of(Outcome outcome) {
            return new Decision(outcome, () -> { });
        }
    }

    private final RollbackGraphFacts facts;
    private final OntologyMutationService mutations;

    PartialUndoRepair(RollbackGraphFacts facts, OntologyMutationService mutations) {
        this.facts = facts;
        this.mutations = mutations;
    }

    Decision decide(String projectId, HistoryChange entry, boolean undo, int applied, int batchTriples) {
        Optional<ExactDelta> delta = ExactReversal.deltaOf(entry);
        if (batchTriples <= 0 || applied <= 0 || delta.isEmpty() || ExactReversal.isRuleChange(entry)) {
            return Decision.of(Outcome.UNKNOWN);
        }
        List<ExactDelta> batches = DeltaBatches.split(delta.get(), batchTriples).stream()
                .filter(batch -> !batch.isEmpty()).toList();
        if (applied >= batches.size()) {
            return Decision.of(Outcome.STUCK);
        }
        Optional<List<BatchState>> states = facts.batchStates(projectId, entry, batches);
        if (states.isEmpty()) {
            return Decision.of(Outcome.UNKNOWN);
        }
        boolean[] done = new boolean[batches.size()];
        boolean clear = true;
        for (int i = 0; i < batches.size(); i++) {
            BatchState state = states.get().get(i);
            boolean source = undo ? state.edited() : state.original();
            boolean target = undo ? state.original() : state.edited();
            if (i < applied) {
                done[i] = true;
                clear &= target;
            } else if (i > applied) {
                clear &= source;
            } else {
                done[i] = addsTriples(batches.get(i), undo) ? target : !source;
                clear &= source || target;
            }
        }
        if (clear) {
            List<String> rest = new ArrayList<>();
            for (int i = 0; i < batches.size(); i++) {
                if (!done[i]) {
                    rest.add(render(batches.get(i), undo));
                }
            }
            return new Decision(Outcome.FINISH, () -> send(projectId, entry, rest));
        }
        List<String> reversal = new ArrayList<>();
        for (int i = batches.size() - 1; i >= 0; i--) {
            if (done[i]) {
                BatchState state = states.get().get(i);
                if (!(undo ? state.original() : state.edited())) {
                    return Decision.of(Outcome.STUCK);
                }
                reversal.add(render(batches.get(i), !undo));
            }
        }
        return new Decision(Outcome.ROLL_BACK, () -> send(projectId, entry, reversal));
    }

    private static boolean addsTriples(ExactDelta batch, boolean undo) {
        return !(undo ? batch.deleted() : batch.inserted()).isEmpty();
    }

    private static String render(ExactDelta batch, boolean undo) {
        return undo ? DeltaSparql.reverse(batch) : DeltaSparql.forward(batch);
    }

    private void send(String projectId, HistoryChange entry, List<String> updates) {
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        for (String update : updates) {
            mutations.applyRawUpdate(projectId, update, draft, entry.getUserId());
        }
    }
}
