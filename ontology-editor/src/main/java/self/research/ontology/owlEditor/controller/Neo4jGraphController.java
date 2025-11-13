// src/main/java/.../controller/Neo4jGraphController.java
package self.research.ontology.owlEditor.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.neo4j.OntologyClassNode;
import self.research.ontology.owlEditor.repository.neo4j.OntologyClassRepository;
import self.research.ontology.owlEditor.repository.neo4j.PropertyRepository;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
@CrossOrigin(origins = "*")
public class Neo4jGraphController {

    @Autowired
    private OntologyClassRepository classRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    /**
     * Fast hierarchy navigation using Neo4j
     */
    @GetMapping("/{projectId}/hierarchy/roots")
    public ResponseEntity<List<OntologyClassNode>> getRootClasses(@PathVariable String projectId) {
        return ResponseEntity.ok(classRepository.findRootClasses(projectId));
    }

    @GetMapping("/{projectId}/hierarchy/children")
    public ResponseEntity<List<OntologyClassNode>> getChildren(
            @PathVariable String projectId,
            @RequestParam String parentIri) {
        return ResponseEntity.ok(classRepository.findDirectSubClasses(parentIri, projectId));
    }

    @GetMapping("/{projectId}/hierarchy/ancestors")
    public ResponseEntity<List<OntologyClassNode>> getAncestors(
            @PathVariable String projectId,
            @RequestParam String classIri) {
        return ResponseEntity.ok(classRepository.findAllAncestors(classIri, projectId));
    }

    @GetMapping("/{projectId}/hierarchy/descendants")
    public ResponseEntity<List<OntologyClassNode>> getDescendants(
            @PathVariable String projectId,
            @RequestParam String classIri) {
        return ResponseEntity.ok(classRepository.findAllDescendants(classIri, projectId));
    }

    /**
     * Fast search using Neo4j
     */
    @GetMapping("/{projectId}/search")
    public ResponseEntity<List<OntologyClassNode>> searchClasses(
            @PathVariable String projectId,
            @RequestParam String query) {
        return ResponseEntity.ok(classRepository.searchByLabel(query, projectId));
    }

    /**
     * Graph analytics
     */
    @GetMapping("/{projectId}/analytics/popular-classes")
    public ResponseEntity<List<OntologyClassRepository.ClassStatistics>> getMostPopularClasses(
            @PathVariable String projectId) {
        return ResponseEntity.ok(classRepository.findMostPopularClasses(projectId));
    }

    /**
     * Full graph for visualization
     */
    @GetMapping("/{projectId}/full-graph")
    public ResponseEntity<Map<String, Object>> getFullGraph(@PathVariable String projectId) {
        List<OntologyClassNode> classes = classRepository.findAllWithParents(projectId);
        
        return ResponseEntity.ok(Map.of(
            "nodes", classes,
            "nodeCount", classes.size()
        ));
    }
}