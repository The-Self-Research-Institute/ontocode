package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Kind;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ChangeRollbackService {

    public static final String UNDO = "UNDO";
    public static final String REDO = "REDO";

    public record Actor(String userId, String username) {}

    public record Item(String changeId, String subChangeId, String entityIRI, String entityLabel,
                       String predicate, String reason) {}

    public record Result(int status, boolean ok, boolean dryRun, String direction, String scopeId,
                         List<Item> applied, List<Item> skipped, String auditId, String message) {}

    private record Work(HistoryChange entry, Plan plan, List<HistoryChange.SubChange> targets,
                        List<Item> skipped, boolean completesEntry, boolean subLevel) {}

    static final String CHANGESET = "CHANGESET";
    static final String ENTRY = "ENTRY";
    static final String SUBCHANGE = "SUBCHANGE";

    record Request(String projectId, String direction, String level, String changeSetId, String changeId,
                           String subChangeId, Actor actor, boolean dryRun, String undoAuditId,
                           List<String> auditChangeIds) {
        Request withUndoAudit(String auditId) {
            return new Request(projectId, direction, level, changeSetId, changeId, subChangeId, actor, dryRun, auditId,
                    auditChangeIds);
        }
    }

    private final HistorySyncService historySync;
    private final RollbackMutationPlanner planner;
    private final RollbackGraphFacts facts;
    private final OntologyMutationService mutations;
    private final RollbackAuditRepository audits;
    private final ProjectWriteLockRegistry locks;
    private final RollbackRecorder recorder;
    private final DraftTrackingService draftTracking;

    @Autowired
    public ChangeRollbackService(HistorySyncService historySync, RollbackMutationPlanner planner,
                                 RollbackGraphFacts facts, OntologyMutationService mutations,
                                 RollbackAuditRepository audits, OntologyHistoryService history,
                                 ProjectWriteLockRegistry locks, DraftTrackingService draftTracking,
                                 @Autowired(required = false) @Nullable SimpMessagingTemplate messaging) {
        this.historySync = historySync;
        this.planner = planner;
        this.facts = facts;
        this.mutations = mutations;
        this.audits = audits;
        this.locks = locks;
        this.draftTracking = draftTracking;
        this.recorder = new RollbackRecorder(audits, history, messaging);
    }

    public Result undoChangeSet(String projectId, String changeSetId, Actor actor, boolean dryRun) {
        return run(new Request(projectId, UNDO, CHANGESET, changeSetId, null, null, actor, dryRun, null, null));
    }

    public Result redoChangeSet(String projectId, String changeSetId, Actor actor, boolean dryRun) {
        return run(new Request(projectId, REDO, CHANGESET, changeSetId, null, null, actor, dryRun, null, null));
    }

    public Result undoEntry(String projectId, String changeId, Actor actor, boolean dryRun) {
        return run(new Request(projectId, UNDO, ENTRY, null, changeId, null, actor, dryRun, null, null));
    }

    public Result undoSubChange(String projectId, String changeId, String subChangeId, Actor actor, boolean dryRun) {
        return run(new Request(projectId, UNDO, SUBCHANGE, null, changeId, subChangeId, actor, dryRun, null, null));
    }

    public List<HistoryChange> entriesForUndo(String projectId, String auditId) {
        RollbackAudit audit = findUndo(projectId, auditId);
        return audit == null ? List.of() : loadByIds(projectId, undoChangeIds(audit));
    }

    public Result redoUndo(String projectId, String auditId, Actor actor, boolean dryRun) {
        RollbackAudit audit = findUndo(projectId, auditId);
        if (audit == null) {
            return new Result(404, false, dryRun, REDO, auditId, List.of(), List.of(), null, "Undo not found");
        }
        String level = audit.getChangeSetId() != null ? CHANGESET : audit.getSubChangeId() != null ? SUBCHANGE : ENTRY;
        return run(new Request(projectId, REDO, level, audit.getChangeSetId(), audit.getHistoryChangeId(),
                audit.getSubChangeId(), actor, dryRun, audit.getId(), undoChangeIds(audit)));
    }

    private RollbackAudit findUndo(String projectId, String auditId) {
        if (auditId == null) {
            return null;
        }
        return audits.findById(auditId)
                .filter(a -> projectId.equals(a.getProjectId()))
                .filter(a -> a.getDirection() == null || UNDO.equals(a.getDirection()))
                .orElse(null);
    }

    private static List<String> undoChangeIds(RollbackAudit audit) {
        if (audit.getChangeIds() != null && !audit.getChangeIds().isEmpty()) {
            return audit.getChangeIds();
        }
        return audit.getHistoryChangeId() == null ? List.of() : List.of(audit.getHistoryChangeId());
    }

    private List<HistoryChange> loadByIds(String projectId, List<String> ids) {
        List<HistoryChange> entries = new ArrayList<>();
        for (String id : ids) {
            HistoryChange entry = historySync.getHistoryChange(id);
            if (entry != null && projectId.equals(entry.getProjectId())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private Result run(Request req) {
        try {
            return locks.runExclusive(req.projectId(), () -> runLocked(req));
        } catch (Exception e) {
            log.error("[ROLLBACK] {} failed for project {}: {}", req.direction(), req.projectId(), e.getMessage(), e);
            return new Result(500, false, req.dryRun(), req.direction(), scopeId(req), List.of(), List.of(), null,
                    "Couldn't finish: " + e.getMessage());
        }
    }

    private Result runLocked(Request original) {
        Request req = original;
        List<HistoryChange> changeSet = null;
        if (REDO.equals(original.direction()) && original.changeSetId() != null && original.undoAuditId() == null) {
            changeSet = historySync.getChangeSet(original.projectId(), original.changeSetId());
            req = original.withUndoAudit(pendingUndoAudit(changeSet, original.changeSetId()));
        }
        List<HistoryChange> entries = load(req, changeSet);
        if (entries.isEmpty()) {
            return new Result(404, false, req.dryRun(), req.direction(), scopeId(req), List.of(), List.of(), null,
                    "Change not found");
        }
        return req.dryRun() ? preview(req, entries) : applyInOrder(req, entries);
    }

    private Result preview(Request req, List<HistoryChange> entries) {
        List<Item> applied = new ArrayList<>();
        List<Item> skipped = new ArrayList<>();
        for (HistoryChange entry : entries) {
            Work work = evaluate(req, entry);
            skipped.addAll(work.skipped());
            applied.addAll(appliedItems(work));
        }
        String message = applied.isEmpty() ? "Nothing to " + req.direction().toLowerCase() : null;
        return new Result(200, true, true, req.direction(), scopeId(req), applied, skipped, null, message);
    }

    private Result applyInOrder(Request req, List<HistoryChange> entries) {
        List<Item> applied = new ArrayList<>();
        List<Item> skipped = new ArrayList<>();
        List<Work> done = new ArrayList<>();
        List<Work> completions = new ArrayList<>();
        RollbackAudit audit = null;
        try {
            for (HistoryChange entry : entries) {
                Work work = evaluate(req, entry);
                skipped.addAll(work.skipped());
                if (work.plan().isEmpty()) {
                    if (work.completesEntry()) {
                        completions.add(work);
                    }
                    continue;
                }
                if (audit == null) {
                    audit = recorder.saveAudit(req, entries, 0);
                }
                applyWork(req, work, audit.getId());
                done.add(work);
                applied.addAll(appliedItems(work));
            }
            if (audit != null) {
                for (Work pending : completions) {
                    applyWork(req, pending, audit.getId());
                }
            }
        } catch (RuntimeException e) {
            return failedMidway(req, audit == null ? null : audit.getId(), done, skipped, e);
        }
        if (audit == null) {
            return new Result(200, false, false, req.direction(), scopeId(req), applied, skipped, null,
                    "Nothing to " + req.direction().toLowerCase());
        }
        audit.setSkippedCount(skipped.size());
        audits.save(audit);
        String description = recorder.recordHistory(req, entries, applied, audit.getId());
        recorder.broadcast(req, audit.getId(), entries, description);
        return new Result(200, true, false, req.direction(), scopeId(req), applied, skipped, audit.getId(), null);
    }

    private String pendingUndoAudit(List<HistoryChange> entries, String changeSetId) {
        List<RollbackAudit> undos = audits.findByChangeSetIdAndDirectionOrderByRevertedAtDesc(changeSetId, UNDO);
        if (undos.isEmpty()) {
            return null;
        }
        for (RollbackAudit undo : undos) {
            boolean pending = entries.stream().anyMatch(entry ->
                    (entry.isReverted() && undo.getId().equals(entry.getRevertedAuditId()))
                            || !RollbackMutationPlanner.revertedBy(entry, undo.getId()).isEmpty());
            if (pending) {
                return undo.getId();
            }
        }
        return undos.get(0).getId();
    }

    private Result failedMidway(Request req, String auditId, List<Work> done, List<Item> skipped, RuntimeException cause) {
        log.error("[ROLLBACK] {} stopped part-way for project {}: {}", req.direction(), req.projectId(), cause.getMessage(), cause);
        List<Item> partial = new ArrayList<>();
        done.forEach(work -> partial.addAll(appliedItems(work)));
        String keptAuditId = auditId;
        if (done.isEmpty()) {
            if (auditId != null) {
                audits.deleteById(auditId);
            }
            keptAuditId = null;
        } else {
            List<HistoryChange> doneEntries = done.stream().map(Work::entry).toList();
            String description = recorder.recordHistory(req, doneEntries, partial, auditId);
            recorder.broadcast(req, auditId, doneEntries, description);
        }
        String message = "Stopped part-way: " + cause.getMessage() + (done.isEmpty() ? "" : " (" + done.size() + " step(s) were applied)");
        return new Result(500, false, false, req.direction(), scopeId(req), partial, skipped, keptAuditId, message);
    }

    private List<HistoryChange> load(Request req, List<HistoryChange> preloadedChangeSet) {
        if (req.changeSetId() != null) {
            List<HistoryChange> entries = new ArrayList<>(preloadedChangeSet != null
                    ? preloadedChangeSet : historySync.getChangeSet(req.projectId(), req.changeSetId()));
            if (REDO.equals(req.direction())) {
                Collections.reverse(entries);
            }
            return entries;
        }
        if (req.auditChangeIds() != null && !req.auditChangeIds().isEmpty()) {
            return loadByIds(req.projectId(), req.auditChangeIds());
        }
        HistoryChange entry = historySync.getHistoryChange(req.changeId());
        return entry != null && req.projectId().equals(entry.getProjectId()) ? List.of(entry) : List.of();
    }

    private Work evaluate(Request req, HistoryChange entry) {
        if (isRollbackEntry(entry)) {
            return skip(entry, "This is itself an undo or redo; use Undo or Redo on the original change");
        }
        if (entry.getEntityIRI() == null || entry.getEntityIRI().isBlank()) {
            return skip(entry, "This kind of change can't be undone automatically");
        }
        if (req.subChangeId() != null && UNDO.equals(req.direction())) {
            return evaluateSubChange(req, entry);
        }
        return UNDO.equals(req.direction()) ? evaluateUndo(req, entry) : evaluateRedo(req, entry);
    }

    private Work evaluateUndo(Request req, HistoryChange entry) {
        if (entry.isReverted()) {
            return skip(entry, "Already undone");
        }
        Kind kind = RollbackMutationPlanner.kindOf(entry);
        boolean exists = exists(req, entry);
        if (kind == Kind.DELETE && exists) {
            return skip(entry, "It was re-created since this change");
        }
        if (kind != Kind.DELETE && !exists) {
            return skip(entry, "It no longer exists");
        }
        List<Item> skipped = new ArrayList<>();
        List<HistoryChange.SubChange> targets = kind == Kind.GROUPED_MODIFY
                ? currentSubChanges(req, entry, RollbackMutationPlanner.active(entry), true, skipped)
                : RollbackMutationPlanner.active(entry);
        Plan plan = planner.planUndo(entry, targets);
        return finish(entry, plan, targets, skipped, kind);
    }

    private Work evaluateRedo(Request req, HistoryChange entry) {
        List<HistoryChange.SubChange> undone = RollbackMutationPlanner.revertedBy(entry, req.undoAuditId());
        boolean entryUndone = entry.isReverted()
                && (req.undoAuditId() == null || req.undoAuditId().equals(entry.getRevertedAuditId()));
        if (!entryUndone && undone.isEmpty()) {
            return skip(entry, "It hasn't been undone");
        }
        if (!entryUndone) {
            return evaluateSubRedo(req, entry, undone);
        }
        Kind kind = RollbackMutationPlanner.kindOf(entry);
        boolean exists = exists(req, entry);
        if (kind == Kind.CREATE && exists) {
            return skip(entry, "It already exists again");
        }
        if (kind != Kind.CREATE && !exists) {
            return skip(entry, "It no longer exists");
        }
        List<Item> skipped = new ArrayList<>();
        List<HistoryChange.SubChange> targets = kind == Kind.GROUPED_MODIFY
                ? currentSubChanges(req, entry, undone, false, skipped)
                : undone;
        Plan plan = planner.planRedo(entry, targets);
        return finish(entry, plan, targets, skipped, kind);
    }

    private Work evaluateSubRedo(Request req, HistoryChange entry, List<HistoryChange.SubChange> undone) {
        if (!exists(req, entry)) {
            return skip(entry, "It no longer exists");
        }
        List<Item> skipped = new ArrayList<>();
        List<HistoryChange.SubChange> targets = currentSubChanges(req, entry, undone, false, skipped);
        Plan plan = targets.isEmpty() ? emptyPlan() : planner.planSubChanges(entry, targets, false);
        skipped.addAll(unsupportedItems(entry, plan));
        return new Work(entry, plan, targets, skipped, false, true);
    }

    private Work evaluateSubChange(Request req, HistoryChange entry) {
        HistoryChange.SubChange sub = findSubChange(entry, req.subChangeId());
        if (sub == null) {
            return skip(entry, "Sub-change not found");
        }
        if (entry.isReverted() || sub.isReverted()) {
            return skipSub(entry, sub, "Already undone");
        }
        if (RollbackMutationPlanner.kindOf(entry) == Kind.DELETE) {
            return skipSub(entry, sub, "Undo the whole deletion first to bring this entity back");
        }
        if (!exists(req, entry)) {
            return skipSub(entry, sub, "It no longer exists");
        }
        List<Item> skipped = new ArrayList<>();
        List<HistoryChange.SubChange> targets = currentSubChanges(req, entry, List.of(sub), true, skipped);
        Plan plan = targets.isEmpty() ? emptyPlan() : planner.planSubChange(entry, sub, true);
        skipped.addAll(unsupportedItems(entry, plan));
        return new Work(entry, plan, targets, skipped, false, true);
    }

    private List<HistoryChange.SubChange> currentSubChanges(Request req, HistoryChange entry,
                                                            List<HistoryChange.SubChange> candidates,
                                                            boolean undo, List<Item> skipped) {
        List<HistoryChange.SubChange> current = new ArrayList<>();
        for (HistoryChange.SubChange sc : candidates) {
            String value = sc.isAddition() ? sc.getNewValue() : sc.getOldValue();
            Boolean present = facts.statementPresent(req.projectId(), entry.getEntityIRI(), sc.getPredicate(), value,
                    entry.isDraft(), entry.getUserId());
            boolean expectedPresent = undo == sc.isAddition();
            if (present != null && present != expectedPresent) {
                skipped.add(item(entry, sc, "It was changed since this edit"));
            } else {
                current.add(sc);
            }
        }
        return current;
    }

    private Work finish(HistoryChange entry, Plan plan, List<HistoryChange.SubChange> targets, List<Item> skipped,
                        Kind kind) {
        skipped.addAll(unsupportedItems(entry, plan));
        boolean allHandled = targets.stream().allMatch(sc -> plan.handledSubChangeIds().contains(sc.getId()));
        boolean completes = skipped.isEmpty() && allHandled && (kind != Kind.SINGLE || !plan.isEmpty());
        return new Work(entry, plan, targets, skipped, completes, false);
    }

    private void applyWork(Request req, Work work, String auditId) {
        HistoryChange entry = work.entry();
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        if (!work.plan().mutations().isEmpty()) {
            if (draft) {
                mutations.applyDraftForRollback(req.projectId(), entry.getUserId(), work.plan().mutations());
                if (entry.getEntityIRI() != null) {
                    draftTracking.discardDraftsByIris(req.projectId(), entry.getUserId(), Set.of(entry.getEntityIRI()));
                }
            } else {
                mutations.applyForRollback(req.projectId(), work.plan().mutations());
            }
        }
        for (String raw : work.plan().rawUpdates()) {
            mutations.applyRawUpdate(req.projectId(), raw, draft, entry.getUserId());
        }
        boolean undo = UNDO.equals(req.direction());
        Set<String> handled = work.plan().handledSubChangeIds();
        historySync.setSubChangesReverted(entry.getId(), handled, undo, undo ? auditId : null);
        if (work.completesEntry() && req.subChangeId() == null) {
            historySync.setEntryReverted(entry.getId(), undo, undo ? auditId : null);
        }
    }

    private List<Item> appliedItems(Work work) {
        List<Item> items = new ArrayList<>();
        if (work.plan().isEmpty()) {
            return items;
        }
        HistoryChange entry = work.entry();
        Set<String> handled = work.plan().handledSubChangeIds();
        List<HistoryChange.SubChange> handledTargets = work.targets().stream()
                .filter(sc -> handled.contains(sc.getId())).toList();
        boolean perProperty = work.subLevel() || RollbackMutationPlanner.kindOf(entry) == Kind.GROUPED_MODIFY;
        if (perProperty) {
            handledTargets.forEach(sc -> items.add(item(entry, sc, null)));
        } else {
            items.add(new Item(entry.getId(), null, entry.getEntityIRI(), entry.getEntityLabel(), null, null));
        }
        return items;
    }

    private List<Item> unsupportedItems(HistoryChange entry, Plan plan) {
        List<Item> items = new ArrayList<>();
        for (String predicate : plan.unsupported()) {
            items.add(new Item(entry.getId(), null, entry.getEntityIRI(), entry.getEntityLabel(), predicate,
                    "This kind of change can't be undone automatically"));
        }
        return items;
    }

    private boolean exists(Request req, HistoryChange entry) {
        return facts.entityExists(req.projectId(), entry.getEntityIRI(), entry.isDraft(), entry.getUserId());
    }

    private static boolean isRollbackEntry(HistoryChange entry) {
        String op = entry.getOperationType();
        return ChangeOrigin.ROLLBACK.equals(entry.getSource())
                || (op != null && (op.startsWith("ROLLBACK_") || op.startsWith("REDO_")));
    }

    private static HistoryChange.SubChange findSubChange(HistoryChange entry, String subChangeId) {
        if (entry.getSubChanges() == null || subChangeId == null) {
            return null;
        }
        return entry.getSubChanges().stream().filter(sc -> subChangeId.equals(sc.getId())).findFirst().orElse(null);
    }

    private static Work skip(HistoryChange entry, String reason) {
        return new Work(entry, emptyPlan(), List.of(),
                new ArrayList<>(List.of(new Item(entry.getId(), null, entry.getEntityIRI(), entry.getEntityLabel(), null, reason))),
                false, false);
    }

    private static Work skipSub(HistoryChange entry, HistoryChange.SubChange sub, String reason) {
        return new Work(entry, emptyPlan(), List.of(), new ArrayList<>(List.of(item(entry, sub, reason))), false, true);
    }

    private static Item item(HistoryChange entry, HistoryChange.SubChange sub, String reason) {
        return new Item(entry.getId(), sub.getId(), entry.getEntityIRI(), entry.getEntityLabel(), sub.getPredicate(), reason);
    }

    private static Plan emptyPlan() {
        return new Plan(List.of(), List.of(), Set.of(), List.of());
    }

    private static String scopeId(Request req) {
        return req.changeSetId() != null ? req.changeSetId() : req.subChangeId() != null ? req.subChangeId() : req.changeId();
    }
}
