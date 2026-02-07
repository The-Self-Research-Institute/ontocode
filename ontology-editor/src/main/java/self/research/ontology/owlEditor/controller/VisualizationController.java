package self.research.ontology.owlEditor.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.GraphGeneratingService;
import self.research.ontology.owlEditor.service.GraphGeneratingService.Graph;
import self.research.ontology.owlEditor.service.GraphDBDatasetService;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for ontology visualization operations.
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class VisualizationController {

    private static final Logger log = LoggerFactory.getLogger(VisualizationController.class);

    @Autowired
    private GridFsTemplate gridfs;

    @Autowired
    private GraphGeneratingService graphService;

    @Autowired
    private GraphDBDatasetService graphDBService;

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
     * GET /api/ontology/{projectId}/graph (alias for plugin compatibility)
     */
    @GetMapping({"/{projectId}/visualization/graph", "/{projectId}/graph"})
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
     * Get root classes for hierarchical navigation
     * GET /api/ontology/{projectId}/hierarchy/roots
     * NOW: Uses GraphDB SPARQL queries instead of loading from GridFS
     * This works for ALL file formats (OWL/XML, RDF/XML, Turtle, etc.)
     */
    @GetMapping("/{projectId}/hierarchy/roots")
    public ResponseEntity<Map<String, Object>> getRootClasses(@PathVariable String projectId) {
        try {
            log.info("Getting root classes for project: {} from GraphDB", projectId);
            
            // Query GraphDB SPARQL instead of loading from GridFS
            // This ensures we get the actual imported triples, not parsing issues from the original file
            List<Map<String, Object>> rootClasses = graphDBService.getRootClassesFromGraphDB(projectId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("roots", rootClasses);
            response.put("count", rootClasses.size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error getting root classes from GraphDB for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get children of a specific class
     * GET /api/ontology/{projectId}/hierarchy/children
     * NOW: Uses GraphDB SPARQL queries instead of loading from GridFS
     */
    @GetMapping("/{projectId}/hierarchy/children")
    public ResponseEntity<Map<String, Object>> getClassChildren(
            @PathVariable String projectId,
            @RequestParam String classIRI
    ) {
        try {
            log.info("Getting children for class: {} from GraphDB", classIRI);
            
            // Query GraphDB SPARQL instead of loading from GridFS
            List<Map<String, Object>> children = graphDBService.getChildClassesFromGraphDB(projectId, classIRI);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("children", children);
            response.put("count", children.size());
            response.put("parentId", classIRI);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error getting class children from GraphDB for parent {}", classIRI, e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get parents of a specific class
     * GET /api/ontology/{projectId}/hierarchy/parents
     */
    @GetMapping("/{projectId}/hierarchy/parents")
    public ResponseEntity<Map<String, Object>> getClassParents(
            @PathVariable String projectId,
            @RequestParam String classIRI
    ) {
        try {
            log.info("Getting parents for class: {}", classIRI);
            
            OWLOntology ontology = loadOntology(projectId);
            OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass childClass = factory.getOWLClass(IRI.create(classIRI));
            OWLClass owlThing = factory.getOWLThing();
            
            Set<Map<String, Object>> parents = new HashSet<>();
            
            // Get direct superclasses
            for (OWLClassExpression superClass : EntitySearcher.getSuperClasses(childClass, ontology).collect(Collectors.toSet())) {
                if (superClass instanceof OWLClass) {
                    OWLClass parentClass = (OWLClass) superClass;
                    if (!parentClass.isOWLThing() && !parentClass.equals(childClass)) {
                        Map<String, Object> classInfo = new HashMap<>();
                        classInfo.put("id", parentClass.getIRI().toString());
                        classInfo.put("label", getClassLabel(parentClass, ontology));
                        classInfo.put("type", "CLASS");
                        classInfo.put("hasChildren", EntitySearcher.getSubClasses(parentClass, ontology).findAny().isPresent());
                        classInfo.put("childId", classIRI);
                        parents.add(classInfo);
                    }
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("parents", parents);
            response.put("count", parents.size());
            response.put("childId", classIRI);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error getting class parents", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Helper method to get class label
     */
    private String getClassLabel(OWLClass cls, OWLOntology ontology) {
        // Try to get rdfs:label annotation
        for (OWLAnnotation annotation : EntitySearcher.getAnnotations(cls, ontology).collect(Collectors.toSet())) {
            if (annotation.getProperty().isLabel()) {
                OWLAnnotationValue value = annotation.getValue();
                if (value instanceof OWLLiteral) {
                    return ((OWLLiteral) value).getLiteral();
                }
            }
        }
        
        // Fallback to fragment or full IRI
        String iri = cls.getIRI().toString();
        if (iri.contains("#")) {
            return iri.substring(iri.lastIndexOf("#") + 1);
        } else if (iri.contains("/")) {
            return iri.substring(iri.lastIndexOf("/") + 1);
        }
        return iri;
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