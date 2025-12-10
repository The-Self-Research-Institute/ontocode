package self.research.ontology.owlEditor.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.BidirectionalShortFormProvider;
import org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DL Query Controller - Manchester OWL Syntax queries like Protege's DL Query Tab
 * 
 * Supports querying for:
 * - Subclasses (direct and indirect)
 * - Superclasses (direct and indirect)
 * - Equivalent classes
 * - Instances (individuals of the class expression)
 * 
 * References:
 * - https://protegewiki.stanford.edu/wiki/DLQueryTab
 * - https://www.w3.org/TR/owl2-manchester-syntax/
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class DLQueryController {

    private static final Logger log = LoggerFactory.getLogger(DLQueryController.class);

    @Autowired
    private GridFsTemplate gridfs;

    // Reasoner factory - using Openllet (OWLAPI 5.x compatible)
    private OWLReasonerFactory reasonerFactory;

    // Cache for ontologies and reasoners
    private final Map<String, OWLOntology> ontologyCache = new HashMap<>();
    private final Map<String, OWLReasoner> reasonerCache = new HashMap<>();

    public DLQueryController() {
        // Initialize Openllet reasoner factory (OWLAPI 5.x compatible)
        try {
            this.reasonerFactory = openllet.owlapi.OpenlletReasonerFactory.getInstance();
            log.info("DLQueryController initialized with Openllet reasoner");
        } catch (Exception e) {
            log.warn("Openllet not available, DL queries may be limited: {}", e.getMessage());
        }
    }

    /**
     * Execute a DL Query using Manchester OWL Syntax
     * POST /api/ontology/{projectId}/dl-query
     */
    @PostMapping("/{projectId}/dl-query")
    public ResponseEntity<Map<String, Object>> executeDLQuery(
            @PathVariable String projectId,
            @RequestBody DLQueryRequest request
    ) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("DL Query for project {}: expression='{}', types={}", 
                    projectId, request.getExpression(), request.getQueryTypes());

            // Load ontology and reasoner
            OWLOntology ontology = loadOntology(projectId);
            OWLReasoner reasoner = getOrCreateReasoner(projectId, ontology);

            // Parse the Manchester syntax expression
            OWLClassExpression classExpression = parseClassExpression(ontology, request.getExpression());
            
            if (classExpression == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Failed to parse class expression: " + request.getExpression(),
                    "hint", "Check Manchester OWL syntax. Examples: 'Person', 'Person and hasAge some integer', 'hasChild some Man'"
                ));
            }

            // Execute queries based on requested types
            Map<String, Object> results = new HashMap<>();
            List<String> queryTypes = request.getQueryTypes();
            
            if (queryTypes == null || queryTypes.isEmpty()) {
                queryTypes = Arrays.asList("subclasses", "instances");
            }

            for (String queryType : queryTypes) {
                switch (queryType.toLowerCase()) {
                    case "directsuperclasses":
                        results.put("directSuperclasses", getSuperClasses(reasoner, classExpression, ontology, true));
                        break;
                    case "superclasses":
                        results.put("superclasses", getSuperClasses(reasoner, classExpression, ontology, false));
                        break;
                    case "equivalentclasses":
                        results.put("equivalentClasses", getEquivalentClasses(reasoner, classExpression, ontology));
                        break;
                    case "directsubclasses":
                        results.put("directSubclasses", getSubClasses(reasoner, classExpression, ontology, true));
                        break;
                    case "subclasses":
                        results.put("subclasses", getSubClasses(reasoner, classExpression, ontology, false));
                        break;
                    case "instances":
                        results.put("instances", getInstances(reasoner, classExpression, ontology, false));
                        break;
                    case "directinstances":
                        results.put("directInstances", getInstances(reasoner, classExpression, ontology, true));
                        break;
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("query", request.getExpression());
            response.put("queryType", queryTypes);
            response.put("results", results);
            response.put("executionTime", duration);

            log.info("DL Query completed in {}ms", duration);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("DL Query error for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage(),
                "query", request.getExpression()
            ));
        }
    }

    /**
     * Parse a Manchester OWL Syntax class expression
     */
    private OWLClassExpression parseClassExpression(OWLOntology ontology, String expression) {
        try {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            OWLDataFactory dataFactory = manager.getOWLDataFactory();

            // Create short form provider for entity lookup
            ShortFormProvider shortFormProvider = new SimpleShortFormProvider();
            Set<OWLOntology> importsClosure = ontology.getImportsClosure();
            BidirectionalShortFormProvider bidiProvider = new BidirectionalShortFormProviderAdapter(
                    manager, importsClosure, shortFormProvider);

            // Create entity checker
            OWLEntityChecker entityChecker = new ShortFormEntityChecker(bidiProvider);

            // Create Manchester syntax parser
            ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
            parser.setOWLEntityChecker(entityChecker);
            parser.setDefaultOntology(ontology);
            parser.setStringToParse(expression);

            return parser.parseClassExpression();

        } catch (Exception e) {
            log.error("Failed to parse Manchester expression '{}': {}", expression, e.getMessage());
            
            // Try fallback: treat as simple class name
            try {
                return findClassByName(ontology, expression);
            } catch (Exception e2) {
                log.error("Fallback class lookup also failed: {}", e2.getMessage());
                return null;
            }
        }
    }

    /**
     * Fallback: find class by local name or IRI
     */
    private OWLClass findClassByName(OWLOntology ontology, String name) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        
        // First check if it's a full IRI
        if (name.startsWith("http://") || name.startsWith("https://")) {
            return df.getOWLClass(IRI.create(name));
        }

        // Look for class by local name
        for (OWLClass cls : ontology.getClassesInSignature(true)) {
            String localName = cls.getIRI().getShortForm();
            if (localName.equalsIgnoreCase(name) || 
                cls.getIRI().toString().endsWith("#" + name) ||
                cls.getIRI().toString().endsWith("/" + name)) {
                return cls;
            }
        }
        
        // Check rdfs:label annotations using OWLAPI 5.x EntitySearcher
        for (OWLClass cls : ontology.getClassesInSignature(true)) {
            for (OWLAnnotation ann : EntitySearcher.getAnnotations(cls, ontology, df.getRDFSLabel()).collect(Collectors.toList())) {
                if (ann.getValue() instanceof OWLLiteral) {
                    String label = ((OWLLiteral) ann.getValue()).getLiteral();
                    if (label.equalsIgnoreCase(name)) {
                        return cls;
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Get superclasses of a class expression
     */
    private List<Map<String, Object>> getSuperClasses(OWLReasoner reasoner, 
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        
        return reasoner.getSuperClasses(expr, direct).getFlattened().stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    /**
     * Get subclasses of a class expression
     */
    private List<Map<String, Object>> getSubClasses(OWLReasoner reasoner, 
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        
        return reasoner.getSubClasses(expr, direct).getFlattened().stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    /**
     * Get equivalent classes of a class expression
     */
    private List<Map<String, Object>> getEquivalentClasses(OWLReasoner reasoner, 
            OWLClassExpression expr, OWLOntology ontology) {
        
        return reasoner.getEquivalentClasses(expr).getEntities().stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    /**
     * Get instances of a class expression
     */
    private List<Map<String, Object>> getInstances(OWLReasoner reasoner, 
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        
        return reasoner.getInstances(expr, direct).getFlattened().stream()
                .map(ind -> createResultItem("individual", ind.getIRI().toString(), getLabel(ind, ontology)))
                .collect(Collectors.toList());
    }

    /**
     * Create a result item map
     */
    private Map<String, Object> createResultItem(String type, String iri, String label) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", type);
        item.put("iri", iri);
        item.put("label", label);
        return item;
    }

    /**
     * Get rdfs:label for an entity using OWLAPI 5.x EntitySearcher
     */
    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        
        for (OWLAnnotation ann : EntitySearcher.getAnnotations(entity, ontology, df.getRDFSLabel()).collect(Collectors.toList())) {
            if (ann.getValue() instanceof OWLLiteral) {
                return ((OWLLiteral) ann.getValue()).getLiteral();
            }
        }
        
        // Fallback to short form
        return entity.getIRI().getShortForm();
    }

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
     * Get or create a reasoner for the ontology
     */
    private OWLReasoner getOrCreateReasoner(String projectId, OWLOntology ontology) {
        if (reasonerCache.containsKey(projectId)) {
            OWLReasoner cached = reasonerCache.get(projectId);
            if (!cached.isConsistent()) {
                // Reasoner may be stale, recreate
                cached.dispose();
                reasonerCache.remove(projectId);
            } else {
                return cached;
            }
        }

        if (reasonerFactory == null) {
            throw new RuntimeException("No reasoner factory available");
        }

        OWLReasoner reasoner = reasonerFactory.createReasoner(ontology);
        reasoner.precomputeInferences();
        reasonerCache.put(projectId, reasoner);
        return reasoner;
    }

    /**
     * Clear caches for a project (call when ontology is modified)
     */
    public void clearCache(String projectId) {
        ontologyCache.remove(projectId);
        OWLReasoner reasoner = reasonerCache.remove(projectId);
        if (reasoner != null) {
            reasoner.dispose();
        }
    }

    /**
     * DL Query Request DTO
     */
    public static class DLQueryRequest {
        private String expression;
        private List<String> queryTypes;

        public String getExpression() {
            return expression;
        }

        public void setExpression(String expression) {
            this.expression = expression;
        }

        public List<String> getQueryTypes() {
            return queryTypes;
        }

        public void setQueryTypes(List<String> queryTypes) {
            this.queryTypes = queryTypes;
        }
    }
}
