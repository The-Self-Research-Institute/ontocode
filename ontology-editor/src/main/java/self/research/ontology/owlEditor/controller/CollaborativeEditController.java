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
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.document.WorkspaceDocument;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.model.collaboration.LockMessage;
import self.research.ontology.owlEditor.model.collaboration.PresenceMessage;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.repository.WorkspaceRepository;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.Map;
import java.util.Optional;

/**
 * WebSocket controller for handling real-time collaborative editing messages.
 * 
 * Message Flow:
 * - Client sends to: /app/collab/{projectId}/edit
 * - Server broadcasts to: /topic/ontology/{projectId}
 * 
 * Topics:
 * - /topic/ontology/{projectId} - Ontology edit operations
 * - /topic/presence/{projectId} - User presence (join/leave/cursor)
 * - /topic/locks/{projectId} - Node lock/unlock notifications
 */
@Slf4j
@Controller
@CrossOrigin(originPatterns = "*", allowedHeaders = "*", methods = {})
@RequiredArgsConstructor
public class CollaborativeEditController {

    private final SimpMessagingTemplate messagingTemplate;
    private final CollaborativeEditService collaborativeEditService;
    private final ProjectRepository projectRepository;
    private final WorkspaceRepository workspaceRepository;

    /**
     * Handle edit operations from clients and broadcast to all subscribers.
     * FREE plan non-owners are rejected to prevent unauthorized ontology mutations.
     */
    @MessageMapping("/collab/{projectId}/edit")
    public void handleEdit(
            @DestinationVariable String projectId,
            @Payload EditOperation operation,
            SimpMessageHeaderAccessor headerAccessor) {

        String sessionId = headerAccessor.getSessionId();
        operation.setSessionId(sessionId);
        operation.setProjectId(projectId);

        // Enforce FREE plan restriction via session attributes populated by WebSocketAuthChannelInterceptor
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs != null) {
            String plan = (String) attrs.getOrDefault("plan", "FREE");
            String userId = (String) attrs.get("userId");
            if ("FREE".equalsIgnoreCase(plan) && !isWorkspaceOwner(userId, projectId)) {
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

        // Process and broadcast through service
        collaborativeEditService.processEdit(operation);
    }

    private boolean isWorkspaceOwner(String userId, String projectId) {
        if (userId == null || projectId == null) return false;
        try {
            Optional<ProjectDocument> proj = projectRepository.findById(projectId);
            if (proj.isEmpty() || proj.get().getWorkspaceId() == null) return false;
            Optional<WorkspaceDocument> ws = workspaceRepository.findByWorkspaceId(proj.get().getWorkspaceId());
            return ws.isPresent() && userId.equals(ws.get().getOwnerId());
        } catch (Exception e) {
            log.debug("[WS-Auth] Could not verify workspace ownership for userId={} projectId={}: {}", userId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Handle user presence updates (join, leave, cursor movement).
     */
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

    /**
     * Handle lock/unlock requests for ontology nodes.
     */
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

    /**
     * Send a message to a specific user.
     * Used for sending error notifications or conflict warnings.
     */
    public void sendToUser(String userId, String projectId, Object message) {
        String destination = String.format("/queue/ontology/%s", projectId);
        messagingTemplate.convertAndSendToUser(userId, destination, message);
        log.debug("Sent private message to user {} for project {}", userId, projectId);
    }
}
