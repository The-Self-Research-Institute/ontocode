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
import java.util.function.BooleanSupplier;

final class PartialUndoRepair {

    enum Outcome { FINISH, ROLL_BACK, STUCK, UNKNOWN }

    interface Progress {
        void record(int applied, boolean inFlight);
    }

    record Decision(Outcome outcome, Runnable run, BooleanSupplier putBack) {
        static Decision of(Outcome outcome) {
            return new Decision(outcome, () -> { }, () -> false);
        }
    }

    private final RollbackGraphFacts facts;
    private final OntologyMutationService mutations;

    PartialUndoRepair(RollbackGraphFacts facts, OntologyMutationService mutations) {
        this.facts = facts;
        this.mutations = mutations;
    }

    Decision decide(String projectId, HistoryChange entry, boolean undo, int applied, int batchTriples, boolean inFlight,
                    Progress progress) {
        Optional<ExactDelta> delta = ExactReversal.deltaOf(entry);
        if (batchTriples <= 0 || (applied <= 0 && !inFlight) || delta.isEmpty() || ExactReversal.isRuleChange(entry)) {
            return Decision.of(Outcome.UNKNOWN);
        }
        List<ExactDelta> batches = batches(delta.get(), batchTriples);
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
            return new Decision(Outcome.FINISH, () -> finish(projectId, entry, undo, batches, done, progress), () -> false);
        }
        for (int i = 0; i < batches.size(); i++) {
            BatchState state = states.get().get(i);
            if (done[i] && !(undo ? state.original() : state.edited())) {
                return Decision.of(Outcome.STUCK);
            }
        }
        return new Decision(Outcome.ROLL_BACK, () -> reverse(projectId, entry, undo, batches, done, progress),
                () -> backToSource(projectId, entry, undo, batches, done));
    }

    Optional<Runnable> restore(String projectId, HistoryChange entry, boolean undo) {
        Optional<ExactDelta> delta = ExactReversal.deltaOf(entry);
        if (!entry.carriesExactDelta() || delta.isEmpty() || ExactReversal.isRuleChange(entry)) {
            return Optional.empty();
        }
        List<String> updates = batches(delta.get(), Limit.DELTA_BATCH_TRIPLES.value()).stream()
                .map(batch -> render(batch, !undo)).toList();
        return Optional.of(() -> updates.forEach(update -> send(projectId, entry, update)));
    }

    private void finish(String projectId, HistoryChange entry, boolean undo, List<ExactDelta> batches, boolean[] done,
                        Progress progress) {
        for (int i = 0; i < batches.size(); i++) {
            if (!done[i]) {
                progress.record(i, true);
                send(projectId, entry, render(batches.get(i), undo));
            }
        }
        progress.record(batches.size(), false);
    }

    private void reverse(String projectId, HistoryChange entry, boolean undo, List<ExactDelta> batches, boolean[] done,
                         Progress progress) {
        for (int i = batches.size() - 1; i >= 0; i--) {
            if (done[i]) {
                progress.record(i, true);
                send(projectId, entry, render(batches.get(i), !undo));
            }
        }
        progress.record(0, false);
    }

    private boolean backToSource(String projectId, HistoryChange entry, boolean undo, List<ExactDelta> batches, boolean[] done) {
        Optional<List<BatchState>> states = facts.batchStates(projectId, entry, batches);
        if (states.isEmpty()) {
            return false;
        }
        for (int i = 0; i < batches.size(); i++) {
            BatchState state = states.get().get(i);
            if (done[i] && !(undo ? state.edited() : state.original())) {
                return false;
            }
        }
        return true;
    }

    private static List<ExactDelta> batches(ExactDelta delta, int batchTriples) {
        return DeltaBatches.split(delta, batchTriples).stream().filter(batch -> !batch.isEmpty()).toList();
    }

    private static boolean addsTriples(ExactDelta batch, boolean undo) {
        return !(undo ? batch.deleted() : batch.inserted()).isEmpty();
    }

    private static String render(ExactDelta batch, boolean undo) {
        return undo ? DeltaSparql.reverse(batch) : DeltaSparql.forward(batch);
    }

    private void send(String projectId, HistoryChange entry, String update) {
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        mutations.applyRawUpdate(projectId, update, draft, entry.getUserId());
    }
}
