package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

@Slf4j
@Service
public class UndoReconciler {

    enum State { SOURCE, TARGET, CONFLICT, UNKNOWN, ALWAYS, PARTIAL, ROLL_BACK }

    private record Step(State state, boolean data, Runnable apply, BooleanSupplier putBack) {
        Step(State state, boolean data, Runnable apply) {
            this(state, data, apply, () -> false);
        }
    }

    private record EntryPlan(HistoryChange entry, Plan plan, List<Step> steps, boolean touched) {}

    private final UndoIntentService intents;
    private final HistorySyncService historySync;
    private final RollbackMutationPlanner planner;
    private final RollbackGraphFacts facts;
    private final OntologyMutationService mutations;
    private final RollbackActionRunner actions;
    private final ProjectWriteLockRegistry locks;
    private final PartialUndoRepair partial;

    @Autowired(required = false)
    private OntologyHistoryService history;

    public UndoReconciler(UndoIntentService intents, HistorySyncService historySync, RollbackMutationPlanner planner,
                          RollbackGraphFacts facts, OntologyMutationService mutations, RollbackActionRunner actions,
                          ProjectWriteLockRegistry locks) {
        this.intents = intents;
        this.historySync = historySync;
        this.planner = planner;
        this.facts = facts;
        this.mutations = mutations;
        this.actions = actions;
        this.locks = locks;
        this.partial = new PartialUndoRepair(facts, mutations);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            Map<String, Integer> perProject = new LinkedHashMap<>();
            intents.unresolvedEverywhere().forEach(intent -> perProject.merge(intent.getProjectId(), 1, Integer::sum));
            for (String projectId : perProject.keySet()) {
                try {
                    locks.runExclusive(projectId, () -> resolveLocked(projectId));
                } catch (Exception e) {
                    log.error("[UNDO] Could not reconcile interrupted undo operations of project {}: {}", projectId, e.getMessage(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("[UNDO] Startup reconciliation of undo operations failed: {}", e.getMessage(), e);
        }
    }

    public int resolveLocked(String projectId) {
        int resolved = 0;
        for (UndoIntentDocument intent : intents.unresolved(projectId)) {
            reconcile(intent);
            resolved++;
        }
        return resolved;
    }

    void reconcile(UndoIntentDocument intent) {
        try {
            decide(intent);
        } catch (RuntimeException e) {
            log.error("[UNDO] Reconciling undo operation {} failed: {}", intent.getId(), e.getMessage(), e);
            intents.needsAttention(intent, "It could not be checked after the interruption: " + e.getMessage());
        }
    }

    private void decide(UndoIntentDocument intent) {
        boolean undo = ChangeRollbackService.UNDO.equals(intent.getDirection());
        List<EntryPlan> plans = new ArrayList<>();
        for (String id : intent.getChangeIds() == null ? List.<String>of() : intent.getChangeIds()) {
            HistoryChange entry = historySync.getHistoryChange(id);
            if (entry != null) {
                plans.add(plan(intent, entry, undo));
            }
        }
        if (plans.isEmpty()) {
            intents.fail(intent, "Nothing had been recorded for this request, so nothing was changed");
            return;
        }
        List<Step> steps = plans.stream().flatMap(p -> p.steps().stream()).toList();
        boolean notStarted = (intent.getStage() == null || intent.getStage() == Stage.STARTED)
                && intent.getAppliedBatches() == 0 && !intent.isBatchInFlight();
        if (steps.stream().anyMatch(s -> s.state() == State.UNKNOWN)) {
            if (notStarted) {
                intents.fail(intent, "The request stopped before it started changing anything");
            } else {
                intents.needsAttention(intent, "This kind of change can't be checked after an interruption");
            }
            return;
        }
        List<Step> data = steps.stream().filter(Step::data).toList();
        if (data.stream().anyMatch(s -> s.state() == State.CONFLICT || s.state() == State.ROLL_BACK)
                || historyContradicts(plans, data, undo)) {
            putBack(intent, plans, undo);
            return;
        }
        if (data.stream().noneMatch(s -> s.state() == State.TARGET || s.state() == State.PARTIAL)) {
            intents.fail(intent, "The request stopped before anything was applied, so it is safe to try again");
            return;
        }
        int applied = 0;
        for (EntryPlan entryPlan : plans) {
            for (Step step : entryPlan.steps()) {
                if (step.state() == State.SOURCE || step.state() == State.ALWAYS || step.state() == State.PARTIAL) {
                    step.apply().run();
                    applied++;
                }
            }
            markHistory(entryPlan, undo, intent.getAuditId());
        }
        log.warn("[UNDO] Finished interrupted undo operation {} after restart ({} step(s) completed)", intent.getId(), applied);
        intents.commit(intent, intent.getAuditId(), plans.size());
    }

    private void putBack(UndoIntentDocument intent, List<EntryPlan> plans, boolean undo) {
        String projectId = intent.getProjectId();
        List<EntryPlan> finished = plans.stream().filter(p -> !p.touched() && changedByThis(p, intent, undo)).toList();
        Optional<EntryPlan> touched = plans.stream().filter(EntryPlan::touched).findFirst();
        if (finished.isEmpty() && touched.isEmpty()) {
            intents.needsAttention(intent, "The ontology no longer matches the recorded change");
            return;
        }
        List<EntryPlan> changed = new ArrayList<>(finished);
        touched.ifPresent(changed::add);
        Optional<Step> reversal = touched.flatMap(p -> p.steps().stream().filter(s -> s.state() == State.ROLL_BACK).findFirst());
        List<Runnable> restores = new ArrayList<>();
        for (EntryPlan entryPlan : finished) {
            Optional<Runnable> restore = !fullyApplied(entryPlan) || restoresDocuments(entryPlan) ? Optional.empty()
                    : partial.restore(projectId, entryPlan.entry(), undo);
            restore.ifPresent(restores::add);
        }
        if ((touched.isPresent() && reversal.isEmpty()) || restores.size() < finished.size()) {
            intents.needsAttention(intent, "It stopped part-way and the ontology changed since, so it could not be put back "
                    + "automatically. Still " + (undo ? "undone" : "redone") + ": " + labels(changed));
            return;
        }
        reversal.ifPresent(step -> step.apply().run());
        for (int i = 0; i < finished.size(); i++) {
            restores.get(i).run();
            unmark(finished.get(i), undo, intent);
            finished.get(i).steps().stream().filter(s -> s.state() == State.ALWAYS).forEach(s -> s.apply().run());
        }
        recordPutBack(intent, changed, undo);
        boolean verified = finished.stream().allMatch(p -> facts.deltaHolds(projectId, p.entry(), undo))
                && reversal.map(step -> step.putBack().getAsBoolean()).orElse(true);
        log.warn("[UNDO] Put back {} change(s) of stopped operation {} (verified={})", changed.size(), intent.getId(), verified);
        if (verified) {
            intents.fail(intent, "It stopped part-way, so every change it had already made was put back. It is safe to try again.");
        } else {
            intents.needsAttention(intent, "It stopped part-way and its changes were put back, but the ontology could not be "
                    + "confirmed to match its earlier state: " + labels(changed));
        }
    }

    private static boolean changedByThis(EntryPlan entryPlan, UndoIntentDocument intent, boolean undo) {
        HistoryChange entry = entryPlan.entry();
        return undo ? entry.isReverted() && intent.getAuditId() != null && intent.getAuditId().equals(entry.getRevertedAuditId())
                : fullyApplied(entryPlan);
    }

    private static boolean fullyApplied(EntryPlan entryPlan) {
        List<Step> data = entryPlan.steps().stream().filter(Step::data).toList();
        return !entryPlan.touched() && !data.isEmpty() && data.stream().allMatch(s -> s.state() == State.TARGET);
    }

    private static boolean restoresDocuments(EntryPlan entryPlan) {
        return entryPlan.plan().actions().stream().anyMatch(a -> RollbackAction.RESTORE_DOCUMENT.equals(a.type()));
    }

    private static String labels(List<EntryPlan> plans) {
        List<String> names = plans.stream().map(p -> p.entry().getEntityLabel() != null ? p.entry().getEntityLabel()
                : p.entry().getOperationType() != null ? p.entry().getOperationType() : p.entry().getId()).toList();
        return names.size() <= 5 ? String.join(", ", names)
                : String.join(", ", names.subList(0, 5)) + " and " + (names.size() - 5) + " more";
    }

    private void unmark(EntryPlan entryPlan, boolean undo, UndoIntentDocument intent) {
        HistoryChange entry = entryPlan.entry();
        List<HistoryChange.SubChange> subs = undo ? RollbackMutationPlanner.revertedBy(entry, intent.getAuditId())
                : entry.getSubChanges() == null ? List.of() : entry.getSubChanges();
        String audit = undo ? null : intent.getUndoAuditId();
        historySync.setSubChangesReverted(entry.getId(), subs.stream().map(HistoryChange.SubChange::getId).toList(), !undo, audit);
        historySync.setEntryReverted(entry.getId(), !undo, audit);
    }

    private void recordPutBack(UndoIntentDocument intent, List<EntryPlan> changed, boolean undo) {
        if (history == null || changed.isEmpty()) {
            return;
        }
        HistoryChange first = changed.get(0).entry();
        history.recordEdit(intent.getProjectId(), intent.getActor(), intent.getActor(), "ROLLBACK_REVERSED", null, null,
                null, null, (undo ? "Undo" : "Redo") + " stopped part-way, so these changes were put back: " + labels(changed),
                null, null, first.isDraft(), ChangeOrigin.rollback(first.getChangeSetId(), intent.getAuditId()));
    }

    private boolean historyContradicts(List<EntryPlan> plans, List<Step> data, boolean undo) {
        boolean someSource = data.stream().anyMatch(s -> s.state() == State.SOURCE);
        boolean marked = plans.stream().anyMatch(p -> p.entry().isReverted() == undo);
        return someSource && marked && data.stream().noneMatch(s -> s.state() == State.TARGET);
    }

    private EntryPlan plan(UndoIntentDocument intent, HistoryChange entry, boolean undo) {
        String projectId = intent.getProjectId();
        Plan plan = undo
                ? planner.planUndo(entry, RollbackMutationPlanner.active(entry))
                : planner.planRedo(entry, RollbackMutationPlanner.revertedBy(entry, intent.getUndoAuditId()));
        List<Step> steps = new ArrayList<>();
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        boolean touched = false;
        if (entry.carriesExactDelta()) {
            State state = graphState(projectId, entry, undo);
            boolean recorded = entry.getId().equals(intent.getBatchChangeId())
                    && (intent.getAppliedBatches() > 0 || intent.isBatchInFlight());
            if (state == State.CONFLICT && recorded) {
                steps.add(resumeStep(intent, entry, undo));
                touched = true;
            } else {
                steps.add(new Step(state, true, () -> plan.rawUpdates()
                        .forEach(update -> mutations.applyRawUpdate(projectId, update, draft, entry.getUserId()))));
            }
        } else if (!plan.mutations().isEmpty() || !plan.rawUpdates().isEmpty()) {
            steps.add(new Step(State.UNKNOWN, true, () -> { }));
        }
        for (RollbackAction action : plan.actions()) {
            steps.add(actionStep(projectId, entry, action));
        }
        return new EntryPlan(entry, plan, steps, touched);
    }

    private Step resumeStep(UndoIntentDocument intent, HistoryChange entry, boolean undo) {
        PartialUndoRepair.Decision decision = partial.decide(intent.getProjectId(), entry, undo,
                intent.getAppliedBatches(), intent.getBatchTriples(), intent.isBatchInFlight(),
                (applied, inFlight) -> intents.recordBatches(intent, entry.getId(), applied, intent.getBatchTriples(), inFlight));
        State state = switch (decision.outcome()) {
            case FINISH -> State.PARTIAL;
            case ROLL_BACK -> State.ROLL_BACK;
            case STUCK -> State.CONFLICT;
            case UNKNOWN -> State.UNKNOWN;
        };
        return new Step(state, true, decision.run(), decision.putBack());
    }

    private State graphState(String projectId, HistoryChange entry, boolean undo) {
        if (facts.deltaHolds(projectId, entry, !undo)) {
            return State.TARGET;
        }
        return facts.deltaHolds(projectId, entry, undo) ? State.SOURCE : State.CONFLICT;
    }

    private Step actionStep(String projectId, HistoryChange entry, RollbackAction action) {
        Runnable run = () -> actions.run(projectId, action, entry.isDraft(), entry.getUserId());
        if (!RollbackAction.RESTORE_DOCUMENT.equals(action.type())) {
            return new Step(State.ALWAYS, false, run);
        }
        RollbackAction target = action.document() == null
                ? RollbackAction.restoreDocument(action.store(), action.documentId(), action.expectedDocument(), null)
                : RollbackAction.restoreDocument(action.store(), action.documentId(), action.document(), action.document());
        if (actions.holds(projectId, target, entry.isDraft(), entry.getUserId())) {
            return new Step(State.TARGET, true, run);
        }
        boolean source = actions.holds(projectId, action, entry.isDraft(), entry.getUserId());
        return new Step(source ? State.SOURCE : State.CONFLICT, true, run);
    }

    private void markHistory(EntryPlan entryPlan, boolean undo, String auditId) {
        HistoryChange entry = entryPlan.entry();
        String audit = undo ? auditId : null;
        historySync.setSubChangesReverted(entry.getId(), entryPlan.plan().handledSubChangeIds(), undo, audit);
        historySync.setEntryReverted(entry.getId(), undo, audit);
    }
}
