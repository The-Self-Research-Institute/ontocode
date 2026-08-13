package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphUpdateMessage {

    public enum UpdateType {

        FULL_REFRESH,

        DELTA_UPDATE,

        NODE_ADDED,

        NODE_UPDATED,

        NODE_DELETED,

        EDGE_ADDED,

        EDGE_DELETED,

        NODE_SELECTED,

        CURSOR_MOVED,

        NODE_EXPANDED
    }

    private UpdateType type;
    private String projectId;
    private String userId;
    private String username;
    private long timestamp;

    private List<GraphNode> addedNodes;
    private List<GraphNode> updatedNodes;
    private List<String> deletedNodeIds;
    private List<GraphEdge> addedEdges;
    private List<GraphEdge> deletedEdges;

    private String selectedNodeId;
    private CursorPosition cursor;
    private String userColor;

    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphNode {
        private String id;
        private String label;
        private String type;
        private String color;
        private boolean expanded;
        private boolean hasChildren;
        private Map<String, Object> metadata;

        public GraphNode(String id, String label, String type) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.metadata = new HashMap<>();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphEdge {
        private String id;
        private String from;
        private String to;
        private String label;
        private String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CursorPosition {
        private double x;
        private double y;
        private String nodeId;
    }

    public static GraphUpdateMessage nodeAdded(String projectId, String userId, String username,
                                               GraphNode node) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.NODE_ADDED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .addedNodes(List.of(node))
                .build();
    }

    public static GraphUpdateMessage nodeUpdated(String projectId, String userId, String username,
                                                 GraphNode node) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.NODE_UPDATED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .updatedNodes(List.of(node))
                .build();
    }

    public static GraphUpdateMessage nodeDeleted(String projectId, String userId, String username,
                                                 String nodeId) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.NODE_DELETED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .deletedNodeIds(List.of(nodeId))
                .build();
    }

    public static GraphUpdateMessage edgeAdded(String projectId, String userId, String username,
                                               GraphEdge edge) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.EDGE_ADDED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .addedEdges(List.of(edge))
                .build();
    }

    public static GraphUpdateMessage edgeDeleted(String projectId, String userId, String username,
                                                 GraphEdge edge) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.EDGE_DELETED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .deletedEdges(List.of(edge))
                .build();
    }

    public static GraphUpdateMessage deltaUpdate(String projectId, String userId, String username,
                                                  List<GraphNode> addedNodes,
                                                  List<GraphNode> updatedNodes,
                                                  List<String> deletedNodeIds,
                                                  List<GraphEdge> addedEdges,
                                                  List<GraphEdge> deletedEdges) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.DELTA_UPDATE)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .addedNodes(addedNodes != null ? addedNodes : new ArrayList<>())
                .updatedNodes(updatedNodes != null ? updatedNodes : new ArrayList<>())
                .deletedNodeIds(deletedNodeIds != null ? deletedNodeIds : new ArrayList<>())
                .addedEdges(addedEdges != null ? addedEdges : new ArrayList<>())
                .deletedEdges(deletedEdges != null ? deletedEdges : new ArrayList<>())
                .build();
    }

    public static GraphUpdateMessage nodeSelected(String projectId, String userId, String username,
                                                  String nodeId, String userColor) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.NODE_SELECTED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .selectedNodeId(nodeId)
                .userColor(userColor)
                .build();
    }

    public static GraphUpdateMessage cursorMoved(String projectId, String userId, String username,
                                                 CursorPosition cursor, String userColor) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.CURSOR_MOVED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .cursor(cursor)
                .userColor(userColor)
                .build();
    }

    public static GraphUpdateMessage nodeExpanded(String projectId, String userId, String username,
                                                  String nodeId, List<GraphNode> childNodes,
                                                  List<GraphEdge> childEdges) {
        return GraphUpdateMessage.builder()
                .type(UpdateType.NODE_EXPANDED)
                .projectId(projectId)
                .userId(userId)
                .username(username)
                .timestamp(System.currentTimeMillis())
                .selectedNodeId(nodeId)
                .addedNodes(childNodes != null ? childNodes : new ArrayList<>())
                .addedEdges(childEdges != null ? childEdges : new ArrayList<>())
                .build();
    }
}
