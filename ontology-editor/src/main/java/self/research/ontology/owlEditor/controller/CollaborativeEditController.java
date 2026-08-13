package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.model.collaboration.LockMessage;
import self.research.ontology.owlEditor.model.collaboration.PresenceMessage;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.Map;

@Slf4j
@Controller
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", methods = {})
@RequiredArgsConstructor
public class CollaborativeEditController {

    private final SimpMessagingTemplate messagingTemplate;
    private final CollaborativeEditService collaborativeEditService;
    private final WorkspaceOwnershipService workspaceOwnershipService;

    @MessageMapping("/collab/{projectId}/edit")
    public void handleEdit(
            @DestinationVariable String projectId,
            @Payload EditOperation operation,
            SimpMessageHeaderAccessor headerAccessor) {

        String sessionId = headerAccessor.getSessionId();
        operation.setSessionId(sessionId);
        operation.setProjectId(projectId);

        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs != null) {
            String plan = (String) attrs.getOrDefault("plan", "FREE");
            String userId = (String) attrs.get("userId");
            if ("FREE".equalsIgnoreCase(plan) && !workspaceOwnershipService.isUserOwnerOfProject(userId, projectId)) {
                log.warn("[WS-Auth] FREE plan user {} blocked from editing project {}", userId, projectId);
                messagingTemplate.convertAndSendToUser(
                    sessionId, "/queue/errors",
                    Map.of("error", "Your current plan is Free. Upgrade to Pro to edit ontologies.",
                           "requiresUpgrade", true)
                );
                return;
            }
        }

        log.debug("Received edit for project {} from session {}: {}",
                projectId, sessionId, operation.getType());

        collaborativeEditService.processEdit(operation);
    }

    @MessageMapping("/collab/{projectId}/presence")
    public void handlePresence(
            @DestinationVariable String projectId,
            @Payload PresenceMessage message,
            SimpMessageHeaderAccessor headerAccessor) {

        String sessionId = headerAccessor.getSessionId();
        message.setSessionId(sessionId);
        message.setProjectId(projectId);

        log.debug("Presence update for project {} from session {}: {}",
                projectId, sessionId, message.getType());

        collaborativeEditService.processPresence(message);
    }

    @MessageMapping("/collab/{projectId}/lock")
    public void handleLock(
            @DestinationVariable String projectId,
            @Payload LockMessage message,
            SimpMessageHeaderAccessor headerAccessor) {

        String sessionId = headerAccessor.getSessionId();
        message.setSessionId(sessionId);
        message.setProjectId(projectId);

        log.debug("Lock operation for project {} from session {}: {}",
                projectId, sessionId, message.getType());

        if (message.getType() == LockMessage.LockType.LOCK_REQUEST) {
            collaborativeEditService.acquireLock(
                projectId,
                message.getNodeId(),
                message.getUserId(),
                message.getUsername(),
                sessionId
            );
        } else if (message.getType() == LockMessage.LockType.LOCK_RELEASED) {
            collaborativeEditService.releaseLock(
                projectId,
                message.getNodeId(),
                message.getUserId(),
                sessionId
            );
        }
    }

    public void sendToUser(String userId, String projectId, Object message) {
        String destination = String.format("/queue/ontology/%s", projectId);
        messagingTemplate.convertAndSendToUser(userId, destination, message);
        log.debug("Sent private message to user {} for project {}", userId, projectId);
    }
}
