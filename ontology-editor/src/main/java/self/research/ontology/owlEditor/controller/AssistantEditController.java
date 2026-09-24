package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.dto.ProposeEditRequest;
import self.research.ontology.owlEditor.service.AssistantEditApplyService;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;
import self.research.ontology.owlEditor.service.AssistantEditProposalService;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.GroupProposalOutcome;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.ProposeEditResult;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant/sessions/{sessionId}")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AssistantEditController {

    private final AssistantEditProposalService proposalService;
    private final AssistantEditApplyService applyService;

    public AssistantEditController(AssistantEditProposalService proposalService,
                                    AssistantEditApplyService applyService) {
        this.proposalService = proposalService;
        this.applyService = applyService;
    }

    @PostMapping("/propose")
    public ResponseEntity<?> propose(@PathVariable String sessionId, @RequestBody ProposeEditRequest request,
                                      HttpServletRequest httpRequest) {
        return JwtIdentityExtractor.extractEmail(httpRequest)
                .map(userEmail -> {
                    ProposeEditResult result = proposalService.propose(sessionId, userEmail, request.groups());
                    return ResponseEntity.ok(toBody(result));
                })
                .orElseGet(AssistantEditController::unauthorized);
    }

    @PostMapping("/groups/{serverGroupId}/apply")
    public ResponseEntity<?> apply(@PathVariable String sessionId, @PathVariable String serverGroupId,
                                    HttpServletRequest httpRequest) {
        return JwtIdentityExtractor.extractEmail(httpRequest)
                .map(userEmail -> {
                    ApplyResult result = applyService.applyGroup(sessionId, serverGroupId, userEmail);
                    return ResponseEntity.ok(toBody(result));
                })
                .orElseGet(AssistantEditController::unauthorized);
    }

    private static Map<String, Object> toBody(ProposeEditResult result) {
        if (!result.isOk()) {
            return Map.of("ok", false, "errorCode", result.getErrorCode(), "message", result.getMessage());
        }
        List<Map<String, Object>> groups = result.getGroups().stream().map(AssistantEditController::toBody).toList();
        return Map.of("ok", true, "groups", groups);
    }

    private static Map<String, Object> toBody(GroupProposalOutcome outcome) {
        return Map.of(
                "clientGroupId", outcome.getClientGroupId(),
                "serverGroupId", outcome.getServerGroupId(),
                "validation", Map.of("passed", outcome.isValidationPassed(), "checks", outcome.getChecks()),
                "diff", outcome.getDiff());
    }

    private static Map<String, Object> toBody(ApplyResult result) {
        if (!result.isOk()) {
            return Map.of("ok", false, "errorCode", result.getErrorCode(), "message", result.getMessage());
        }
        return Map.of(
                "ok", true,
                "applied", result.isApplied(),
                "newRevision", result.getNewRevision(),
                "remappedPendingGroups", result.getRemappedPendingGroups());
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("ok", false, "message", "Missing or invalid Authorization header"));
    }
}
