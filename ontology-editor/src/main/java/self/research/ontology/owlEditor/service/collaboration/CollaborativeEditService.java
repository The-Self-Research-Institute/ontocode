package self.research.ontology.owlEditor.service.collaboration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.model.collaboration.GraphUpdateMessage;
import self.research.ontology.owlEditor.model.collaboration.LockMessage;
import self.research.ontology.owlEditor.model.collaboration.PresenceMessage;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.websocket.WebSocketEventListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollaborativeEditService {

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketEventListener eventListener;
    private final OntologyHistoryService historyService;

    private final Map<String, Queue<EditOperation>> operationHistory = new ConcurrentHashMap<>();

    private final Map<String, Map<String, LockInfo>> projectLocks = new ConcurrentHashMap<>();

    private final Map<String, String> userColors = new ConcurrentHashMap<>();

    private static final String[] COLORS = {
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8",
        "#F7DC6F", "#BB8FCE", "#85C1E2", "#F8B739", "#52B788"
    };

    private int colorIndex = 0;

    public EditOperation processEdit(EditOperation operation) {

        operation.setServerTimestamp(System.currentTimeMillis());

        if (!validateOperation(operation)) {
            log.warn("Invalid operation rejected: {}", operation);
            return null;
        }

        if (isLockedByOther(operation.getProjectId(), operation.getNodeId(), operation.getUserId())) {
            log.warn("Edit rejected - node {} locked by another user", operation.getNodeId());
            sendErrorToUser(operation.getUserId(), operation.getProjectId(),
                "Cannot edit: node is locked by another user");
            return null;
        }

        addToHistory(operation);

        broadcastEdit(operation);

        processEditForGraphUpdate(operation);

        log.info("Processed edit: type={}, node={}, user={}",
                operation.getType(), operation.getNodeId(), operation.getUserId());

        return operation;
    }

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

    public void processPresence(PresenceMessage message) {

        if (message.getType() == PresenceMessage.PresenceType.USER_JOINED) {
            String color = assignColor(message.getUserId());
            message.setColor(color);

            eventListener.registerSession(
                message.getSessionId(),
                message.getProjectId(),
                message.getUserId(),
                message.getUsername()
            );
        }

        message.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSend(
            "/topic/presence/" + message.getProjectId(),
            message
        );

        log.debug("Processed presence: type={}, user={}, project={}",
                message.getType(), message.getUserId(), message.getProjectId());
    }

    public LockMessage acquireLock(String projectId, String nodeId, String userId,
                                   String username, String sessionId) {
        Map<String, LockInfo> locks = projectLocks.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());

        LockInfo existingLock = locks.get(nodeId);

        if (existingLock != null && !existingLock.getUserId().equals(userId)) {

            if (existingLock.getExpiresAt() < System.currentTimeMillis()) {

                locks.remove(nodeId);
                log.info("Expired lock removed for node {}", nodeId);
            } else {

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

        messagingTemplate.convertAndSend("/topic/locks/" + projectId, message);

        log.info("Lock acquired: node={}, user={}, project={}", nodeId, username, projectId);

        return message;
    }

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

    public List<EditOperation> getHistory(String projectId, int limit) {

        List<Map<String, Object>> historyData = historyService.getHistory(projectId, limit);

        return historyData.stream()
                .map(this::convertToEditOperation)
                .toList();
    }

    private EditOperation convertToEditOperation(Map<String, Object> data) {
        EditOperation op = new EditOperation();

        String typeStr = (String) data.get("type");
        EditOperation.OperationType operationType = convertStringToOperationType(typeStr);
        if (operationType == null) {
            operationType = EditOperation.OperationType.CLASS_MODIFIED;
        }
        op.setType(operationType);

        op.setProjectId("");
        op.setNodeId((String) data.getOrDefault("nodeId", ""));
        op.setUserId((String) data.get("userId"));
        op.setUsername((String) data.get("username"));
        op.setSessionId("");
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
            case "createObjectProperty", "createDataProperty", "createProperty", "createAnnotationProperty" -> EditOperation.OperationType.PROPERTY_ADDED;
            case "deleteObjectProperty", "deleteDataProperty", "deleteProperty", "deleteAnnotationProperty" -> EditOperation.OperationType.PROPERTY_DELETED;
            case "addAnnotation" -> EditOperation.OperationType.ANNOTATION_ADDED;
            case "updateAnnotation" -> EditOperation.OperationType.ANNOTATION_MODIFIED;
            case "deleteAnnotation" -> EditOperation.OperationType.ANNOTATION_DELETED;
            case "createIndividual" -> EditOperation.OperationType.INDIVIDUAL_ADDED;
            case "deleteIndividual" -> EditOperation.OperationType.INDIVIDUAL_DELETED;
            case "addClassAssertion", "addObjectPropertyAssertion", "addDataPropertyAssertion",
                 "addNegativeObjectPropertyAssertion", "addNegativeDataPropertyAssertion" -> EditOperation.OperationType.INDIVIDUAL_MODIFIED;
            case "removeClassAssertion", "deleteObjectPropertyAssertion", "deleteDataPropertyAssertion",
                 "deleteNegativeObjectPropertyAssertion", "deleteNegativeDataPropertyAssertion" -> EditOperation.OperationType.INDIVIDUAL_MODIFIED;
            case "addSubClass", "addSubClassOf" -> EditOperation.OperationType.SUBCLASS_ADDED;
            case "removeSubClass", "deleteSubClassOf", "updateSubClassOf" -> EditOperation.OperationType.SUBCLASS_REMOVED;
            case "addPropertyDomain", "deletePropertyDomain",
                 "addPropertyRange", "deletePropertyRange",
                 "addSubPropertyOf", "deleteSubPropertyOf", "addInverseProperty", "deleteInverseProperty",
                 "addPropertyChain", "deletePropertyChain", "addCharacteristic", "deleteCharacteristic" -> EditOperation.OperationType.PROPERTY_MODIFIED;
            case "addDisjointWith" -> EditOperation.OperationType.DISJOINT_ADDED;
            case "deleteDisjointWith", "updateDisjointWith" -> EditOperation.OperationType.DISJOINT_REMOVED;
            case "addEquivalentClass" -> EditOperation.OperationType.EQUIVALENT_ADDED;
            case "deleteEquivalentClass", "updateEquivalentClass" -> EditOperation.OperationType.EQUIVALENT_REMOVED;
            case "addDisjointProperty", "deleteDisjointProperty", "addEquivalentProperty", "deleteEquivalentProperty" -> EditOperation.OperationType.PROPERTY_MODIFIED;
            case "createDatatype" -> EditOperation.OperationType.CLASS_ADDED;
            case "deleteDatatype" -> EditOperation.OperationType.CLASS_DELETED;
            case "addObjectRestriction", "addDataRestriction",
                 "deleteObjectRestriction", "deleteDataRestriction" -> EditOperation.OperationType.CLASS_MODIFIED;
            case "addAxiom", "deleteAxiom", "updateAxiom" -> EditOperation.OperationType.CLASS_MODIFIED;
            case "addDisjointUnion", "deleteDisjointUnion" -> EditOperation.OperationType.CLASS_MODIFIED;
            case "addHasKey", "deleteHasKey" -> EditOperation.OperationType.CLASS_MODIFIED;
            case "updateObjectPropertyLabel", "updateDataPropertyLabel", "updateAnnotationPropertyLabel" -> EditOperation.OperationType.PROPERTY_MODIFIED;
            case "addIntersection", "addUnion",
                 "addGCAIntersection", "addGCAUnion" -> EditOperation.OperationType.CLASS_MODIFIED;
            case "addDatatypeDefinition", "updateDatatypeDefinition", "deleteDatatypeDefinition" -> EditOperation.OperationType.CLASS_MODIFIED;
            case "addSameIndividual", "deleteSameIndividual",
                 "addDifferentIndividual", "deleteDifferentIndividual" -> EditOperation.OperationType.INDIVIDUAL_MODIFIED;
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

    public void broadcastGraphUpdate(GraphUpdateMessage update) {
        messagingTemplate.convertAndSend(
            "/topic/graph/" + update.getProjectId(),
            update
        );

        log.debug("Broadcast graph update: type={}, project={}, user={}",
                update.getType(), update.getProjectId(), update.getUserId());
    }

    public void processEditForGraphUpdate(EditOperation operation) {
        GraphUpdateMessage graphUpdate = convertEditToGraphUpdate(operation);
        if (graphUpdate != null) {
            broadcastGraphUpdate(graphUpdate);
        }
    }

    private GraphUpdateMessage convertEditToGraphUpdate(EditOperation operation) {
        String nodeId = operation.getNodeId();
        Map<String, Object> metadata = operation.getMetadata();
        if (metadata == null) metadata = java.util.Collections.emptyMap();

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

                return null;
        }

        return null;
    }

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

        return !lock.getUserId().equals(userId) &&
               lock.getExpiresAt() > System.currentTimeMillis();
    }

    private void addToHistory(EditOperation operation) {
        Queue<EditOperation> history = operationHistory.computeIfAbsent(
            operation.getProjectId(),
            k -> new ConcurrentLinkedQueue<>()
        );

        history.offer(operation);

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
