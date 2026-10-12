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
import self.research.ontology.owlEditor.config.EditorApiAuthInterceptor;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.dto.AssistantSessionCreateRequest;
import self.research.ontology.owlEditor.dto.AssistantSessionResponse;
import self.research.ontology.owlEditor.service.AssistantAdmissionLimiter;
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final ProjectAccessService projectAccessService;
    private final DraftCopyService draftCopyService;

    public AssistantSessionController(AssistantSessionService sessionService,
                                      AssistantAdmissionLimiter admissionLimiter,
                                      ProjectAccessService projectAccessService,
                                      DraftCopyService draftCopyService) {
        this.sessionService = sessionService;
        this.admissionLimiter = admissionLimiter;
        this.projectAccessService = projectAccessService;
        this.draftCopyService = draftCopyService;
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestBody AssistantSessionCreateRequest request,
                                            HttpServletRequest httpRequest) {
        String userEmail = JwtIdentityExtractor.extractEmail(httpRequest).orElse(null);
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "errorCode", "UNAUTHORIZED",
                            "message", "Missing or invalid Authorization header"));
        }
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "errorCode", "VALIDATION_FAILED", "message", "projectId is required"));
        }
        if (lacksProjectAccess(httpRequest, request.getProjectId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "errorCode", "FORBIDDEN",
                            "message", "You do not have access to this project"));
        }
        String provider = normalize(request.getProvider());
        String model = normalize(request.getModel());
        if ((provider != null && provider.length() > MAX_PROVIDER_LENGTH)
                || (model != null && model.length() > MAX_MODEL_LENGTH)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "errorCode", "VALIDATION_FAILED",
                    "message", "provider must be at most " + MAX_PROVIDER_LENGTH + " characters and model at most "
                            + MAX_MODEL_LENGTH));
        }

        String draftUserId = null;
        if (request.isDraft()) {
            draftUserId = JwtIdentityExtractor.extractUserId(httpRequest).orElse(null);
            if (draftUserId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("ok", false, "errorCode", "UNAUTHORIZED",
                                "message", "Could not verify who this draft session belongs to"));
            }
            if (!draftCopyService.isReady(request.getProjectId(), draftUserId)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("ok", false, "errorCode", "DRAFT_NOT_READY",
                                "message", "Your draft copy isn't ready yet. Try again in a moment."));
            }
        }

        AssistantSessionService.SessionCreateOutcome outcome = sessionService.createSession(
                request.getProjectId(), userEmail, request.getDocumentPath(),
                request.getActionType(), request.getActionContext(), provider, model,
                request.getTokenBudget(), request.getRetrievalAttempts(),
                request.isDraft(), draftUserId,
                () -> admissionLimiter.tryAdmitSessionCreate(userEmail));
        if (!outcome.created()) {
            return rateLimited(outcome.retryAfterSeconds());
        }
        AssistantSessionDocument session = outcome.session();

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
                        .maxRetrievalCalls(session.getRetrievalAttemptsRemaining())
                        .build())
                .expiresAt(session.getExpiresAt())
                .build();

        return ResponseEntity.ok(response);
    }

    private boolean lacksProjectAccess(HttpServletRequest httpRequest, String projectId) {
        Object verifiedEmail = httpRequest.getAttribute(EditorApiAuthInterceptor.VERIFIED_EMAIL_ATTRIBUTE);
        return verifiedEmail != null && !projectAccessService.hasProjectAccess(projectId, verifiedEmail.toString());
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
