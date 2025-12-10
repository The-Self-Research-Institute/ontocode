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
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerService.ReasonerType;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for reasoning operations on ontologies.
 * Provides endpoints for consistency checking, classification, realization, and inference.
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class ReasonerController {

    private static final Logger log = LoggerFactory.getLogger(ReasonerController.class);

    @Autowired
    private GridFsTemplate gridfs;

    @Autowired
    private ReasonerService reasonerService;

    // Cache for loaded ontologies (in production, use proper caching)
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
     * Check ontology consistency
     * POST /api/ontology/{projectId}/reasoner/consistency
     */
    @PostMapping("/{projectId}/reasoner/consistency")
    public ResponseEntity<Map<String, Object>> checkConsistency(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Checking consistency for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            boolean isConsistent = reasonerService.isConsistent(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> result = new HashMap<>();
            result.put("consistent", isConsistent);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("projectId", projectId);
            
            // If inconsistent, get unsatisfiable classes
            if (!isConsistent) {
                Set<OWLClass> unsatisfiable = reasonerService.getUnsatisfiableClasses(ontology, type);
                List<Map<String, String>> unsatisfiableList = unsatisfiable.stream()
                    .map(cls -> Map.of(
                        "iri", cls.getIRI().toString(),
                        "label", getLabel(cls, ontology)
                    ))
                    .collect(Collectors.toList());
                result.put("unsatisfiableClasses", unsatisfiableList);
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error checking consistency", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Classify the ontology (compute class hierarchy)
     * POST /api/ontology/{projectId}/reasoner/classify
     */
    @PostMapping("/{projectId}/reasoner/classify")
    public ResponseEntity<Map<String, Object>> classify(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Classifying ontology for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            reasonerService.classify(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Classification completed successfully");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during classification", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Realize the ontology (compute instances)
     * POST /api/ontology/{projectId}/reasoner/realize
     */
    @PostMapping("/{projectId}/reasoner/realize")
    public ResponseEntity<Map<String, Object>> realize(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Realizing ontology for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            reasonerService.realize(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Realization completed successfully");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during realization", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred axioms
     * GET /api/ontology/{projectId}/reasoner/inferred-axioms
     */
    @GetMapping("/{projectId}/reasoner/inferred-axioms")
    public ResponseEntity<Map<String, Object>> getInferredAxioms(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Getting inferred axioms for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            long startTime = System.currentTimeMillis();
            Set<OWLAxiom> inferredAxioms = reasonerService.getInferredAxioms(ontology, type);
            long duration = System.currentTimeMillis() - startTime;
            
            // Convert axioms to readable format
            List<Map<String, String>> axiomsList = inferredAxioms.stream()
                .limit(100) // Limit to first 100 for performance
                .map(axiom -> Map.of(
                    "axiomType", axiom.getAxiomType().getName(),
                    "axiom", axiom.toString(),
                    "readable", formatAxiom(axiom, ontology)
                ))
                .collect(Collectors.toList());
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", type.getDisplayName());
            result.put("durationMs", duration);
            result.put("totalInferredAxioms", inferredAxioms.size());
            result.put("axioms", axiomsList);
            result.put("message", axiomsList.size() < inferredAxioms.size() 
                ? "Showing first 100 of " + inferredAxioms.size() + " inferred axioms"
                : "Showing all " + inferredAxioms.size() + " inferred axioms");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error getting inferred axioms", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred superclasses for a class
     * GET /api/ontology/{projectId}/reasoner/inferred-superclasses
     */
    @GetMapping("/{projectId}/reasoner/inferred-superclasses")
    public ResponseEntity<Map<String, Object>> getInferredSuperClasses(
            @PathVariable String projectId,
            @RequestParam String classIri,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            
            Set<OWLClass> superClasses = reasonerService.getInferredSuperClasses(ontology, owlClass, type);
            
            List<Map<String, String>> superClassesList = superClasses.stream()
                .map(cls -> Map.of(
                    "iri", cls.getIRI().toString(),
                    "label", getLabel(cls, ontology)
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "classIri", classIri,
                "reasonerType", type.getDisplayName(),
                "inferredSuperClasses", superClassesList
            ));
            
        } catch (Exception e) {
            log.error("Error getting inferred superclasses", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred subclasses for a class
     * GET /api/ontology/{projectId}/reasoner/inferred-subclasses
     */
    @GetMapping("/{projectId}/reasoner/inferred-subclasses")
    public ResponseEntity<Map<String, Object>> getInferredSubClasses(
            @PathVariable String projectId,
            @RequestParam String classIri,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            
            Set<OWLClass> subClasses = reasonerService.getInferredSubClasses(ontology, owlClass, type);
            
            List<Map<String, String>> subClassesList = subClasses.stream()
                .map(cls -> Map.of(
                    "iri", cls.getIRI().toString(),
                    "label", getLabel(cls, ontology)
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "classIri", classIri,
                "reasonerType", type.getDisplayName(),
                "inferredSubClasses", subClassesList
            ));
            
        } catch (Exception e) {
            log.error("Error getting inferred subclasses", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred instances for a class
     * GET /api/ontology/{projectId}/reasoner/inferred-instances
     */
    @GetMapping("/{projectId}/reasoner/inferred-instances")
    public ResponseEntity<Map<String, Object>> getInferredInstances(
            @PathVariable String projectId,
            @RequestParam String classIri,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            
            Set<OWLNamedIndividual> instances = reasonerService.getInferredInstances(ontology, owlClass, type);
            
            List<Map<String, String>> instancesList = instances.stream()
                .map(ind -> Map.of(
                    "iri", ind.getIRI().toString(),
                    "label", getLabel(ind, ontology)
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "classIri", classIri,
                "reasonerType", type.getDisplayName(),
                "inferredInstances", instancesList
            ));
            
        } catch (Exception e) {
            log.error("Error getting inferred instances", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get reasoner statistics
     * GET /api/ontology/{projectId}/reasoner/stats
     */
    @GetMapping("/{projectId}/reasoner/stats")
    public ResponseEntity<Map<String, Object>> getReasonerStats(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            Map<String, Object> stats = reasonerService.getReasonerStats(ontology, type);
            stats.put("success", true);
            stats.put("projectId", projectId);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Run full reasoning (consistency + classify + realize)
     * POST /api/ontology/{projectId}/reasoner/run
     */
    @PostMapping("/{projectId}/reasoner/run")
    public ResponseEntity<Map<String, Object>> runReasoner(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Running full reasoning for project: {} with {}", projectId, reasonerType);
            
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            Map<String, Object> result = new HashMap<>();
            long totalStartTime = System.currentTimeMillis();
            
            // Step 1: Consistency check
            long startTime = System.currentTimeMillis();
            boolean isConsistent = reasonerService.isConsistent(ontology, type);
            result.put("consistencyCheckMs", System.currentTimeMillis() - startTime);
            result.put("consistent", isConsistent);
            
            if (!isConsistent) {
                Set<OWLClass> unsatisfiable = reasonerService.getUnsatisfiableClasses(ontology, type);
                result.put("unsatisfiableClassCount", unsatisfiable.size());
                result.put("message", "Ontology is inconsistent. Found " + unsatisfiable.size() + " unsatisfiable classes.");
                result.put("success", false);
                return ResponseEntity.ok(result);
            }
            
            // Step 2: Classification
            startTime = System.currentTimeMillis();
            reasonerService.classify(ontology, type);
            result.put("classificationMs", System.currentTimeMillis() - startTime);
            
            // Step 3: Realization
            startTime = System.currentTimeMillis();
            reasonerService.realize(ontology, type);
            result.put("realizationMs", System.currentTimeMillis() - startTime);
            
            // Get inferred axioms count
            Set<OWLAxiom> inferredAxioms = reasonerService.getInferredAxioms(ontology, type);
            result.put("inferredAxiomsCount", inferredAxioms.size());
            
            result.put("totalDurationMs", System.currentTimeMillis() - totalStartTime);
            result.put("reasonerType", type.getDisplayName());
            result.put("success", true);
            result.put("message", "Reasoning completed successfully");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error running reasoner", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Clear reasoner cache
     * POST /api/ontology/reasoner/clear-cache
     */
    @PostMapping("/reasoner/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        try {
            reasonerService.clearCache();
            ontologyCache.clear();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Cache cleared successfully"
            ));
            
        } catch (Exception e) {
            log.error("Error clearing cache", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    // Helper methods

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(entity.getIRI()).stream()
            .filter(a -> a.getProperty().isLabel())
            .findFirst()
            .map(a -> a.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(""))
            .orElse(getLocalName(entity.getIRI().toString()));
    }

    private String getLocalName(String iri) {
        int hashIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < iri.length() - 1
            ? iri.substring(splitIndex + 1)
            : iri;
    }

    private String formatAxiom(OWLAxiom axiom, OWLOntology ontology) {
        // Convert axiom to a more readable format
        String axiomString = axiom.toString();
        
        // Replace IRIs with labels where possible
        for (OWLEntity entity : axiom.getSignature()) {
            String label = getLabel(entity, ontology);
            if (!label.isEmpty()) {
                axiomString = axiomString.replace(entity.getIRI().toString(), label);
            }
        }
        
        return axiomString;
    }
}