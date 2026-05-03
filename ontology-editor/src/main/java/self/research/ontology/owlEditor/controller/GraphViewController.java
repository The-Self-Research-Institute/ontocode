package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.collaboration.GraphUpdateMessage;
import self.research.ontology.owlEditor.service.GraphGeneratingService;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.springframework.data.mongodb.gridfs.GridFsResource;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Map;

/**
 * REST and WebSocket controller for collaborative graph visualization.
 * Handles graph data queries, lazy loading, and real-time updates.
 */
@Slf4j
@RestController
@RequestMapping("/api/collab-graph")
@CrossOrigin(originPatterns = "*")
@RequiredArgsConstructor
public class GraphViewController {

    private final GraphGeneratingService graphGeneratingService;
    private final GridFsTemplate gridfs;
    private final CollaborativeEditService collaborativeEditService;
    private final self.research.ontology.owlEditor.service.GraphDBDatasetService graphDBDatasetService;
    private final java.util.Map<String, OWLOntology> ontologyCache = new HashMap<>();

    /**
     * Load ontology from GridFS with caching
     * Pass forceReload=true to bypass cache and get fresh data
     */
    private OWLOntology loadOntology(String projectId) throws Exception {
        return loadOntology(projectId, false);
    }

    private OWLOntology loadOntology(String projectId, boolean forceReload) throws Exception {
        if (!forceReload && ontologyCache.containsKey(projectId)) {
            log.debug("Using cached ontology for project: {}", projectId);
            return ontologyCache.get(projectId);
        }

        GridFSFile file = gridfs.findOne(new Query(Criteria.where("metadata.projectId").is(projectId)));
        if (file == null) {
            throw new RuntimeException("Ontology file not found for project: " + projectId);
        }

        GridFsResource resource = gridfs.getResource(file);
        try (InputStream inputStream = resource.getInputStream()) {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);
            ontologyCache.put(projectId, ontology);
            return ontology;
        }
    }

    /**
     * Generate graph data directly from GraphDB (live data)
     * This fetches the current state from GraphDB, not from GridFS snapshot
     * Includes all OWL relationships: subClassOf, domain, range, equivalentClass, etc.
     */
    private Map<String, Object> generateGraphFromGraphDB(String projectId, int maxNodes) {
        log.info("📊 Generating graph from GraphDB for project: {}", projectId);
        
        // Fetch all OWL entities (classes, properties, individuals)
        String sparql = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            
            SELECT DISTINCT ?entity ?type ?label WHERE {
                ?entity a ?type .
                OPTIONAL { ?entity rdfs:label ?label }
                FILTER(?type IN (owl:Class, owl:ObjectProperty, owl:DatatypeProperty, owl:NamedIndividual, owl:AnnotationProperty))
            }
            LIMIT """ + maxNodes;

        List<GraphGeneratingService.Node> nodes = new ArrayList<>();
        List<GraphGeneratingService.Edge> edges = new ArrayList<>();

        try {
            var result = graphDBDatasetService.execSelect(projectId, sparql);

            while (result.hasNext()) {
                var binding = result.next();
                String entityIri = binding.getValue("entity").stringValue();
                String type = binding.getValue("type").stringValue();
                String label = binding.hasBinding("label") ?
                    binding.getValue("label").stringValue() : getLocalName(entityIri);

                String nodeType = type.contains("AnnotationProperty") ? "annotationProperty" :
                                type.contains("Class") ? "class" :
                                type.contains("ObjectProperty") ? "objectProperty" :
                                type.contains("DatatypeProperty") ? "dataProperty" :
                                "individual";
                
                GraphGeneratingService.Node node = new GraphGeneratingService.Node(
                    getNodeId(entityIri), label, nodeType, entityIri
                );
                nodes.add(node);
            }
            
            log.info("✅ Fetched {} nodes from GraphDB", nodes.size());
            
            // Fetch ALL types of edges: subClassOf, domain, range, equivalentClass, instanceOf, subPropertyOf
            String edgeSparql = """
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                
                SELECT DISTINCT ?from ?to ?edgeType ?property WHERE {
                    {
                        # SubClass relationships
                        ?from rdfs:subClassOf ?to .
                        BIND("subClassOf" AS ?edgeType)
                        FILTER(!isBlank(?to))
                    } UNION {
                        # Equivalent Class
                        ?from owl:equivalentClass ?to .
                        BIND("equivalentClass" AS ?edgeType)
                    } UNION {
                        # Instance relationships
                        ?from rdf:type ?to .
                        ?to a owl:Class .
                        BIND("instanceOf" AS ?edgeType)
                    } UNION {
                        # Property Domain (property -> domain class)
                        ?from rdfs:domain ?to .
                        ?from a ?propType .
                        FILTER(?propType IN (owl:ObjectProperty, owl:DatatypeProperty))
                        BIND("domain" AS ?edgeType)
                    } UNION {
                        # Property Range (property -> range class)
                        ?from rdfs:range ?to .
                        ?from a ?propType .
                        FILTER(?propType IN (owl:ObjectProperty, owl:DatatypeProperty))
                        BIND("range" AS ?edgeType)
                    } UNION {
                        # SubProperty relationships
                        ?from rdfs:subPropertyOf ?to .
                        BIND("subPropertyOf" AS ?edgeType)
                    } UNION {
                        # Inverse properties
                        ?from owl:inverseOf ?to .
                        BIND("inverseOf" AS ?edgeType)
                    } UNION {
                        # Disjoint classes
                        ?from owl:disjointWith ?to .
                        BIND("disjointWith" AS ?edgeType)
                    }
                    FILTER(!isBlank(?from) && !isBlank(?to))
                }
                """;
            
            result = graphDBDatasetService.execSelect(projectId, edgeSparql);
            
            while (result.hasNext()) {
                var binding = result.next();
                String fromIri = binding.getValue("from").stringValue();
                String toIri = binding.getValue("to").stringValue();
                String edgeType = binding.getValue("edgeType").stringValue();
                
                String from = getNodeId(fromIri);
                String to = getNodeId(toIri);
                
                GraphGeneratingService.Edge edge = new GraphGeneratingService.Edge(
                    from, to, edgeType, edgeType
                );
                edges.add(edge);
            }
            
            log.info("✅ Fetched {} edges from GraphDB (subClassOf, domain, range, instanceOf, etc.)", edges.size());
            
        } catch (Exception e) {
            log.error("❌ Error fetching graph from GraphDB", e);
            throw new RuntimeException("Failed to generate graph from GraphDB: " + e.getMessage(), e);
        }
        
        return Map.of(
            "success", true,
            "projectId", projectId,
            "nodes", nodes,
            "edges", edges,
            "source", "graphdb",
            "timestamp", System.currentTimeMillis()
        );
    }
    
    private String getNodeId(String iri) {
        return iri.replaceAll("[^a-zA-Z0-9]", "_");
    }
    
    private String getLocalName(String iri) {
        int hashIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < iri.length() - 1
            ? iri.substring(splitIndex + 1)
            : iri;
    }

    /**
     * Get initial graph structure for a project.
     * Returns root-level nodes only for large ontologies.
     * Now fetches from GraphDB (live data) instead of GridFS (snapshot)
     *
     * GET /api/collab-graph/{projectId}/initial
     */
    @GetMapping("/{projectId}/initial")
    public ResponseEntity<Map<String, Object>> getInitialGraph(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "100") int maxNodes,
            @RequestParam(defaultValue = "false") boolean forceReload
    ) {
        try {
            log.info("Fetching initial graph for project: {} (max {} nodes, forceReload={}, source=GraphDB)", 
                projectId, maxNodes, forceReload);

            // Clear cache if forceReload is true
            if (forceReload) {
                ontologyCache.remove(projectId);
                graphGeneratingService.clearGraphCache();
                log.info("Cleared caches for project: {}", projectId);
            }

            // Fetch directly from GraphDB (live data)
            return ResponseEntity.ok(generateGraphFromGraphDB(projectId, maxNodes));

        } catch (Exception e) {
            log.error("Error fetching initial graph for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Expand a node to load its children (lazy loading).
     *
     * GET /api/collab-graph/{projectId}/expand/{nodeId}
     */
    @GetMapping("/{projectId}/expand/{nodeId}")
    public ResponseEntity<Map<String, Object>> expandNode(
            @PathVariable String projectId,
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "false") boolean forceReload
    ) {
        try {
            log.info("Expanding node: {} in project: {} (forceReload={})", nodeId, projectId, forceReload);

            if (forceReload) {
                ontologyCache.remove(projectId);
            }

            OWLOntology ontology = loadOntology(projectId, forceReload);
            if (ontology == null) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", "Project not found"
                ));
            }

            GraphGeneratingService.Graph graph = graphGeneratingService.generateGraph(ontology, false);
            List<GraphGeneratingService.Node> allNodes = graph.getNodes();
            List<GraphGeneratingService.Edge> allEdges = graph.getEdges();

            // Find connected nodes
            Set<String> expandedNodeIds = new HashSet<>();
            expandedNodeIds.add(nodeId);
            for (GraphGeneratingService.Edge edge : allEdges) {
                if (edge.getSource().equals(nodeId)) {
                    expandedNodeIds.add(edge.getTarget());
                }
                if (edge.getTarget().equals(nodeId)) {
                    expandedNodeIds.add(edge.getSource());
                }
            }

            List<GraphGeneratingService.Node> nodes = allNodes.stream()
                .filter(n -> expandedNodeIds.contains(n.getId()))
                .collect(Collectors.toList());
            List<GraphGeneratingService.Edge> edges = allEdges.stream()
                .filter(e -> expandedNodeIds.contains(e.getSource()) && expandedNodeIds.contains(e.getTarget()))
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "projectId", projectId,
                "nodeId", nodeId,
                "nodes", nodes,
                "edges", edges,
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("Error expanding node: {} in project: {}", nodeId, projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Search for nodes by label or IRI.
     *
     * GET /api/collab-graph/{projectId}/search
     */
    @GetMapping("/{projectId}/search")
    public ResponseEntity<Map<String, Object>> searchNodes(
            @PathVariable String projectId,
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit
    ) {
        try {
            log.info("Searching nodes in project: {} with query: {}", projectId, query);

            OWLOntology ontology = loadOntology(projectId);
            if (ontology == null) {
                return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", "Project not found"
                ));
            }

            GraphGeneratingService.Graph graph = graphGeneratingService.generateGraph(ontology, false);
            List<GraphGeneratingService.Node> allNodes = graph.getNodes();
            String lowerQuery = query.toLowerCase();

            List<GraphGeneratingService.Node> results = allNodes.stream()
                .filter(n -> n.getLabel().toLowerCase().contains(lowerQuery) || 
                            n.getId().toLowerCase().contains(lowerQuery))
                .limit(limit)
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "projectId", projectId,
                "query", query,
                "results", results,
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("Error searching nodes in project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get graph delta updates since a timestamp.
     *
     * GET /api/collab-graph/{projectId}/delta
     */
    @GetMapping("/{projectId}/delta")
    public ResponseEntity<Map<String, Object>> getGraphDelta(
            @PathVariable String projectId,
            @RequestParam long since
    ) {
        try {
            log.debug("Fetching graph delta for project: {} since: {}", projectId, since);

            // Return empty delta for now - full change tracking would require modification tracking
            Map<String, Object> delta = Map.of(
                "addedNodes", List.of(),
                "removedNodes", List.of(),
                "addedEdges", List.of(),
                "removedEdges", List.of()
            );

            return ResponseEntity.ok(Map.of(
                "success", true,
                "projectId", projectId,
                "since", since,
                "delta", delta,
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("Error fetching graph delta for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    // ==================== WebSocket Message Handlers ====================

    /**
     * Handle node selection from graph view clients.
     * Broadcasts to other viewers showing which node the user selected.
     *
     * STOMP: /app/graph/{projectId}/select
     * Subscribe to: /topic/graph/{projectId}
     */
    @MessageMapping("/graph/{projectId}/select")
    public void handleNodeSelection(
            @DestinationVariable String projectId,
            @Payload Map<String, Object> payload
    ) {
        try {
            String userId = (String) payload.get("userId");
            String username = (String) payload.get("username");
            String nodeId = (String) payload.get("nodeId");
            String userColor = (String) payload.get("userColor");

            GraphUpdateMessage message = GraphUpdateMessage.nodeSelected(
                projectId, userId, username, nodeId, userColor
            );

            collaborativeEditService.broadcastGraphUpdate(message);

            log.debug("Node selected: {} by user: {} in project: {}", nodeId, username, projectId);

        } catch (Exception e) {
            log.error("Error handling node selection", e);
        }
    }

    /**
     * Handle cursor movement in graph view.
     * Broadcasts cursor position to other viewers (debounced on client side).
     *
     * STOMP: /app/graph/{projectId}/cursor
     * Subscribe to: /topic/graph/{projectId}
     */
    @MessageMapping("/graph/{projectId}/cursor")
    public void handleCursorMove(
            @DestinationVariable String projectId,
            @Payload Map<String, Object> payload
    ) {
        try {
            String userId = (String) payload.get("userId");
            String username = (String) payload.get("username");
            String userColor = (String) payload.get("userColor");

            @SuppressWarnings("unchecked")
            Map<String, Object> cursorData = (Map<String, Object>) payload.get("cursor");

            GraphUpdateMessage.CursorPosition cursor = GraphUpdateMessage.CursorPosition.builder()
                .x(((Number) cursorData.get("x")).doubleValue())
                .y(((Number) cursorData.get("y")).doubleValue())
                .nodeId((String) cursorData.get("nodeId"))
                .build();

            GraphUpdateMessage message = GraphUpdateMessage.cursorMoved(
                projectId, userId, username, cursor, userColor
            );

            collaborativeEditService.broadcastGraphUpdate(message);

        } catch (Exception e) {
            log.error("Error handling cursor movement", e);
        }
    }

    /**
     * Handle node expansion request (lazy loading).
     * Fetches child nodes and broadcasts to all viewers.
     *
     * STOMP: /app/graph/{projectId}/expand
     * Subscribe to: /topic/graph/{projectId}
     */
    @MessageMapping("/graph/{projectId}/expand")
    public void handleNodeExpansion(
            @DestinationVariable String projectId,
            @Payload Map<String, Object> payload
    ) {
        try {
            String userId = (String) payload.get("userId");
            String username = (String) payload.get("username");
            String nodeId = (String) payload.get("nodeId");

            // Fetch child nodes
            OWLOntology ontology = loadOntology(projectId);
            if (ontology == null) {
                log.error("Project not found: {}", projectId);
                return;
            }

            GraphGeneratingService.Graph graph = graphGeneratingService.generateGraph(ontology, false);
            List<GraphGeneratingService.Node> allNodes = graph.getNodes();
            List<GraphGeneratingService.Edge> allEdges = graph.getEdges();

            // Find connected nodes
            Set<String> expandedNodeIds = new HashSet<>();
            expandedNodeIds.add(nodeId);
            for (GraphGeneratingService.Edge edge : allEdges) {
                if (edge.getSource().equals(nodeId)) {
                    expandedNodeIds.add(edge.getTarget());
                }
                if (edge.getTarget().equals(nodeId)) {
                    expandedNodeIds.add(edge.getSource());
                }
            }

            List<GraphGeneratingService.Node> nodes = allNodes.stream()
                .filter(n -> expandedNodeIds.contains(n.getId()))
                .collect(Collectors.toList());
            List<GraphGeneratingService.Edge> edges = allEdges.stream()
                .filter(e -> expandedNodeIds.contains(e.getSource()) && expandedNodeIds.contains(e.getTarget()))
                .collect(Collectors.toList());

            // Convert to GraphUpdateMessage format
            List<GraphUpdateMessage.GraphNode> childNodes = nodes.stream()
                .map(node -> GraphUpdateMessage.GraphNode.builder()
                    .id(node.getId())
                    .label(node.getLabel())
                    .type(node.getType())
                    .hasChildren(false) // Can be enhanced later
                    .expanded(false)
                    .build())
                .toList();

            List<GraphUpdateMessage.GraphEdge> childEdges = edges.stream()
                .map(edge -> GraphUpdateMessage.GraphEdge.builder()
                    .id(edge.getSource() + "_" + edge.getTarget())
                    .from(edge.getSource())
                    .to(edge.getTarget())
                    .label(edge.getLabel())
                    .type(edge.getType())
                    .build())
                .toList();

            GraphUpdateMessage message = GraphUpdateMessage.nodeExpanded(
                projectId, userId, username, nodeId, childNodes, childEdges
            );

            collaborativeEditService.broadcastGraphUpdate(message);

            log.info("Node expanded: {} with {} children by user: {} in project: {}",
                    nodeId, childNodes.size(), username, projectId);

        } catch (Exception e) {
            log.error("Error handling node expansion", e);
        }
    }

    /**
     * Clear all caches for a specific project.
     * Use this after ontology modifications to ensure fresh data is loaded.
     *
     * POST /api/collab-graph/{projectId}/clear-cache
     */
    @PostMapping("/{projectId}/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache(@PathVariable String projectId) {
        try {
            log.info("Clearing all caches for project: {}", projectId);
            
            // Clear ontology cache
            ontologyCache.remove(projectId);
            
            // Clear graph cache
            graphGeneratingService.clearGraphCache();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "All caches cleared for project " + projectId,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Error clearing cache for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get active users viewing the graph.
     *
     * GET /api/collab-graph/{projectId}/active-users
     */
    @GetMapping("/{projectId}/active-users")
    public ResponseEntity<Map<String, Object>> getActiveUsers(@PathVariable String projectId) {
        try {
            List<Map<String, Object>> activeUsers = collaborativeEditService.getActiveUsers(projectId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "projectId", projectId,
                "users", activeUsers,
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("Error fetching active users for project: {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
}
