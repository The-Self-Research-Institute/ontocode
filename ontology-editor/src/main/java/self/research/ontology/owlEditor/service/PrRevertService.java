package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PrRevertService {

    public record Outcome(int status, boolean ok, boolean dryRun, String message,
                          List<Map<String, Object>> changeSets, boolean alreadyDone) {}

    private final DraftPullRequestRepository prs;
    private final HistorySyncService historySync;
    private final ChangeRollbackService rollback;
    private final DraftTrackingService drafts;
    private final DraftMergeApplier mergeApplier;
    private final WorkspaceOwnershipService ownership;

    public PrRevertService(DraftPullRequestRepository prs, HistorySyncService historySync,
                           ChangeRollbackService rollback, DraftTrackingService drafts,
                           DraftMergeApplier mergeApplier, WorkspaceOwnershipService ownership) {
        this.prs = prs;
        this.historySync = historySync;
        this.rollback = rollback;
        this.drafts = drafts;
        this.mergeApplier = mergeApplier;
        this.ownership = ownership;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, Object> monitors = new java.util.concurrent.ConcurrentHashMap<>();

    private Object monitorOf(String prId) {
        return monitors.computeIfAbsent(prId, id -> new Object());
    }

    public Outcome revert(String projectId, String prId, String userId, String username, String reason, boolean dryRun) {
        if (dryRun) {
            return revertNow(projectId, prId, userId, username, reason, true);
        }
        synchronized (monitorOf(prId)) {
            return revertNow(projectId, prId, userId, username, reason, false);
        }
    }

    private Outcome revertNow(String projectId, String prId, String userId, String username, String reason, boolean dryRun) {
        DraftPullRequest pr = prs.findById(prId).orElse(null);
        if (pr == null || !projectId.equals(pr.getProjectId())) {
            return fail(404, dryRun, "Pull request not found");
        }
        if (userId == null || !ownership.canPublishToProject(userId, projectId)) {
            return fail(403, dryRun, "Only project owners, admins, or editors can revert pull requests");
        }
        if (pr.getStatus() == DraftPullRequest.Status.REVERTED) {
            return new Outcome(200, true, dryRun, "Already reverted", List.of(), true);
        }
        if (pr.getStatus() != DraftPullRequest.Status.APPROVED) {
            return fail(409, dryRun, "Only a merged pull request can be reverted");
        }
        List<String> ids = pr.getPublishedChangeSetIds();
        if (ids == null || ids.isEmpty()) {
            return fail(409, dryRun, "This pull request has no recorded changes to revert. It was merged before reverts were supported");
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (!dryRun && cleanReason.isEmpty()) {
            return fail(400, false, "Please give a reason for reverting this pull request");
        }
        List<String> ordered = newestFirst(projectId, ids);
        List<Map<String, Object>> report = new ArrayList<>();
        boolean blocked = false;
        for (String changeSetId : ordered) {
            ChangeRollbackService.Result preview = rollback.undoChangeSet(projectId, changeSetId,
                    new Actor(userId, username == null ? userId : username, null), true);
            boolean already = alreadyUndone(preview);
            report.add(entry(changeSetId, preview, already));
            if (!preview.ok() && !already) {
                blocked = true;
            }
        }
        if (blocked) {
            return new Outcome(409, false, dryRun,
                    "This pull request can't be reverted automatically. See which change sets are blocked and why", report, false);
        }
        if (dryRun) {
            return new Outcome(200, true, true, "Preview ready: " + ordered.size() + " change sets can be reverted", report, false);
        }
        if (pr.getRevertStartedAt() == null) {
            pr.setRevertStartedAt(Instant.now());
            prs.save(pr);
        }
        int done = 0;
        for (int i = 0; i < ordered.size(); i++) {
            String changeSetId = ordered.get(i);
            if (Boolean.TRUE.equals(report.get(i).get("alreadyUndone"))) {
                done++;
                continue;
            }
            ChangeRollbackService.Result result = rollback.undoChangeSet(projectId, changeSetId,
                    new Actor(userId, username == null ? userId : username, prId + ":" + changeSetId), false);
            if (!result.ok() && !alreadyUndone(result)) {
                report.get(i).put("failed", true);
                report.get(i).put("reasons", reasons(result));
                return new Outcome(409, false, false, "Reverted " + done + " of " + ordered.size()
                        + " change sets, then stopped. The rest were not changed", report, false);
            }
            report.get(i).put("reverted", true);
            done++;
        }
        historySync.markPrPublicationReverted(projectId, prId);
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        pr.setRevertedAt(Instant.now());
        pr.setRevertedBy(userId);
        pr.setRevertReason(cleanReason);
        prs.save(pr);
        drafts.recordPrReversal(projectId, prId, pr.getTitle(), username == null ? userId : username, ordered.size(), cleanReason);
        return new Outcome(200, true, false, "Reverted " + ordered.size() + " change sets", report, false);
    }

    public Outcome restore(String projectId, String prId, String userId, String username) {
        synchronized (monitorOf(prId)) {
            return restoreNow(projectId, prId, userId, username);
        }
    }

    private Outcome restoreNow(String projectId, String prId, String userId, String username) {
        DraftPullRequest pr = prs.findById(prId).orElse(null);
        if (pr == null || !projectId.equals(pr.getProjectId())) {
            return fail(404, false, "Pull request not found");
        }
        if (userId == null || !userId.equalsIgnoreCase(pr.getAuthorId())) {
            return fail(403, false, "Only the author can restore the changes of this pull request");
        }
        if (pr.getStatus() != DraftPullRequest.Status.REVERTED) {
            return fail(409, false, "Only a reverted pull request can be restored");
        }
        if (pr.getRestoredAt() != null) {
            return new Outcome(200, true, false, "Already restored into your draft", List.of(), true);
        }
        List<String> ids = pr.getPublishedChangeSetIds();
        if (ids == null || ids.isEmpty()) {
            return fail(409, false, "This pull request has no recorded changes to restore");
        }
        List<String> oldestFirst = new ArrayList<>(newestFirst(projectId, ids));
        java.util.Collections.reverse(oldestFirst);
        Model deleted = new LinkedHashModel();
        Model inserted = new LinkedHashModel();
        for (String changeSetId : oldestFirst) {
            List<HistoryChange> rows = new ArrayList<>(historySync.getChangeSet(projectId, changeSetId));
            rows.sort(Comparator.comparing(HistoryChange::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
            for (HistoryChange row : rows) {
                if (RollbackWorkSupport.isPublicationEntry(row)) {
                    continue;
                }
                Optional<ExactDelta> delta = ExactReversal.deltaOf(row);
                if (delta.isEmpty()) {
                    return fail(409, false, "These changes can't be restored automatically: "
                            + UserUndoService.labelOf(row) + " has no recorded statements");
                }
                fold(deleted, inserted, delta.get());
            }
        }
        ExactDelta folded = new ExactDelta(deleted, inserted);
        if (folded.isEmpty()) {
            return fail(409, false, "Nothing to restore");
        }
        try {
            mergeApplier.apply(projectId, userId, username == null ? userId : username, folded,
                    "Restored changes from PR: " + pr.getTitle());
        } catch (DraftNotReadyException e) {
            return fail(409, false, "Switch to Draft mode and wait for your draft to be ready, then restore again");
        }
        pr.setRestoredAt(Instant.now());
        prs.save(pr);
        return new Outcome(200, true, false, "Restored " + inserted.size() + " additions and " + deleted.size()
                + " removals into your draft. Raise a pull request when you are ready", List.of(), false);
    }

    static void fold(Model deleted, Model inserted, ExactDelta next) {
        next.deleted().forEach(st -> {
            if (!inserted.remove(st.getSubject(), st.getPredicate(), st.getObject())) {
                deleted.add(st);
            }
        });
        next.inserted().forEach(st -> {
            if (!deleted.remove(st.getSubject(), st.getPredicate(), st.getObject())) {
                inserted.add(st);
            }
        });
    }

    private List<String> newestFirst(String projectId, List<String> changeSetIds) {
        Map<String, LocalDateTime> newest = new LinkedHashMap<>();
        for (String id : changeSetIds) {
            LocalDateTime latest = null;
            for (HistoryChange row : historySync.getChangeSet(projectId, id)) {
                if (row.getTimestamp() != null && (latest == null || row.getTimestamp().isAfter(latest))) {
                    latest = row.getTimestamp();
                }
            }
            newest.put(id, latest);
        }
        List<String> ordered = new ArrayList<>(changeSetIds);
        ordered.sort(Comparator.comparing((String id) -> newest.get(id), Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return ordered;
    }

    private static boolean alreadyUndone(ChangeRollbackService.Result result) {
        return result.applied().isEmpty() && !result.skipped().isEmpty()
                && result.skipped().stream().allMatch(item -> "Already undone".equals(item.reason()));
    }

    private static List<String> reasons(ChangeRollbackService.Result result) {
        List<String> reasons = new ArrayList<>();
        for (ChangeRollbackService.Item item : result.skipped()) {
            if (item.reason() != null && !reasons.contains(item.reason())) {
                reasons.add(item.reason());
            }
        }
        if (reasons.isEmpty() && result.message() != null) {
            reasons.add(result.message());
        }
        return reasons;
    }

    private static Map<String, Object> entry(String changeSetId, ChangeRollbackService.Result result, boolean already) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("changeSetId", changeSetId);
        map.put("canRevert", result.ok() || already);
        map.put("alreadyUndone", already);
        map.put("willRemove", result.applied().size());
        map.put("reasons", result.ok() || already ? List.of() : reasons(result));
        return map;
    }

    private static Outcome fail(int status, boolean dryRun, String message) {
        return new Outcome(status, false, dryRun, message, List.of(), false);
    }
}
