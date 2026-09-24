package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.dto.AssistantSessionCreateRequest;
import self.research.ontology.owlEditor.dto.AssistantSessionResponse;
import self.research.ontology.owlEditor.service.AssistantAdmissionLimiter;
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant/sessions")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AssistantSessionController {

    private static final int MAX_PROVIDER_LENGTH = 32;
    private static final int MAX_MODEL_LENGTH = 128;

    private final AssistantSessionService sessionService;
    private final AssistantAdmissionLimiter admissionLimiter;

    public AssistantSessionController(AssistantSessionService sessionService,
                                      AssistantAdmissionLimiter admissionLimiter) {
        this.sessionService = sessionService;
        this.admissionLimiter = admissionLimiter;
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestBody AssistantSessionCreateRequest request,
                                            HttpServletRequest httpRequest) {
        String userEmail = JwtIdentityExtractor.extractEmail(httpRequest).orElse(null);
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(java.util.Map.of("ok", false, "message", "Missing or invalid Authorization header"));
        }
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("ok", false, "message", "projectId is required"));
        }
        String provider = normalize(request.getProvider());
        String model = normalize(request.getModel());
        if ((provider != null && provider.length() > MAX_PROVIDER_LENGTH)
                || (model != null && model.length() > MAX_MODEL_LENGTH)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "errorCode", "VALIDATION_FAILED",
                    "message", "provider must be at most " + MAX_PROVIDER_LENGTH + " characters and model at most "
                            + MAX_MODEL_LENGTH));
        }

        Optional<Integer> retryAfter = sessionService.activeSessionLimitRetryAfter(userEmail)
                .or(() -> admissionLimiter.tryAdmitSessionCreate(userEmail));
        if (retryAfter.isPresent()) {
            sessionService.recordCreateRejected(userEmail, request.getProjectId(), provider, model,
                    "RATE_LIMITED", "retryAfterSeconds=" + retryAfter.get());
            return rateLimited(retryAfter.get());
        }

        AssistantSessionDocument session = sessionService.createSession(
                request.getProjectId(), userEmail, request.getDocumentPath(),
                request.getActionType(), request.getActionContext(), provider, model);

        AssistantSessionResponse response = AssistantSessionResponse.builder()
                .sessionId(session.getId())
                .snapshot(AssistantSessionResponse.Snapshot.builder()
                        .projectId(session.getProjectId())
                        .documentPath(session.getDocumentPath())
                        .revision(session.getPinnedRevision())
                        .actionType(session.getActionType())
                        .build())
                .budget(AssistantSessionResponse.Budget.builder()
                        .retrievalCallsRemaining(session.getRetrievalAttemptsRemaining())
                        .maxRetrievalCalls(sessionService.getMaxRetrievalAttempts())
                        .build())
                .expiresAt(session.getExpiresAt())
                .build();

        return ResponseEntity.ok(response);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseEntity<Map<String, Object>> rateLimited(int retryAfterSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorCode", "RATE_LIMITED");
        body.put("message", "Too many assistant sessions. Retry in " + retryAfterSeconds + "s.");
        body.put("retryAfterSeconds", retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(body);
    }
}
