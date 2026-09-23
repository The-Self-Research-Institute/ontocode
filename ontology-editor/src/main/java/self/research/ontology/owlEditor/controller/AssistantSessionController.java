package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

@Slf4j
@RestController
@RequestMapping("/api/v1/code-assistant/sessions")
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", allowCredentials = "false",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AssistantSessionController {

    private final AssistantSessionService sessionService;

    public AssistantSessionController(AssistantSessionService sessionService) {
        this.sessionService = sessionService;
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

        AssistantSessionDocument session = sessionService.createSession(
                request.getProjectId(), userEmail, request.getDocumentPath(),
                request.getActionType(), request.getActionContext());

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
}
