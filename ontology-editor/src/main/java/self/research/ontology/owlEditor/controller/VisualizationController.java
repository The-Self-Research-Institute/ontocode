package self.research.ontology.owlEditor.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.GraphGenerationService;
import self.research.ontology.owlEditor.service.GraphGenerationService.Graph;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for ontology visualization operations.
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(origins = "*")
public class VisualizationController {

    private static final Logger log = LoggerFactory.getLogger(VisualizationController.class);

    @Autowired
    private GridFsTemplate gridfs;

    @Autowired
    private GraphGenerationService graphService;

    private final Map<String, OWLOntology> ontologyCache = new HashMap<>();

    /**
     * Load ontology from GridFS
     */
    private OWLOntology loadOntology(String projectId) throws Exception {
        if (ontologyCache.containsKey(projectId)) {
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
     * Generate complete ontology graph
     * GET /api/ontology/{projectId}/visualization/graph
     */
    @GetMapping("/{projectId}/visualization/graph")
    public ResponseEntity<Map<String, Object>> getGraph(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean includeIndividuals
    ) {
        try {
            log.info("Generating graph for project: {}", projectId);
            
            OWLOntology ontology = loadOntology(projectId);
            Graph graph = graphService.generateGraph(ontology, includeIndividuals);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("projectId", projectId);
            response.put("nodes", graph.getNodes());
            response.put("edges", graph.getEdges());
            response.put("metadata", graph.getMetadata());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error generating graph", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Generate class hierarchy graph
     * GET /api/ontology/{projectId}/visualization/class-hierarchy
     */
    @GetMapping("/{projectId}/visualization/class-hierarchy")
    public ResponseEntity<Map<String, Object>> getClassHierarchy(
            @PathVariable String projectId,
            @RequestParam String classIRI,
            @RequestParam(defaultValue = "3") int depth
    ) {
        try {
            log.info("Generating class hierarchy for: {}", classIRI);
            
            OWLOntology ontology = loadOntology(projectId);
            OWLClass owlClass = ontology.getOWLOntologyManager()
                .getOWLDataFactory()
                .getOWLClass(IRI.create(classIRI));
            
            Graph graph = graphService.generateClassHierarchyGraph(ontology, owlClass, depth);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("rootClass", classIRI);
            response.put("nodes", graph.getNodes());
            response.put("edges", graph.getEdges());
            response.put("metadata", graph.getMetadata());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error generating class hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Generate property graph
     * GET /api/ontology/{projectId}/visualization/properties
     */
    @GetMapping("/{projectId}/visualization/properties")
    public ResponseEntity<Map<String, Object>> getPropertyGraph(@PathVariable String projectId) {
        try {
            log.info("Generating property graph for project: {}", projectId);
            
            OWLOntology ontology = loadOntology(projectId);
            Graph graph = graphService.generatePropertyGraph(ontology);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("nodes", graph.getNodes());
            response.put("edges", graph.getEdges());
            response.put("metadata", graph.getMetadata());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error generating property graph", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get visualization statistics
     * GET /api/ontology/{projectId}/visualization/stats
     */
    @GetMapping("/{projectId}/visualization/stats")
    public ResponseEntity<Map<String, Object>> getVisualizationStats(@PathVariable String projectId) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("success", true);
            stats.put("classCount", ontology.getClassesInSignature().size());
            stats.put("objectPropertyCount", ontology.getObjectPropertiesInSignature().size());
            stats.put("dataPropertyCount", ontology.getDataPropertiesInSignature().size());
            stats.put("individualCount", ontology.getIndividualsInSignature().size());
            stats.put("axiomCount", ontology.getAxiomCount());
            
            // Count relationships
            long subclassCount = ontology.getAxioms(org.semanticweb.owlapi.model.AxiomType.SUBCLASS_OF).size();
            long instanceCount = ontology.getAxioms(org.semanticweb.owlapi.model.AxiomType.CLASS_ASSERTION).size();
            
            stats.put("subclassRelationships", subclassCount);
            stats.put("instanceRelationships", instanceCount);
            stats.put("totalRelationships", subclassCount + instanceCount);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting visualization stats", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Clear ontology cache
     * POST /api/ontology/visualization/clear-cache
     */
    @PostMapping("/visualization/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        try {
            ontologyCache.clear();
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Visualization cache cleared"
            ));
        } catch (Exception e) {
            log.error("Error clearing cache", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
}