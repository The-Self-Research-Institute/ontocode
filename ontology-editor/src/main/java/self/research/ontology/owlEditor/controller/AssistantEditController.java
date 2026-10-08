package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckRecord;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckState;
import self.research.ontology.owlEditor.dto.ProposeEditRequest;
import self.research.ontology.owlEditor.service.AssistantConsistencyCheckService;
import self.research.ontology.owlEditor.service.AssistantEditApplyService;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;
import self.research.ontology.owlEditor.service.AssistantEditProposalService;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.CheckResult;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.GroupProposalOutcome;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.ProposeEditResult;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant/sessions/{sessionId}")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AssistantEditController {

    private static final int MAX_SUMMARY_CHARS = 500;

    private final AssistantEditProposalService proposalService;
    private final AssistantEditApplyService applyService;
    private final AssistantConsistencyCheckService consistencyCheckService;

    public AssistantEditController(AssistantEditProposalService proposalService,
                                    AssistantEditApplyService applyService,
                                    AssistantConsistencyCheckService consistencyCheckService) {
        this.proposalService = proposalService;
        this.applyService = applyService;
        this.consistencyCheckService = consistencyCheckService;
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
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest httpRequest) {
        String summary = body != null && body.get("summary") instanceof String s ? truncate(s, MAX_SUMMARY_CHARS) : null;
        return JwtIdentityExtractor.extractEmail(httpRequest)
                .map(userEmail -> {
                    ApplyResult result = applyService.applyGroup(sessionId, serverGroupId, userEmail, summary);
                    int status = AssistantEditApplyService.PROPOSAL_NOT_FOUND.equals(result.getErrorCode()) ? 403 : 200;
                    return ResponseEntity.status(status).body(toBody(result));
                })
                .orElseGet(AssistantEditController::unauthorized);
    }

    @GetMapping("/groups/{serverGroupId}/consistency-check")
    public ResponseEntity<?> consistencyCheck(@PathVariable String sessionId, @PathVariable String serverGroupId,
                                              HttpServletRequest httpRequest) {
        return JwtIdentityExtractor.extractEmail(httpRequest)
                .map(userEmail -> {
                    Optional<ConsistencyCheckRecord> record =
                            consistencyCheckService.getRecord(sessionId, serverGroupId, userEmail);
                    if (record.isEmpty()) {
                        return ResponseEntity.status(404).body(errorBody("PROPOSAL_NOT_FOUND",
                                "Unknown or unauthorized proposal, or no consistency check was run for it"));
                    }
                    return ResponseEntity.ok(toBody(record.get()));
                })
                .orElseGet(AssistantEditController::unauthorized);
    }

    private static Map<String, Object> toBody(ConsistencyCheckRecord record) {
        boolean failed = record.getState() == ConsistencyCheckState.FAILED;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("name", AssistantConsistencyCheckService.CONSISTENCY_PRESERVED_CHECK);
        body.put("passed", !failed);
        if (record.getState() == ConsistencyCheckState.PENDING) {
            body.put("status", AssistantConsistencyCheckService.PENDING_STATUS);
        }
        if (record.getDetail() != null) {
            body.put("detail", record.getDetail());
        }
        return body;
    }

    private static Map<String, Object> toBody(ProposeEditResult result) {
        if (!result.isOk()) {
            return errorBody(result.getErrorCode(), result.getMessage());
        }
        List<Map<String, Object>> groups = result.getGroups() == null ? List.of()
                : result.getGroups().stream().map(AssistantEditController::toBody).toList();
        return Map.of("ok", true, "groups", groups);
    }

    private static Map<String, Object> toBody(GroupProposalOutcome outcome) {
        List<Map<String, Object>> checks = outcome.getChecks() == null ? List.of()
                : outcome.getChecks().stream().map(AssistantEditController::toBody).toList();
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("passed", outcome.isValidationPassed());
        validation.put("checks", checks);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientGroupId", outcome.getClientGroupId());
        body.put("serverGroupId", outcome.getServerGroupId());
        body.put("validation", validation);
        body.put("diff", outcome.getDiff() == null ? List.of() : outcome.getDiff());
        return body;
    }

    private static Map<String, Object> toBody(CheckResult check) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", check.name());
        body.put("passed", check.passed());
        if (check.detail() != null) {
            body.put("detail", check.detail());
        }
        if (check.status() != null) {
            body.put("status", check.status());
        }
        return body;
    }

    private static Map<String, Object> errorBody(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorCode", errorCode);
        body.put("message", message);
        return body;
    }

    private static Map<String, Object> toBody(ApplyResult result) {
        if (!result.isOk()) {
            return errorBody(result.getErrorCode(), result.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("applied", result.isApplied());
        body.put("newRevision", result.getNewRevision());
        body.put("remappedPendingGroups", result.getRemappedPendingGroups() == null
                ? List.of() : result.getRemappedPendingGroups());
        body.put("appliedRanges", result.getAppliedRanges() == null ? List.of() : result.getAppliedRanges());
        return body;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("ok", false, "errorCode", "UNAUTHORIZED",
                "message", "Missing or invalid Authorization header"));
    }
}
