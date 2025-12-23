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
import self.research.ontology.owlEditor.service.ReasonerType;
import self.research.ontology.owlEditor.service.GraphDBDatasetService;
import org.eclipse.rdf4j.rio.RDFFormat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    @Autowired
    private GraphDBDatasetService datasetService;

    // Cache for loaded ontologies (in production, use proper caching)
    private final Map<String, OWLOntology> ontologyCache = new HashMap<>();

    /**
     * Load ontology from GridFS
     */
    private OWLOntology loadOntology(String projectId) throws Exception {
        log.info("Loading ontology for project: {}", projectId);
        
        if (ontologyCache.containsKey(projectId)) {
            log.info("Returning cached ontology for project: {}", projectId);
            return ontologyCache.get(projectId);
        }

        // Try to load from GraphDB first (most up-to-date)
        try {
            log.info("Attempting to load ontology from GraphDB for project: {}", projectId);
            String rdfData = datasetService.exportDataset(projectId, RDFFormat.RDFXML);
            if (rdfData != null && !rdfData.isBlank()) {
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                try (InputStream is = new ByteArrayInputStream(rdfData.getBytes(StandardCharsets.UTF_8))) {
                    OWLOntology ontology = manager.loadOntologyFromOntologyDocument(is);
                    log.info("Ontology loaded from GraphDB: {} axioms", ontology.getAxiomCount());
                    ontologyCache.put(projectId, ontology);
                    return ontology;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load ontology from GraphDB, falling back to GridFS: {}", e.getMessage());
        }

        // Fallback to GridFS
        GridFSFile file = gridfs.findOne(new Query(Criteria.where("metadata.projectId").is(projectId)));
        
        if (file == null) {
            log.warn("File not found with metadata.projectId={}, trying filename", projectId);
            file = gridfs.findOne(new Query(Criteria.where("filename").is(projectId + ".owl")));
        }
        
        if (file == null) {
            log.error("Ontology file not found for project: {}", projectId);
            throw new RuntimeException("Ontology file not found for project: " + projectId);
        }

        log.info("Found ontology file: {}", file.getFilename());
        GridFsResource resource = gridfs.getResource(file);
        
        try (InputStream inputStream = resource.getInputStream()) {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            log.info("Loading ontology from input stream for project: {}", projectId);
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);
            log.info("Ontology loaded successfully for project: {}", projectId);
            log.info("Axiom count: {}", ontology.getAxiomCount());
            log.info("Class count: {}", ontology.getClassesInSignature().size());
            log.info("Object property count: {}", ontology.getObjectPropertiesInSignature().size());
            log.info("Data property count: {}", ontology.getDataPropertiesInSignature().size());
            ontologyCache.put(projectId, ontology);
            return ontology;
        } catch (Exception e) {
            log.error("Error loading ontology from GridFS", e);
            throw e;
        }
    }

    @PostMapping("/{projectId}/reasoner/refresh")
    public ResponseEntity<Map<String, Object>> refreshReasoner(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            log.info("Refreshing reasoner for project: {}", projectId);
            OWLOntology oldOntology = ontologyCache.remove(projectId);
            
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            if (oldOntology != null) {
                reasonerService.disposeReasoner(oldOntology, type);
            }
            
            OWLOntology ontology = loadOntology(projectId);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Reasoner refreshed with latest data from GraphDB",
                "axiomCount", ontology.getAxiomCount()
            ));
        } catch (Exception e) {
            log.error("Error refreshing reasoner", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
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
            log.error("Error checking consistency for project: " + projectId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("errorType", e.getClass().getSimpleName());
            errorResponse.put("projectId", projectId);
            
            // Include stack trace in development
            if (log.isDebugEnabled()) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                errorResponse.put("stackTrace", sw.toString());
            }
            
            return ResponseEntity.status(500).body(errorResponse);
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
            @RequestParam(defaultValue = "HERMIT") String reasonerType,
            @RequestParam(defaultValue = "false") boolean direct
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());
            
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIri));
            
            Set<OWLClass> subClasses = reasonerService.getInferredSubClasses(ontology, owlClass, type, direct);
            
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
                "direct", direct,
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
     * Get inferred class hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-class-hierarchy
     */
    @GetMapping("/{projectId}/reasoner/inferred-class-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredClassHierarchy(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "OPENLLET") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            // Default to OPENLLET if HERMIT is requested but failing due to binary compatibility
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "OPENLLET" : reasonerType;
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            // Ensure classification is done before building hierarchy
            log.info("Ensuring classification for project {} with {}", projectId, type);
            reasonerService.classify(ontology, type);

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass thing = df.getOWLThing();

            Set<String> visited = new HashSet<>();
            Map<String, Object> root = buildClassNode(ontology, thing, type, visited);

            log.info("Inferred class hierarchy built for project: {}. Root node has {} children. Total visited: {}", 
                projectId, ((List<?>)root.get("children")).size(), visited.size());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "reasonerType", type.getDisplayName(),
                    "hierarchy", List.of(root)
            ));
        } catch (Exception e) {
            log.error("Error getting inferred class hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private Map<String, Object> buildClassNode(OWLOntology ontology, OWLClass owlClass, ReasonerType type, Set<String> visited) {
        String iri = owlClass.getIRI().toString();
        // Don't skip owl:Thing even if visited, but skip others to prevent cycles
        if (visited.contains(iri) && !owlClass.isOWLThing()) {
            log.debug("Skipping already visited class: {}", iri);
            return Map.of("id", iri, "label", getLabel(owlClass, ontology), "children", List.of(), "hasChildren", false);
        }
        visited.add(iri);

        // Get ALL inferred subclasses (direct=false) to ensure we don't miss any branches
        // but then we process them one by one to build the tree structure
        Set<OWLClass> subClasses = reasonerService.getInferredSubClasses(ontology, owlClass, type, true);
        log.debug("Class {} has {} direct inferred subclasses", iri, subClasses.size());
        
        List<Map<String, Object>> children = subClasses.stream()
                .filter(cls -> !cls.isOWLNothing() && !cls.equals(owlClass))
                .map(cls -> buildClassNode(ontology, cls, type, visited))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(m -> m.get("label").toString()))
                .collect(Collectors.toList());

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", getLabel(owlClass, ontology));
        node.put("children", children);
        node.put("hasChildren", !children.isEmpty());
        node.put("type", "Class");
        
        log.debug("Built node for {} with {} children", iri, children.size());
        return node;
    }

    /**
     * Get inferred object property hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-object-property-hierarchy
     */
    @GetMapping("/{projectId}/reasoner/inferred-object-property-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredObjectPropertyHierarchy(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "OPENLLET") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            // Default to OPENLLET if HERMIT is requested but failing due to binary compatibility
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "OPENLLET" : reasonerType;
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());

            log.info("========== Object Property Hierarchy Request ==========");
            log.info("Project ID: {}", projectId);
            log.info("Ontology loaded - Total axioms: {}", ontology.getAxiomCount());
            log.info("Object properties in signature: {}", ontology.getObjectPropertiesInSignature().size());
            ontology.getObjectPropertiesInSignature().forEach(prop -> 
                log.info("  - Found property: {}", prop.getIRI().getShortForm())
            );

            // Ensure classification is done before building property hierarchy
            log.info("Ensuring classification for project {} with {} (Object Properties)", projectId, type);
            reasonerService.classify(ontology, type);

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLObjectProperty topProperty = df.getOWLTopObjectProperty();

            Set<String> visited = new HashSet<>();
            Map<String, Object> root = buildObjectPropertyNode(ontology, topProperty, type, visited);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
            
            log.info("Inferred object property hierarchy built for project: {}. Root node has {} children. Total visited: {}", 
                projectId, children.size(), visited.size());
            
            // If no inferred properties found, fall back to asserted properties from the ontology
            if (children.isEmpty()) {
                log.warn("No inferred object properties found from reasoner. Checking ontology signature...");
                int totalProps = ontology.getObjectPropertiesInSignature().size();
                log.warn("Ontology has {} object properties in signature", totalProps);
                
                children = ontology.getObjectPropertiesInSignature().stream()
                    .filter(prop -> !prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty())
                    .map(prop -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("children", List.of());
                        node.put("hasChildren", false);
                        node.put("type", "ObjectProperty");
                        log.info("  Adding fallback property: {} ({})", getLabel(prop, ontology), prop.getIRI().toString());
                        return node;
                    })
                    .collect(Collectors.toList());
                root.put("children", children);
                log.warn("Added {} properties as fallback", children.size());
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> finalChildren = (List<Map<String, Object>>) root.get("children");
            log.info("========== Returning {} top-level object properties to frontend ==========", finalChildren.size());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "reasonerType", type.getDisplayName(),
                    "hierarchy", List.of(root)
            ));
        } catch (Exception e) {
            log.error("Error getting inferred object property hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred data property hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-data-property-hierarchy
     */
    @GetMapping("/{projectId}/reasoner/inferred-data-property-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredDataPropertyHierarchy(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "OPENLLET") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            // Default to OPENLLET if HERMIT is requested but failing due to binary compatibility
            String effectiveType = reasonerType.equalsIgnoreCase("HERMIT") ? "OPENLLET" : reasonerType;
            ReasonerType type = ReasonerType.valueOf(effectiveType.toUpperCase());
            log.info("========== Data Property Hierarchy Request ==========");
            log.info("Project ID: {}", projectId);
            log.info("Ontology loaded - Total axioms: {}", ontology.getAxiomCount());
            log.info("Data properties in signature: {}", ontology.getDataPropertiesInSignature().size());
            ontology.getDataPropertiesInSignature().forEach(prop -> 
                log.info("  - Found property: {}", prop.getIRI().getShortForm())
            );
            // Ensure classification is done before building property hierarchy
            log.info("Ensuring classification for project {} with {} (Data Properties)", projectId, type);
            reasonerService.classify(ontology, type);

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLDataProperty topProperty = df.getOWLTopDataProperty();

            Set<String> visited = new HashSet<>();
            Map<String, Object> root = buildDataPropertyNode(ontology, topProperty, type, visited);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
            
            log.info("Inferred data property hierarchy built for project: {}. Root node has {} children. Total visited: {}", 
                projectId, children.size(), visited.size());
            
            // If no inferred properties found, fall back to asserted properties from the ontology
            if (children.isEmpty()) {
                log.warn("No inferred data properties found. Falling back to asserted properties.");
                children = ontology.getDataPropertiesInSignature().stream()
                    .filter(prop -> !prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty())
                    .map(prop -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("children", List.of());
                        node.put("hasChildren", false);
                        node.put("type", "DataProperty");
                        return node;
                    })
                    .collect(Collectors.toList());
                root.put("children", children);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "reasonerType", type.getDisplayName(),
                    "hierarchy", List.of(root)
            ));
        } catch (Exception e) {
            log.error("Error getting inferred data property hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
                        

    private Map<String, Object> buildObjectPropertyNode(OWLOntology ontology, OWLObjectProperty property, ReasonerType type, Set<String> visited) {
        String iri = property.getIRI().toString();
        // Don't skip top property to allow building the tree from root
        if (visited.contains(iri) && !property.isOWLTopObjectProperty()) {
            return Map.of("id", iri, "label", getLabel(property, ontology), "children", List.of(), "hasChildren", false);
        }
        visited.add(iri);

        Set<OWLObjectPropertyExpression> subProps = reasonerService.getInferredSubObjectProperties(ontology, property, type, true);
        List<Map<String, Object>> children = subProps.stream()
                .filter(expr -> !expr.isAnonymous() && !expr.equals(property) && !expr.asOWLObjectProperty().isOWLBottomObjectProperty())
                .map(expr -> buildObjectPropertyNode(ontology, expr.asOWLObjectProperty(), type, visited))
                .sorted(Comparator.comparing(m -> m.get("label").toString()))
                .toList();

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", getLabel(property, ontology));
        node.put("children", children);
        node.put("hasChildren", !children.isEmpty());
        node.put("type", "ObjectProperty");
        return node;
    }

    private Map<String, Object> buildDataPropertyNode(OWLOntology ontology, OWLDataProperty property, ReasonerType type, Set<String> visited) {
        String iri = property.getIRI().toString();
        // Don't skip top property to allow building the tree from root
        if (visited.contains(iri) && !property.isOWLTopDataProperty()) {
            return Map.of("id", iri, "label", getLabel(property, ontology), "children", List.of(), "hasChildren", false);
        }
        visited.add(iri);

        Set<OWLDataPropertyExpression> subProps = reasonerService.getInferredSubDataProperties(ontology, property, type, true);
        List<Map<String, Object>> children = subProps.stream()
                .filter(expr -> !expr.isAnonymous() && !expr.equals(property) && !expr.asOWLDataProperty().isOWLBottomDataProperty())
                .map(expr -> buildDataPropertyNode(ontology, expr.asOWLDataProperty(), type, visited))
                .sorted(Comparator.comparing(m -> m.get("label").toString()))
                .toList();

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", getLabel(property, ontology));
        node.put("children", children);
        node.put("hasChildren", !children.isEmpty());
        node.put("type", "DatatypeProperty");
        return node;
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
     * Get all inferred individuals with their types
     * GET /api/ontology/{projectId}/reasoner/inferred-individuals
     */
    @GetMapping("/{projectId}/reasoner/inferred-individuals")
    public ResponseEntity<Map<String, Object>> getAllInferredIndividuals(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "HERMIT") String reasonerType
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            ReasonerType type = ReasonerType.valueOf(reasonerType.toUpperCase());

            List<Map<String, Object>> individualsList = ontology.getIndividualsInSignature().stream()
                    .filter(OWLNamedIndividual::isNamed)
                    .map(ind -> {
                        Set<OWLClass> types = reasonerService.getInferredTypes(ontology, ind, type);
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", ind.getIRI().toString());
                        map.put("label", getLabel(ind, ontology));
                        map.put("type", "Individual");
                        map.put("inferredTypes", types.stream()
                                .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", getLabel(cls, ontology)))
                                .collect(Collectors.toList()));
                        return map;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "individuals", individualsList
            ));
        } catch (Exception e) {
            log.error("Error getting all inferred individuals", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred annotation property hierarchy
     * GET /api/ontology/{projectId}/reasoner/inferred-annotation-property-hierarchy
     */
    @GetMapping("/{projectId}/reasoner/inferred-annotation-property-hierarchy")
    public ResponseEntity<Map<String, Object>> getInferredAnnotationPropertyHierarchy(
            @PathVariable String projectId
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            
            // Annotation properties are not reasoned over by standard OWL reasoners,
            // so we return the asserted hierarchy to keep the UI consistent.
            List<Map<String, Object>> hierarchy = ontology.getAnnotationPropertiesInSignature().stream()
                    .map(prop -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("type", "AnnotationProperty");
                        node.put("children", List.of());
                        node.put("hasChildren", false);
                        return node;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "hierarchy", hierarchy
            ));
        } catch (Exception e) {
            log.error("Error getting inferred annotation property hierarchy", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get inferred datatypes
     * GET /api/ontology/{projectId}/reasoner/inferred-datatypes
     */
    @GetMapping("/{projectId}/reasoner/inferred-datatypes")
    public ResponseEntity<Map<String, Object>> getInferredDatatypes(
            @PathVariable String projectId
    ) {
        try {
            OWLOntology ontology = loadOntology(projectId);
            
            List<Map<String, Object>> datatypes = ontology.getDatatypesInSignature().stream()
                    .map(dt -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", dt.getIRI().toString());
                        node.put("label", getLabel(dt, ontology));
                        node.put("type", "Datatype");
                        return node;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "datatypes", datatypes
            ));
        } catch (Exception e) {
            log.error("Error getting inferred datatypes", e);
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
