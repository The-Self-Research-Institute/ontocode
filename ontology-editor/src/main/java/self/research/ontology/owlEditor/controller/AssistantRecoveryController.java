package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.AssistantRecoveryService;
import self.research.ontology.owlEditor.service.AssistantRecoveryService.RecoveryOutcome;
import self.research.ontology.owlEditor.service.AssistantRecoveryService.RecoveryState;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant/projects/{projectId}/recovery")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AssistantRecoveryController {

    private final AssistantRecoveryService recoveryService;

    public AssistantRecoveryController(AssistantRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @GetMapping
    public ResponseEntity<?> state(@PathVariable String projectId, HttpServletRequest httpRequest) {
        if (JwtIdentityExtractor.extractEmail(httpRequest).isEmpty()) {
            return unauthorized();
        }
        return ResponseEntity.ok(toBody(recoveryService.state(projectId)));
    }

    @PostMapping("/restore")
    public ResponseEntity<?> restore(@PathVariable String projectId, HttpServletRequest httpRequest) {
        return act(projectId, httpRequest, recoveryService::restore);
    }

    @PostMapping("/clear")
    public ResponseEntity<?> clear(@PathVariable String projectId, HttpServletRequest httpRequest) {
        return act(projectId, httpRequest, recoveryService::clear);
    }

    private ResponseEntity<?> act(String projectId, HttpServletRequest httpRequest,
                                  BiFunction<String, String, RecoveryOutcome> action) {
        return JwtIdentityExtractor.extractEmail(httpRequest)
                .<ResponseEntity<?>>map(userEmail -> toResponse(action.apply(projectId, userEmail)))
                .orElseGet(AssistantRecoveryController::unauthorized);
    }

    private static ResponseEntity<?> toResponse(RecoveryOutcome outcome) {
        if (outcome.ok()) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        HttpStatus status = AssistantRecoveryService.RECOVERY_REQUIRED.equals(outcome.errorCode())
                ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(Map.of(
                "ok", false,
                "errorCode", outcome.errorCode(),
                "message", outcome.message() != null ? outcome.message() : "Recovery failed"));
    }

    private static Map<String, Object> toBody(RecoveryState state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locked", state.locked());
        if (state.reason() != null) {
            body.put("reason", state.reason());
        }
        if (state.lockedAt() != null) {
            body.put("lockedAt", state.lockedAt().toString());
        }
        if (state.operationId() != null) {
            body.put("operationId", state.operationId());
        }
        body.put("canRestore", state.canRestore());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("ok", false, "errorCode", "UNAUTHORIZED",
                        "message", "Missing or invalid Authorization header"));
    }
}
