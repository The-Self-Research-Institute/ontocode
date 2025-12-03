package self.research.ontology.owlEditor.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.SwrlService;
import self.research.ontology.owlEditor.service.SwrlService.ValidationResult;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for SWRL (Semantic Web Rule Language) operations.
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class SwrlController {

    private static final Logger log = LoggerFactory.getLogger(SwrlController.class);

    @Autowired
    private GridFsTemplate gridfs;

    @Autowired
    private SwrlService swrlService;

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
     * Get all SWRL rules
     * GET /api/ontology/{projectId}/swrl/rules
     */
    @GetMapping("/{projectId}/swrl/rules")
    public ResponseEntity<Map<String, Object>> getAllRules(@PathVariable String projectId) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            Set<SWRLRule> rules = swrlService.getAllRules(ontology);
            
            List<Map<String, Object>> rulesList = rules.stream()
                .map(rule -> {
                    Map<String, Object> ruleMap = new HashMap<>();
                    ruleMap.put("rule", swrlService.formatRule(rule));
                    ruleMap.put("bodyAtomCount", rule.getBody().size());
                    ruleMap.put("headAtomCount", rule.getHead().size());
                    ruleMap.put("variables", swrlService.getVariables(rule).stream()
                        .map(var -> var.getIRI().getShortForm())
                        .collect(Collectors.toList()));
                    return ruleMap;
                })
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "ruleCount", rules.size(),
                "rules", rulesList
            ));
            
        } catch (Exception e) {
            log.error("Error getting SWRL rules", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Add a SWRL rule
     * POST /api/ontology/{projectId}/swrl/rules
     * Body: { "ruleString": "Person(?p) ^ hasAge(?p, ?age) -> Adult(?p)" }
     */
    @PostMapping("/{projectId}/swrl/rules")
    public ResponseEntity<Map<String, Object>> addRule(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String ruleString = request.get("ruleString");
            if (ruleString == null || ruleString.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Rule string is required"
                ));
            }
            
            OWLOntology ontology = loadOntology(projectId);
            SWRLRule rule = swrlService.parseRule(ontology, ruleString);
            
            // Validate rule
            ValidationResult validation = swrlService.validateRule(ontology, rule);
            if (!validation.isValid()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Invalid SWRL rule",
                    "errors", validation.getErrors(),
                    "warnings", validation.getWarnings()
                ));
            }
            
            // Add rule
            swrlService.addRule(ontology, rule);
            
            // Save ontology (in production, handle this properly)
            // For now, just return success
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "SWRL rule added successfully",
                "rule", swrlService.formatRule(rule),
                "warnings", validation.getWarnings()
            ));
            
        } catch (Exception e) {
            log.error("Error adding SWRL rule", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Validate a SWRL rule without adding it
     * POST /api/ontology/{projectId}/swrl/validate
     */
    @PostMapping("/{projectId}/swrl/validate")
    public ResponseEntity<Map<String, Object>> validateRule(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String ruleString = request.get("ruleString");
            if (ruleString == null || ruleString.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Rule string is required"
                ));
            }
            
            OWLOntology ontology = loadOntology(projectId);
            SWRLRule rule = swrlService.parseRule(ontology, ruleString);
            ValidationResult validation = swrlService.validateRule(ontology, rule);
            
            return ResponseEntity.ok(Map.of(
                "valid", validation.isValid(),
                "rule", swrlService.formatRule(rule),
                "errors", validation.getErrors(),
                "warnings", validation.getWarnings()
            ));
            
        } catch (Exception e) {
            log.error("Error validating SWRL rule", e);
            return ResponseEntity.status(500).body(Map.of(
                "valid", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get SWRL statistics
     * GET /api/ontology/{projectId}/swrl/stats
     */
    @GetMapping("/{projectId}/swrl/stats")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable String projectId) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            Map<String, Object> stats = swrlService.getRuleStatistics(ontology);
            stats.put("success", true);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting SWRL stats", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
}