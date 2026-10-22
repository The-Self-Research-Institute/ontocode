package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.delta.ExactReversal;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Kind;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetImportService;
import self.research.ontology.owlEditor.service.RollbackWorkSupport.Presence;
import self.research.ontology.owlEditor.service.RollbackWorkSupport.Work;

import static self.research.ontology.owlEditor.service.RollbackWorkSupport.appliedItems;
import static self.research.ontology.owlEditor.service.RollbackWorkSupport.emptyPlan;
import static self.research.ontology.owlEditor.service.RollbackWorkSupport.findSubChange;
import static self.research.ontology.owlEditor.service.RollbackWorkSupport.isRollbackEntry;
import static self.research.ontology.owlEditor.service.RollbackWorkSupport.item;
import static self.research.ontology.owlEditor.service.RollbackWorkSupport.skip;
import static self.research.ontology.owlEditor.service.RollbackWorkSupport.skipSub;
import static self.research.ontology.owlEditor.service.RollbackWorkSupport.unsupportedItems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ChangeRollbackService {

    public static final String UNDO = "UNDO";
    public static final String REDO = "REDO";

    public record Actor(String userId, String username, String opId) {
        public Actor(String userId, String username) {
            this(userId, username, null);
        }
    }

    public record Item(String changeId, String subChangeId, String entityIRI, String entityLabel,
                       String predicate, String reason) {}

    public record Result(int status, boolean ok, boolean dryRun, String direction, String scopeId,
                         List<Item> applied, List<Item> skipped, String auditId, String message) {}

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

    @Autowired(required = false)
    private RollbackActionRunner actionRunner;

    @Autowired(required = false)
    private DraftMergeApplier mergeApplier;

    @Autowired(required = false)
    private OperationGroups groups;

    @Autowired(required = false)
    private UndoOperations operations;

    @Autowired(required = false)
    private UndoBlockers blockers;

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

    private SpreadsheetImportUndo spreadsheetImports;

    @Autowired(required = false)
    void setSpreadsheetImports(SpreadsheetImportService imports) {
        this.spreadsheetImports = imports == null ? null : new SpreadsheetImportUndo(imports);
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
        RollbackAudit audit = recorder.findUndo(projectId, auditId);
        return audit == null ? List.of() : loadByIds(projectId, RollbackRecorder.undoChangeIds(audit));
    }

    public Result redoUndo(String projectId, String auditId, Actor actor, boolean dryRun) {
        RollbackAudit audit = recorder.findUndo(projectId, auditId);
        if (audit == null) {
            return new Result(404, false, dryRun, REDO, auditId, List.of(), List.of(), null, "Undo not found");
        }
        String level = audit.getChangeSetId() != null ? CHANGESET : audit.getSubChangeId() != null ? SUBCHANGE : ENTRY;
        return run(new Request(projectId, REDO, level, audit.getChangeSetId(), audit.getHistoryChangeId(),
                audit.getSubChangeId(), actor, dryRun, audit.getId(), RollbackRecorder.undoChangeIds(audit)));
    }

    private List<HistoryChange> loadByIds(String projectId, List<String> ids) {
        return RollbackWorkSupport.loadByIds(projectId, ids, historySync::getHistoryChange);
    }

    private Result run(Request req) {
        try {
            return locks.runExclusive(req.projectId(), () -> operations == null ? runLocked(req) : operations.around(req, () -> runLocked(req)));
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
            req = original.withUndoAudit(recorder.pendingUndoAudit(changeSet, original.changeSetId()));
        }
        List<HistoryChange> entries = load(req, changeSet);
        if (entries.isEmpty()) {
            return new Result(404, false, req.dryRun(), req.direction(), scopeId(req), List.of(), List.of(), null,
                    "Change not found");
        }
        UndoOperationContext.entries(entries, req.undoAuditId());
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
                        applied.addAll(appliedItems(work));
                    }
                    continue;
                }
                if (audit == null) {
                    audit = recorder.saveAudit(req, entries, 0);
                    UndoOperationContext.audit(audit.getId());
                }
                applyWork(req, work, audit);
                done.add(work);
                applied.addAll(appliedItems(work));
            }
            if (audit != null) {
                for (Work pending : completions) {
                    applyWork(req, pending, audit);
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
        String warning = groups == null || !UNDO.equals(req.direction()) ? null : groups.warning(entries);
        return new Result(200, true, false, req.direction(), scopeId(req), applied, skipped, audit.getId(), warning);
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
        if (isRollbackEntry(entry)) { return skip(entry, "This is itself an undo or redo; use Undo or Redo on the original change"); }
        if (isSpreadsheetImport(entry)) { return evaluateImport(req, entry); }
        if (UndoOperationContext.blocked(entry.getId())) { return skip(entry, "An earlier undo or redo of this change was interrupted and needs repair"); }
        boolean entityless = entry.getEntityIRI() == null || entry.getEntityIRI().isBlank();
        Kind entryKind = RollbackMutationPlanner.kindOf(entry);
        if (entityless && entryKind != Kind.PREFIX && entryKind != Kind.EXACT) { return skip(entry, "This kind of change can't be undone automatically"); }
        if (ExactReversal.needsWholeChangeSet(entry) && !CHANGESET.equals(req.level()) && req.subChangeId() == null) {
            return skip(entry, "These edits were saved together; undo them together");
        }
        if (req.subChangeId() != null && PrefixReversal.isAggregate(entry)) { return skip(entry, "Prefix changes saved together are undone together"); }
        if (req.subChangeId() != null && UNDO.equals(req.direction())) {
            return evaluateSubChange(req, entry);
        }
        return UNDO.equals(req.direction()) ? evaluateUndo(req, entry) : evaluateRedo(req, entry);
    }

    private String changedSince(Request req, HistoryChange entry) {
        String plain = "It was changed since this edit";
        if (blockers != null && RollbackMutationPlanner.kindOf(entry) == Kind.MERGE && blockers.importRunning(req.projectId())) {
            return UndoBlockers.STILL_LOADING;
        }
        return blockers == null ? plain : blockers.changedSince(req.projectId(), entry, plain);
    }

    private boolean isSpreadsheetImport(HistoryChange entry) {
        return spreadsheetImports != null && spreadsheetImports.handles(entry);
    }

    private Work evaluateImport(Request req, HistoryChange entry) {
        return spreadsheetImports.evaluate(req.projectId(), UNDO.equals(req.direction()), req.subChangeId(),
                req.undoAuditId(), entry);
    }

    private Work evaluateUndo(Request req, HistoryChange entry) {
        if (entry.isReverted()) { return skip(entry, "Already undone"); }
        Kind kind = RollbackMutationPlanner.kindOf(entry);
        if (kind == Kind.DELETE && facts.storeRecordsExact(req.projectId(), entry.isDraft())) { return skip(entry, RollbackWorkSupport.INEXACT_DELETE); }
        Presence presence = RollbackWorkSupport.presence(entry, kind, true);
        if (presence != Presence.IGNORED && (presence == Presence.REQUIRED) != exists(req, entry)) {
            return skip(entry, presence == Presence.REQUIRED ? "It no longer exists" : "It was re-created since this change");
        }
        if (kind == Kind.RENAME && facts.entityExists(req.projectId(), entry.getOldValue(), entry.isDraft(), entry.getUserId())) { return skip(entry, "The old name is in use again"); }
        if (ENTRY.equals(req.level()) && RollbackWorkSupport.isCreation(entry, kind) && facts.referencedOutside(req.projectId(), entry.getEntityIRI(), entry.isDraft(), entry.getUserId(), RollbackWorkSupport.insertedTriples(List.of(entry)))) { return skip(entry, "Other items still refer to it"); }
        if (!deltaHolds(req, entry, true)) { return skip(entry, changedSince(req, entry)); }
        List<Item> skipped = new ArrayList<>();
        List<HistoryChange.SubChange> targets = kind == Kind.GROUPED_MODIFY
                ? currentSubChanges(req, entry, RollbackMutationPlanner.active(entry), true, skipped)
                : RollbackMutationPlanner.active(entry);
        Plan plan = planner.planUndo(entry, targets);
        if (!actionsHold(req, entry, plan)) { return skip(entry, changedSince(req, entry)); }
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
        Presence presence = RollbackWorkSupport.presence(entry, kind, false);
        if (presence != Presence.IGNORED && (presence == Presence.REQUIRED) != exists(req, entry)) {
            return skip(entry, presence == Presence.REQUIRED ? "It no longer exists" : "It already exists again");
        }
        if (!deltaHolds(req, entry, false)) { return skip(entry, changedSince(req, entry)); }
        List<Item> skipped = new ArrayList<>();
        List<HistoryChange.SubChange> targets = kind == Kind.GROUPED_MODIFY
                ? currentSubChanges(req, entry, undone, false, skipped)
                : undone;
        Plan plan = planner.planRedo(entry, targets);
        if (!actionsHold(req, entry, plan)) { return skip(entry, "It was changed since this edit"); }
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

    private void applyWork(Request req, Work work, RollbackAudit audit) {
        String auditId = audit.getId();
        HistoryChange entry = work.entry();
        boolean draft = entry.isDraft() && entry.getUserId() != null && !entry.getUserId().isBlank();
        if (!work.plan().mutations().isEmpty()) {
            if (draft) {
                mutations.applyDraftForRollback(req.projectId(), entry.getUserId(), work.plan().mutations());
            } else {
                mutations.applyForRollback(req.projectId(), work.plan().mutations());
            }
        }
        boolean appliedOnDesktop = !draft && isSpreadsheetImport(entry)
                && spreadsheetImports.applyOnDesktop(req.projectId(), work.plan().rawUpdates());
        if (!appliedOnDesktop) {
            for (String raw : work.plan().rawUpdates()) {
                mutations.applyRawUpdate(req.projectId(), raw, draft, entry.getUserId());
            }
        }
        UndoOperationContext.stage(UndoIntentDocument.Stage.GRAPH_APPLIED);
        for (RollbackAction action : work.plan().actions()) {
            if (actionRunner == null) { throw new IllegalStateException("This kind of change can't be undone on this server"); }
            actionRunner.run(req.projectId(), action, entry.isDraft(), entry.getUserId());
        }
        UndoOperationContext.stage(UndoIntentDocument.Stage.DOCUMENTS_APPLIED);
        boolean undo = UNDO.equals(req.direction());
        if (draft) {
            trackDraftChange(req, work, undo, audit);
        }
        if (isSpreadsheetImport(entry)) {
            spreadsheetImports.markUndone(req.projectId(), entry, undo, req.actor().username());
        }
        Set<String> handled = work.plan().handledSubChangeIds();
        historySync.setSubChangesReverted(entry.getId(), handled, undo, undo ? auditId : null);
        if (work.completesEntry() && req.subChangeId() == null) {
            historySync.setEntryReverted(entry.getId(), undo, undo ? auditId : null);
        }
        UndoOperationContext.stage(UndoIntentDocument.Stage.HISTORY_MARKED);
    }

    private void trackDraftChange(Request req, Work work, boolean undo, RollbackAudit audit) {
        String projectId = req.projectId();
        HistoryChange entry = work.entry();
        if (work.plan().mutations().isEmpty() && !work.completesEntry()) {
            return;
        }
        boolean tracked = tracksDraftChange(entry);
        if (undo) {
            List<DraftChange> undone = draftTracking.discardForUndo(projectId, entry.getUserId(), entry.getChangeSetId(),
                    tracked ? entry.getEntityIRI() : null);
            if (!undone.isEmpty()) {
                audit.getDraftRecords().addAll(undone);
                audits.save(audit);
            }
            return;
        }
        RollbackAudit undoAudit = recorder.findUndo(projectId, req.undoAuditId());
        List<DraftChange> stashed = undoAudit == null ? null : undoAudit.getDraftRecords();
        int before = stashed == null ? 0 : stashed.size();
        if (draftTracking.restoreForRedo(projectId, entry.getUserId(), entry.getChangeSetId(), stashed)) {
            if (undoAudit != null && stashed.size() != before) {
                audits.save(undoAudit);
            }
            return;
        }
        if (!tracked) {
            return;
        }
        if (OntologyMergeService.MERGE_OPERATION.equals(entry.getOperationType())) {
            DraftMergeApplier.recordMarker(draftTracking, projectId, entry.getUserId(), entry.getUsername(),
                    entry.getEntityIRI(), mergeApplier != null ? mergeApplier.summarize(projectId, entry)
                            : DraftMergeApplier.summaryOf(entry, null));
        } else {
            List<MutationOp> ops = work.plan().mutations().isEmpty() ? List.of(redoneOp(entry)) : work.plan().mutations();
            draftTracking.recordDrafts(projectId, entry.getUserId(), entry.getUsername(), ops, UUID.randomUUID().toString());
        }
    }

    private static boolean tracksDraftChange(HistoryChange entry) {
        return entry.getEntityIRI() != null && !entry.getEntityIRI().isBlank() && entry.getOperationType() != null
                && !SpreadsheetImportService.isImportEntry(entry.getOperationType())
                && RollbackMutationPlanner.kindOf(entry) != Kind.PREFIX;
    }

    private static MutationOp redoneOp(HistoryChange entry) {
        return new MutationOp(entry.getOperationType(), entry.getEntityIRI(), entry.getEntityLabel(), null,
                entry.getAnnotationProperty(), entry.getNewValue(), null, null, null, null, null, entry.getOldValue(),
                null, null, null);
    }

    private boolean actionsHold(Request req, HistoryChange entry, Plan plan) {
        return actionRunner == null || plan.actions().stream()
                .allMatch(action -> actionRunner.holds(req.projectId(), action, entry.isDraft(), entry.getUserId()));
    }

    private boolean deltaHolds(Request req, HistoryChange entry, boolean undo) {
        return !entry.carriesExactDelta() || facts.deltaHolds(req.projectId(), entry, undo)
                && (!undo || !facts.laterUsesNewName(req.projectId(), entry));
    }

    private boolean exists(Request req, HistoryChange entry) {
        return facts.entityExists(req.projectId(), entry.getEntityIRI(), entry.isDraft(), entry.getUserId());
    }

    private static String scopeId(Request req) {
        return req.changeSetId() != null ? req.changeSetId() : req.subChangeId() != null ? req.subChangeId() : req.changeId();
    }
}
