package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RollbackRequestSupport {

    private final WorkspaceOwnershipService workspaceOwnershipService;

    public RollbackRequestSupport(WorkspaceOwnershipService workspaceOwnershipService) {
        this.workspaceOwnershipService = workspaceOwnershipService;
    }

    public ChangeRollbackService.Actor actor(HttpServletRequest request, Map<String, Object> body) {
        String authHeader = request != null ? request.getHeader("Authorization") : null;
        String email = JwtClaimUtils.extractEmail(authHeader);
        String[] claims = JwtClaimUtils.extractPlanAndUserId(authHeader);
        String claimUserId = claims != null ? claims[1] : null;
        if (email != null || claimUserId != null) {
            String userId = email != null ? email : claimUserId;
            return new ChangeRollbackService.Actor(userId, userId);
        }
        String userId = body != null && body.get("userId") instanceof String s && !s.isBlank() ? s : "system";
        String username = body != null && body.get("username") instanceof String s && !s.isBlank() ? s : "System";
        return new ChangeRollbackService.Actor(userId, username);
    }

    public ResponseEntity<Map<String, Object>> denyIfNotAllowed(List<HistoryChange> changes, String projectId,
                                                                HttpServletRequest request) {
        String authHeader = request != null ? request.getHeader("Authorization") : null;
        String[] claims = JwtClaimUtils.extractPlanAndUserId(authHeader);
        String requesterId = claims != null ? claims[1] : null;
        if (requesterId == null) {
            return null;
        }
        if (workspaceOwnershipService.isViewerInProject(requesterId, projectId)
                && !workspaceOwnershipService.isUserOwnerOfProject(requesterId, projectId)) {
            return ResponseEntity.status(403).body(Map.of("success", false,
                    "error", "You do not have permission to roll back changes in this project."));
        }
        String requesterEmail = JwtClaimUtils.extractEmail(authHeader);
        boolean touchesSomeoneElsesDraft = changes.stream().anyMatch(change -> change.isDraft()
                && !isAuthor(change, requesterId, requesterEmail));
        if (touchesSomeoneElsesDraft) {
            return ResponseEntity.status(403).body(Map.of("success", false,
                    "error", "Only the person who made this change can roll it back before it's merged."));
        }
        if (workspaceOwnershipService.isDraftEditorInProject(requesterId, projectId)
                && !workspaceOwnershipService.isUserOwnerOfProject(requesterId, projectId)
                && changes.stream().anyMatch(change -> !change.isDraft())) {
            return ResponseEntity.status(403).body(Map.of("success", false,
                    "error", "You can only roll back changes in your own draft."));
        }
        return null;
    }

    private static boolean isAuthor(HistoryChange change, String requesterId, String requesterEmail) {
        String owner = change.getUserId();
        return owner != null && (owner.equals(requesterId) || owner.equalsIgnoreCase(requesterEmail));
    }

    public static ResponseEntity<Map<String, Object>> toBody(ChangeRollbackService.Result result, String changeId,
                                                             String subChangeId) {
        boolean alreadyUndone = result.applied().isEmpty() && !result.skipped().isEmpty()
                && result.skipped().stream().allMatch(item -> "Already undone".equals(item.reason()));
        Map<String, Object> body = new HashMap<>();
        body.put("success", result.ok() || alreadyUndone);
        body.put("alreadyReverted", alreadyUndone);
        body.put("dryRun", result.dryRun());
        body.put("direction", result.direction());
        body.put("changeId", changeId);
        body.put("subChangeId", subChangeId);
        body.put("scopeId", result.scopeId());
        body.put("auditId", result.auditId());
        body.put("mutationApplied", !result.dryRun() && !result.applied().isEmpty());
        body.put("applied", items(result.applied()));
        body.put("skipped", items(result.skipped()));
        String firstReason = result.skipped().isEmpty() ? null : result.skipped().get(0).reason();
        String message = result.message() != null ? result.message()
                : alreadyUndone ? "Already reverted"
                : result.ok() ? (result.dryRun() ? "Preview ready" : "Rolled back") : firstReason;
        body.put("message", message);
        int status = result.status();
        if (status == 200 && !result.ok() && !alreadyUndone && !result.dryRun()) {
            status = 409;
            body.put("error", message);
        } else if (status != 200) {
            body.put("error", message);
        }
        return ResponseEntity.status(status).body(body);
    }

    private static List<Map<String, Object>> items(List<ChangeRollbackService.Item> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChangeRollbackService.Item item : items) {
            Map<String, Object> map = new HashMap<>();
            map.put("changeId", item.changeId());
            map.put("subChangeId", item.subChangeId());
            map.put("entityIRI", item.entityIRI());
            map.put("entityLabel", item.entityLabel());
            map.put("predicate", item.predicate());
            map.put("reason", item.reason());
            list.add(map);
        }
        return list;
    }
}
