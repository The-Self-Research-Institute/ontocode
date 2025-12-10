package self.research.ontology.owlEditor.service.collaboration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.model.collaboration.GraphUpdateMessage;
import self.research.ontology.owlEditor.model.collaboration.LockMessage;
import self.research.ontology.owlEditor.model.collaboration.PresenceMessage;
import self.research.ontology.owlEditor.service.GraphDBHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.websocket.WebSocketEventListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Core service for managing collaborative editing sessions.
 * Handles edit operations, conflict resolution, and broadcasting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollaborativeEditService {

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketEventListener eventListener;
    private final GraphDBHistoryService historyService;
    
    // Operation history per project: projectId -> Queue<EditOperation>
    private final Map<String, Queue<EditOperation>> operationHistory = new ConcurrentHashMap<>();
    
    // Active locks per project: projectId -> Map<nodeId, LockInfo>
    private final Map<String, Map<String, LockInfo>> projectLocks = new ConcurrentHashMap<>();
    
    // User colors: userId -> color (hex)
    private final Map<String, String> userColors = new ConcurrentHashMap<>();
    
    private static final String[] COLORS = {
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8",
        "#F7DC6F", "#BB8FCE", "#85C1E2", "#F8B739", "#52B788"
    };
    
    private int colorIndex = 0;

    /**
     * Process an edit operation from a client.
     * Validates, applies server timestamp, and broadcasts to all clients.
     */
    public EditOperation processEdit(EditOperation operation) {
        // Add server timestamp
        operation.setServerTimestamp(System.currentTimeMillis());
        
        // Validate the operation
        if (!validateOperation(operation)) {
            log.warn("Invalid operation rejected: {}", operation);
            return null;
        }
        
        // Check if node is locked by another user
        if (isLockedByOther(operation.getProjectId(), operation.getNodeId(), operation.getUserId())) {
            log.warn("Edit rejected - node {} locked by another user", operation.getNodeId());
            sendErrorToUser(operation.getUserId(), operation.getProjectId(), 
                "Cannot edit: node is locked by another user");
            return null;
        }
        
        // Add to operation history
        addToHistory(operation);
        
        // Broadcast to all clients in the project
        broadcastEdit(operation);

        // Also broadcast graph update for visualization clients
        processEditForGraphUpdate(operation);

        log.info("Processed edit: type={}, node={}, user={}",
                operation.getType(), operation.getNodeId(), operation.getUserId());

        return operation;
    }

    /**
     * Broadcast a mutation that was applied through the REST API so that
     * collaborative clients still receive real-time updates.
     */
    public void broadcastMutation(String projectId, MutationOp mutation,
                                  String userId, String username) {
        EditOperation.OperationType operationType = convertStringToOperationType(mutation.type());
        if (operationType == null) {
            log.debug("Skipping broadcast for unsupported mutation type: {}", mutation.type());
            return;
        }

        String nodeId = resolveNodeId(mutation);
        if (nodeId == null || nodeId.isBlank()) {
            log.debug("Skipping broadcast for mutation without node id: {}", mutation);
            return;
        }

        EditOperation operation = EditOperation.builder()
            .type(operationType)
            .projectId(projectId)
            .nodeId(nodeId)
            .property(resolvePropertyName(mutation))
            .value(resolveValue(mutation))
            .metadata(buildMetadata(mutation))
            .userId(userId)
            .username(username)
            .sessionId("rest-mutation")
            .timestamp(System.currentTimeMillis())
            .build();

        processEdit(operation);
    }

    /**
     * Process a presence update (user joined, cursor moved, etc.).
     */
    public void processPresence(PresenceMessage message) {
        // Assign color to new users
        if (message.getType() == PresenceMessage.PresenceType.USER_JOINED) {
            String color = assignColor(message.getUserId());
            message.setColor(color);
            
            // Register session
            eventListener.registerSession(
                message.getSessionId(), 
                message.getProjectId(), 
                message.getUserId(), 
                message.getUsername()
            );
        }
        
        message.setTimestamp(System.currentTimeMillis());
        
        // Broadcast presence update
        messagingTemplate.convertAndSend(
            "/topic/presence/" + message.getProjectId(),
            message
        );
        
        log.debug("Processed presence: type={}, user={}, project={}", 
                message.getType(), message.getUserId(), message.getProjectId());
    }

    /**
     * Acquire a lock on a node for editing.
     */
    public LockMessage acquireLock(String projectId, String nodeId, String userId, 
                                   String username, String sessionId) {
        Map<String, LockInfo> locks = projectLocks.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
        
        LockInfo existingLock = locks.get(nodeId);
        
        // Check if already locked
        if (existingLock != null && !existingLock.getUserId().equals(userId)) {
            // Check if lock expired
            if (existingLock.getExpiresAt() < System.currentTimeMillis()) {
                // Lock expired, release it
                locks.remove(nodeId);
                log.info("Expired lock removed for node {}", nodeId);
            } else {
                // Lock still valid, deny request
                return LockMessage.builder()
                        .type(LockMessage.LockType.LOCK_DENIED)
                        .projectId(projectId)
                        .nodeId(nodeId)
                        .userId(userId)
                        .username(username)
                        .sessionId(sessionId)
                        .success(false)
                        .error("Node is locked by " + existingLock.getUsername())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }
        }
        
        // Acquire lock (30 second timeout)
        long expiresAt = System.currentTimeMillis() + 30000;
        LockInfo lock = new LockInfo(userId, username, sessionId, expiresAt);
        locks.put(nodeId, lock);
        
        LockMessage message = LockMessage.builder()
                .type(LockMessage.LockType.LOCK_ACQUIRED)
                .projectId(projectId)
                .nodeId(nodeId)
                .userId(userId)
                .username(username)
                .sessionId(sessionId)
                .expiresAt(expiresAt)
                .success(true)
                .timestamp(System.currentTimeMillis())
                .build();
        
        // Broadcast lock acquisition
        messagingTemplate.convertAndSend("/topic/locks/" + projectId, message);
        
        log.info("Lock acquired: node={}, user={}, project={}", nodeId, username, projectId);
        
        return message;
    }

    /**
     * Release a lock on a node.
     */
    public void releaseLock(String projectId, String nodeId, String userId, String sessionId) {
        Map<String, LockInfo> locks = projectLocks.get(projectId);
        if (locks == null) return;
        
        LockInfo lock = locks.get(nodeId);
        if (lock != null && lock.getUserId().equals(userId)) {
            locks.remove(nodeId);
            
            LockMessage message = LockMessage.builder()
                    .type(LockMessage.LockType.LOCK_RELEASED)
                    .projectId(projectId)
                    .nodeId(nodeId)
                    .userId(userId)
                    .username(lock.getUsername())
                    .sessionId(sessionId)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            messagingTemplate.convertAndSend("/topic/locks/" + projectId, message);
            
            log.info("Lock released: node={}, user={}, project={}", nodeId, lock.getUsername(), projectId);
        }
    }

    /**
     * Release all locks held by a user (on disconnect).
     */
    public void releaseUserLocks(String projectId, String sessionId) {
        Map<String, LockInfo> locks = projectLocks.get(projectId);
        if (locks == null) return;
        
        List<String> toRelease = new ArrayList<>();
        locks.forEach((nodeId, lock) -> {
            if (lock.getSessionId().equals(sessionId)) {
                toRelease.add(nodeId);
            }
        });
        
        for (String nodeId : toRelease) {
            LockInfo lock = locks.remove(nodeId);
            if (lock != null) {
                LockMessage message = LockMessage.builder()
                        .type(LockMessage.LockType.LOCK_EXPIRED)
                        .projectId(projectId)
                        .nodeId(nodeId)
                        .userId(lock.getUserId())
                        .username(lock.getUsername())
                        .sessionId(sessionId)
                        .timestamp(System.currentTimeMillis())
                        .build();
                
                messagingTemplate.convertAndSend("/topic/locks/" + projectId, message);
            }
        }
        
        log.info("Released {} locks for session {}", toRelease.size(), sessionId);
    }

    /**
     * Get operation history for a project from GraphDB.
     */
    public List<EditOperation> getHistory(String projectId, int limit) {
        // Read from GraphDB history graph
        List<Map<String, Object>> historyData = historyService.getHistory(projectId, limit);
        
        // Convert to EditOperation objects
        return historyData.stream()
                .map(this::convertToEditOperation)
                .toList();
    }
    
    private EditOperation convertToEditOperation(Map<String, Object> data) {
        EditOperation op = new EditOperation();
        
        // Convert string operation type to enum
        String typeStr = (String) data.get("type");
        EditOperation.OperationType operationType = convertStringToOperationType(typeStr);
        if (operationType == null) {
            operationType = EditOperation.OperationType.CLASS_MODIFIED;
        }
        op.setType(operationType);
        
        op.setProjectId(""); // Not stored in GraphDB history
        op.setNodeId((String) data.getOrDefault("nodeId", ""));
        op.setUserId((String) data.get("userId"));
        op.setUsername((String) data.get("username"));
        op.setSessionId(""); // Not stored in GraphDB history
        op.setTimestamp((Long) data.get("timestamp"));
        op.setServerTimestamp((Long) data.get("serverTimestamp"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
        if (metadata != null) {
            op.setMetadata(metadata);
        }
        
        return op;
    }
    
    private EditOperation.OperationType convertStringToOperationType(String type) {
        if (type == null) return null;
        
        return switch (type) {
            case "createClass" -> EditOperation.OperationType.CLASS_ADDED;
            case "deleteClass" -> EditOperation.OperationType.CLASS_DELETED;
            case "updateClassLabel" -> EditOperation.OperationType.CLASS_MODIFIED;
            case "renameClass" -> EditOperation.OperationType.CLASS_RENAMED;
            case "createObjectProperty", "createDataProperty", "createProperty" -> EditOperation.OperationType.PROPERTY_ADDED;
            case "deleteObjectProperty", "deleteDataProperty", "deleteProperty" -> EditOperation.OperationType.PROPERTY_DELETED;
            case "addAnnotation" -> EditOperation.OperationType.ANNOTATION_ADDED;
            case "updateAnnotation" -> EditOperation.OperationType.ANNOTATION_MODIFIED;
            case "deleteAnnotation" -> EditOperation.OperationType.ANNOTATION_DELETED;
            case "createIndividual" -> EditOperation.OperationType.INDIVIDUAL_ADDED;
            case "deleteIndividual" -> EditOperation.OperationType.INDIVIDUAL_DELETED;
            case "addSubClass", "addSubClassOf" -> EditOperation.OperationType.SUBCLASS_ADDED;
            case "removeSubClass", "deleteSubClassOf" -> EditOperation.OperationType.SUBCLASS_REMOVED;
            case "addDisjointWith" -> EditOperation.OperationType.DISJOINT_ADDED;
            case "deleteDisjointWith" -> EditOperation.OperationType.DISJOINT_REMOVED;
            case "addEquivalentClass" -> EditOperation.OperationType.EQUIVALENT_ADDED;
            case "deleteEquivalentClass" -> EditOperation.OperationType.EQUIVALENT_REMOVED;
            default -> null;
        };
    }

    private String resolveNodeId(MutationOp mutation) {
        if (mutation.iri() != null && !mutation.iri().isBlank()) {
            return mutation.iri();
        }
        return mutation.target();
    }

    private String resolvePropertyName(MutationOp mutation) {
        if (mutation.property() != null && !mutation.property().isBlank()) {
            return mutation.property();
        }
        return switch (mutation.type()) {
            case "createClass", "updateClassLabel" -> "label";
            case "addSubClassOf", "deleteSubClassOf" -> "subClassOf";
            case "addEquivalentClass", "deleteEquivalentClass" -> "equivalentClass";
            case "addDisjointWith", "deleteDisjointWith" -> "disjointWith";
            case "addPropertyDomain", "deletePropertyDomain" -> "domain";
            case "addPropertyRange", "deletePropertyRange" -> "range";
            case "addSubPropertyOf", "deleteSubPropertyOf" -> "subPropertyOf";
            case "addInverseProperty", "deleteInverseProperty" -> "inverseOf";
            case "addDisjointProperty", "deleteDisjointProperty" -> "propertyDisjointWith";
            case "addEquivalentProperty", "deleteEquivalentProperty" -> "equivalentProperty";
            case "addCharacteristic", "deleteCharacteristic" -> "characteristic";
            default -> null;
        };
    }

    private Object resolveValue(MutationOp mutation) {
        if (mutation.value() != null && !mutation.value().isBlank()) {
            return mutation.value();
        }
        if (mutation.target() != null && !mutation.target().isBlank()) {
            return mutation.target();
        }
        if (mutation.label() != null && !mutation.label().isBlank()) {
            return mutation.label();
        }
        if (mutation.parent() != null && !mutation.parent().isBlank()) {
            return mutation.parent();
        }
        return null;
    }

    private Map<String, Object> buildMetadata(MutationOp mutation) {
        Map<String, Object> metadata = new HashMap<>();
        if (mutation.label() != null) {
            metadata.put("label", mutation.label());
        }
        if (mutation.parent() != null) {
            metadata.put("parent", mutation.parent());
        }
        if (mutation.property() != null) {
            metadata.put("property", mutation.property());
        }
        if (mutation.target() != null) {
            metadata.put("target", mutation.target());
        }
        if (mutation.classIri() != null) {
            metadata.put("classIri", mutation.classIri());
        }
        return metadata.isEmpty() ? null : metadata;
    }

    /**
     * Get active users in a project.
     */
    public List<Map<String, Object>> getActiveUsers(String projectId) {
        Map<String, WebSocketEventListener.UserSession> sessions = eventListener.getProjectSessions(projectId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (WebSocketEventListener.UserSession session : sessions.values()) {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("userId", session.getUserId());
            userMap.put("username", session.getUsername());
            userMap.put("sessionId", session.getSessionId());
            userMap.put("color", userColors.getOrDefault(session.getUserId(), "#999999"));
            userMap.put("lastActivity", session.getLastActivity());
            result.add(userMap);
        }
        return result;
    }

    /**
     * Broadcast graph update to all clients viewing the graph.
     */
    public void broadcastGraphUpdate(GraphUpdateMessage update) {
        messagingTemplate.convertAndSend(
            "/topic/graph/" + update.getProjectId(),
            update
        );

        log.debug("Broadcast graph update: type={}, project={}, user={}",
                update.getType(), update.getProjectId(), update.getUserId());
    }

    /**
     * Convert EditOperation to GraphUpdateMessage for graph view clients.
     */
    public void processEditForGraphUpdate(EditOperation operation) {
        GraphUpdateMessage graphUpdate = convertEditToGraphUpdate(operation);
        if (graphUpdate != null) {
            broadcastGraphUpdate(graphUpdate);
        }
    }

    private GraphUpdateMessage convertEditToGraphUpdate(EditOperation operation) {
        String nodeId = operation.getNodeId();
        Map<String, Object> metadata = operation.getMetadata();

        switch (operation.getType()) {
            case CLASS_ADDED:
                GraphUpdateMessage.GraphNode newClassNode = GraphUpdateMessage.GraphNode.builder()
                    .id(nodeId)
                    .label((String) metadata.getOrDefault("label", nodeId))
                    .type("class")
                    .hasChildren(false)
                    .expanded(false)
                    .build();

                return GraphUpdateMessage.nodeAdded(
                    operation.getProjectId(),
                    operation.getUserId(),
                    operation.getUsername(),
                    newClassNode
                );

            case CLASS_DELETED:
                return GraphUpdateMessage.nodeDeleted(
                    operation.getProjectId(),
                    operation.getUserId(),
                    operation.getUsername(),
                    nodeId
                );

            case CLASS_MODIFIED:
            case CLASS_RENAMED:
                GraphUpdateMessage.GraphNode updatedNode = GraphUpdateMessage.GraphNode.builder()
                    .id(nodeId)
                    .label((String) metadata.getOrDefault("label", nodeId))
                    .type("class")
                    .build();

                return GraphUpdateMessage.nodeUpdated(
                    operation.getProjectId(),
                    operation.getUserId(),
                    operation.getUsername(),
                    updatedNode
                );

            case PROPERTY_ADDED:
                String propertyType = (String) metadata.getOrDefault("propertyType", "property");
                GraphUpdateMessage.GraphNode propertyNode = GraphUpdateMessage.GraphNode.builder()
                    .id(nodeId)
                    .label((String) metadata.getOrDefault("label", nodeId))
                    .type(propertyType)
                    .hasChildren(false)
                    .expanded(false)
                    .build();

                return GraphUpdateMessage.nodeAdded(
                    operation.getProjectId(),
                    operation.getUserId(),
                    operation.getUsername(),
                    propertyNode
                );

            case PROPERTY_DELETED:
                return GraphUpdateMessage.nodeDeleted(
                    operation.getProjectId(),
                    operation.getUserId(),
                    operation.getUsername(),
                    nodeId
                );

            case INDIVIDUAL_ADDED:
                GraphUpdateMessage.GraphNode individualNode = GraphUpdateMessage.GraphNode.builder()
                    .id(nodeId)
                    .label((String) metadata.getOrDefault("label", nodeId))
                    .type("individual")
                    .hasChildren(false)
                    .expanded(false)
                    .build();

                return GraphUpdateMessage.nodeAdded(
                    operation.getProjectId(),
                    operation.getUserId(),
                    operation.getUsername(),
                    individualNode
                );

            case INDIVIDUAL_DELETED:
                return GraphUpdateMessage.nodeDeleted(
                    operation.getProjectId(),
                    operation.getUserId(),
                    operation.getUsername(),
                    nodeId
                );

            case SUBCLASS_ADDED:
                String parentId = (String) metadata.get("parentId");
                if (parentId != null) {
                    GraphUpdateMessage.GraphEdge edge = GraphUpdateMessage.GraphEdge.builder()
                        .id(nodeId + "_" + parentId)
                        .from(nodeId)
                        .to(parentId)
                        .label("subClassOf")
                        .type("subClassOf")
                        .build();

                    return GraphUpdateMessage.edgeAdded(
                        operation.getProjectId(),
                        operation.getUserId(),
                        operation.getUsername(),
                        edge
                    );
                }
                break;

            case SUBCLASS_REMOVED:
                String removedParentId = (String) metadata.get("parentId");
                if (removedParentId != null) {
                    GraphUpdateMessage.GraphEdge edge = GraphUpdateMessage.GraphEdge.builder()
                        .id(nodeId + "_" + removedParentId)
                        .from(nodeId)
                        .to(removedParentId)
                        .label("subClassOf")
                        .type("subClassOf")
                        .build();

                    return GraphUpdateMessage.edgeDeleted(
                        operation.getProjectId(),
                        operation.getUserId(),
                        operation.getUsername(),
                        edge
                    );
                }
                break;

            default:
                // For other operations, no graph update needed
                return null;
        }

        return null;
    }

    // Private helper methods

    private boolean validateOperation(EditOperation operation) {
        return operation.getProjectId() != null &&
               operation.getNodeId() != null &&
               operation.getType() != null &&
               operation.getUserId() != null;
    }

    private boolean isLockedByOther(String projectId, String nodeId, String userId) {
        Map<String, LockInfo> locks = projectLocks.get(projectId);
        if (locks == null) return false;
        
        LockInfo lock = locks.get(nodeId);
        if (lock == null) return false;
        
        // Check if locked by another user and not expired
        return !lock.getUserId().equals(userId) && 
               lock.getExpiresAt() > System.currentTimeMillis();
    }

    private void addToHistory(EditOperation operation) {
        Queue<EditOperation> history = operationHistory.computeIfAbsent(
            operation.getProjectId(), 
            k -> new ConcurrentLinkedQueue<>()
        );
        
        history.offer(operation);
        
        // Keep last 1000 operations
        while (history.size() > 1000) {
            history.poll();
        }
    }

    private void broadcastEdit(EditOperation operation) {
        messagingTemplate.convertAndSend(
            "/topic/ontology/" + operation.getProjectId(),
            operation
        );
    }

    private void sendErrorToUser(String userId, String projectId, String error) {
        Map<String, Object> errorMessage = Map.of(
            "type", "ERROR",
            "message", error,
            "timestamp", System.currentTimeMillis()
        );
        
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/ontology/" + projectId, 
            errorMessage
        );
    }

    private String assignColor(String userId) {
        return userColors.computeIfAbsent(userId, k -> {
            String color = COLORS[colorIndex % COLORS.length];
            colorIndex++;
            return color;
        });
    }

    // Inner class for lock information
    private static class LockInfo {
        private final String userId;
        private final String username;
        private final String sessionId;
        private final long expiresAt;

        public LockInfo(String userId, String username, String sessionId, long expiresAt) {
            this.userId = userId;
            this.username = username;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
        }

        public String getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getSessionId() { return sessionId; }
        public long getExpiresAt() { return expiresAt; }
    }
}
