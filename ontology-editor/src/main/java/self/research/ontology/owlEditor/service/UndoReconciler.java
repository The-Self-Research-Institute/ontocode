package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
public class UndoReconciler {

    enum State { SOURCE, TARGET, CONFLICT, UNKNOWN, ALWAYS }

    private record Step(State state, boolean data, Runnable apply) {}

    private record EntryPlan(HistoryChange entry, Plan plan, List<Step> steps) {}

    private final UndoIntentService intents;
    private final HistorySyncService historySync;
    private final RollbackMutationPlanner planner;
    private final RollbackGraphFacts facts;
    private final OntologyMutationService mutations;
    private final RollbackActionRunner actions;
    private final ProjectWriteLockRegistry locks;

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
                plans.add(plan(intent.getProjectId(), entry, undo, intent.getUndoAuditId()));
            }
        }
        if (plans.isEmpty()) {
            intents.fail(intent, "Nothing had been recorded for this request, so nothing was changed");
            return;
        }
        List<Step> steps = plans.stream().flatMap(p -> p.steps().stream()).toList();
        boolean notStarted = intent.getStage() == null || intent.getStage() == Stage.STARTED;
        if (steps.stream().anyMatch(s -> s.state() == State.UNKNOWN)) {
            if (notStarted) {
                intents.fail(intent, "The request stopped before it started changing anything");
            } else {
                intents.needsAttention(intent, "This kind of change can't be checked after an interruption");
            }
            return;
        }
        List<Step> data = steps.stream().filter(Step::data).toList();
        if (data.stream().anyMatch(s -> s.state() == State.CONFLICT) || historyContradicts(plans, data, undo)) {
            intents.needsAttention(intent, "The ontology no longer matches the recorded change");
            return;
        }
        if (data.stream().noneMatch(s -> s.state() == State.TARGET)) {
            intents.fail(intent, "The request stopped before anything was applied, so it is safe to try again");
            return;
        }
        int applied = 0;
        for (EntryPlan entryPlan : plans) {
            for (Step step : entryPlan.steps()) {
                if (step.state() == State.SOURCE || step.state() == State.ALWAYS) {
                    step.apply().run();
                    applied++;
                }
            }
            markHistory(entryPlan, undo, intent.getAuditId());
        }
        log.warn("[UNDO] Finished interrupted undo operation {} after restart ({} step(s) completed)", intent.getId(), applied);
        intents.commit(intent, intent.getAuditId(), plans.size());
    }

    private boolean historyContradicts(List<EntryPlan> plans, List<Step> data, boolean undo) {
        boolean someSource = data.stream().anyMatch(s -> s.state() == State.SOURCE);
        boolean marked = plans.stream().anyMatch(p -> p.entry().isReverted() == undo);
        return someSource && marked && data.stream().noneMatch(s -> s.state() == State.TARGET);
    }

    private EntryPlan plan(String projectId, HistoryChange entry, boolean undo, String undoAuditId) {
        Plan plan = undo
                ? planner.planUndo(entry, RollbackMutationPlanner.active(entry))
                : planner.planRedo(entry, RollbackMutationPlanner.revertedBy(entry, undoAuditId));
        List<Step> steps = new ArrayList<>();
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        if (entry.carriesExactDelta()) {
            State state = graphState(projectId, entry, undo);
            steps.add(new Step(state, true, () -> plan.rawUpdates()
                    .forEach(update -> mutations.applyRawUpdate(projectId, update, draft, entry.getUserId()))));
        } else if (!plan.mutations().isEmpty() || !plan.rawUpdates().isEmpty()) {
            steps.add(new Step(State.UNKNOWN, true, () -> { }));
        }
        for (RollbackAction action : plan.actions()) {
            steps.add(actionStep(projectId, entry, action));
        }
        return new EntryPlan(entry, plan, steps);
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
