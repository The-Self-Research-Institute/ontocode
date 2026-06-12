package self.research.ontology.owlEditor.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
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
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    @Autowired
    private OntologyMutationService mutationService;

    @Autowired
    private ReasonerService reasonerService;

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
     * Add the current DL Query expression as a named defined class, matching
     * Protégé's DL Query "Add to ontology" behavior:
     * NewClass EquivalentTo <Manchester expression>.
     */
    @PostMapping("/{projectId}/dl/add")
    public ResponseEntity<Map<String, Object>> addDLQueryToOntology(
            @PathVariable String projectId,
            @RequestBody DLAddRequest request
    ) {
        try {
            if (request.getExpression() == null || request.getExpression().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "DL query expression is required"
                ));
            }
            if (request.getClassName() == null || request.getClassName().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Class name is required"
                ));
            }

            OWLOntology ontology = loadOntology(projectId);
            OWLClassExpression classExpression = parseClassExpression(ontology, request.getExpression());
            if (classExpression == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Failed to parse class expression: " + request.getExpression()
                ));
            }

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = manager.getOWLDataFactory();
            String classIri = resolveNewClassIri(ontology, request.getClassName());
            OWLClass newClass = df.getOWLClass(IRI.create(classIri));

            Set<OWLAxiom> axioms = new LinkedHashSet<>();
            axioms.add(df.getOWLDeclarationAxiom(newClass));
            // Keep the newly defined class visible in the asserted hierarchy
            // immediately, while the real definition remains the EquivalentTo axiom.
            axioms.add(df.getOWLSubClassOfAxiom(newClass, df.getOWLThing()));
            axioms.add(df.getOWLAnnotationAssertionAxiom(
                    df.getRDFSLabel(),
                    newClass.getIRI(),
                    df.getOWLLiteral(request.getClassName().trim())
            ));
            axioms.add(df.getOWLEquivalentClassesAxiom(newClass, classExpression));

            String sparql = buildInsertDataFromAxioms(axioms);
            mutationService.applyRawUpdate(projectId, sparql);
            clearCache(projectId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "classIri", classIri,
                    "className", request.getClassName().trim(),
                    "expression", request.getExpression().trim(),
                    "message", "Created defined class with EquivalentTo axiom"
            ));
        } catch (Exception e) {
            log.error("Failed to add DL query expression to ontology for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to add DL query expression"
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

    private String resolveNewClassIri(OWLOntology ontology, String className) {
        String trimmed = className.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("urn:")) {
            return trimmed;
        }

        String base = ontology.getOntologyID().getOntologyIRI()
                .map(IRI::toString)
                .map(this::asNamespace)
                .orElseGet(() -> inferNamespaceFromClasses(ontology).orElse("http://example.org/ontocode#"));

        return base + sanitizeLocalName(trimmed);
    }

    private String asNamespace(String iri) {
        if (iri.endsWith("#") || iri.endsWith("/")) {
            return iri;
        }
        return iri + "#";
    }

    private Optional<String> inferNamespaceFromClasses(OWLOntology ontology) {
        return ontology.getClassesInSignature(true).stream()
                .map(cls -> cls.getIRI().toString())
                .filter(iri -> !iri.startsWith("http://www.w3.org/2002/07/owl#"))
                .map(iri -> {
                    int hash = iri.lastIndexOf('#');
                    if (hash >= 0) {
                        return iri.substring(0, hash + 1);
                    }
                    int slash = iri.lastIndexOf('/');
                    return slash >= 0 ? iri.substring(0, slash + 1) : iri + "#";
                })
                .findFirst();
    }

    private String sanitizeLocalName(String label) {
        String sanitized = label.trim()
                .replaceAll("\\s+", "_")
                .replaceAll("[^A-Za-z0-9_\\-.]", "_");
        if (sanitized.isBlank()) {
            sanitized = "DLQueryClass";
        }
        if (!Character.isLetter(sanitized.charAt(0)) && sanitized.charAt(0) != '_') {
            sanitized = "Class_" + sanitized;
        }
        return sanitized;
    }

    private String buildInsertDataFromAxioms(Set<OWLAxiom> axioms) throws Exception {
        OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();
        OWLOntology tempOntology = tempManager.createOntology(
                IRI.create("urn:ontocode:dl-query-add:" + UUID.randomUUID())
        );
        tempManager.addAxioms(tempOntology, axioms);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tempManager.saveOntology(tempOntology, new RDFXMLDocumentFormat(), out);

        org.eclipse.rdf4j.model.Model model = org.eclipse.rdf4j.rio.Rio.parse(
                new ByteArrayInputStream(out.toByteArray()),
                "",
                org.eclipse.rdf4j.rio.RDFFormat.RDFXML
        );

        StringBuilder sparql = new StringBuilder("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
                INSERT DATA {
                """);

        for (org.eclipse.rdf4j.model.Statement st : model) {
            if (isTemporaryOntologyHeader(st)) {
                continue;
            }
            sparql.append("  ")
                    .append(toSparqlTerm(st.getSubject()))
                    .append(" ")
                    .append(toSparqlTerm(st.getPredicate()))
                    .append(" ")
                    .append(toSparqlTerm(st.getObject()))
                    .append(" .\n");
        }
        sparql.append("}");
        return sparql.toString();
    }

    private boolean isTemporaryOntologyHeader(org.eclipse.rdf4j.model.Statement st) {
        return st.getPredicate().stringValue().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
                && st.getObject().stringValue().equals("http://www.w3.org/2002/07/owl#Ontology");
    }

    private String toSparqlTerm(org.eclipse.rdf4j.model.Value value) {
        if (value instanceof org.eclipse.rdf4j.model.IRI iri) {
            return "<" + iri.stringValue() + ">";
        }
        if (value instanceof org.eclipse.rdf4j.model.BNode bNode) {
            return "_:dl_" + bNode.getID().replaceAll("[^A-Za-z0-9_]", "_");
        }
        if (value instanceof org.eclipse.rdf4j.model.Literal literal) {
            String escaped = literal.getLabel()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
            StringBuilder term = new StringBuilder("\"").append(escaped).append("\"");
            literal.getLanguage().ifPresent(lang -> term.append("@").append(lang));
            if (literal.getLanguage().isEmpty() && literal.getDatatype() != null) {
                term.append("^^<").append(literal.getDatatype().stringValue()).append(">");
            }
            return term.toString();
        }
        return "\"" + value.stringValue().replace("\"", "\\\"") + "\"";
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

    public static class DLAddRequest {
        private String expression;
        private String className;
        private String userEmail;

        public String getExpression() {
            return expression;
        }

        public void setExpression(String expression) {
            this.expression = expression;
        }

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public String getUserEmail() {
            return userEmail;
        }

        public void setUserEmail(String userEmail) {
            this.userEmail = userEmail;
        }
    }

    // ─── Axiom Explanation ────────────────────────────────────────────────────

    /**
     * Return the justification set(s) for an asserted axiom.
     * POST /api/ontology/{projectId}/explain-axiom
     *
     * Body: { entityIri, relatedIri, sectionName, justificationType, maxJustifications }
     */
    @PostMapping("/{projectId}/explain-axiom")
    public ResponseEntity<Map<String, Object>> explainAxiom(
            @PathVariable String projectId,
            @RequestBody ExplainAxiomRequest request
    ) {
        long t0 = System.currentTimeMillis();
        try {
            log.info("explain-axiom project={} entity={} related={} section={}",
                    projectId, request.getEntityIri(), request.getRelatedIri(), request.getSectionName());

            OWLOntology ontology = loadOntology(projectId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            String sec = request.getSectionName() != null
                    ? request.getSectionName().toLowerCase() : "";
            int maxJ = request.getMaxJustifications() > 0 ? request.getMaxJustifications() : 99;

            boolean laconic = request.getJustificationType() != null
                    && request.getJustificationType().toLowerCase().contains("laconic");

            Set<OWLAxiom> matchedOwlAxioms = findMatchingOwlAxioms(
                    ontology, df, request.getEntityIri(), request.getRelatedIri(), sec);

            boolean isInferred = false;
            if (matchedOwlAxioms.isEmpty()) {
                isInferred = isInferredRelationship(
                        ontology, df, request.getEntityIri(), request.getRelatedIri(), sec);
                if (isInferred) {
                    matchedOwlAxioms = findSupportingAxioms(
                            ontology, df, request.getEntityIri(), request.getRelatedIri(), sec);
                }
            }

            List<Map<String, Object>> rawAxioms = matchedOwlAxioms.stream()
                    .map(ax -> axiomToMap(ontology, ax))
                    .collect(Collectors.toList());

            List<Map<String, Object>> justifications = new ArrayList<>();
            if (laconic && !rawAxioms.isEmpty()) {
                Map<String, Object> j = new LinkedHashMap<>();
                j.put("index", 1);
                j.put("axioms", rawAxioms.stream().limit(maxJ).collect(Collectors.toList()));
                j.put("isAsserted", !isInferred);
                j.put("isInferred", isInferred);
                justifications.add(j);
            } else {
                for (int i = 0; i < Math.min(rawAxioms.size(), maxJ); i++) {
                    Map<String, Object> j = new LinkedHashMap<>();
                    j.put("index", i + 1);
                    j.put("axioms", List.of(rawAxioms.get(i)));
                    j.put("isAsserted", !isInferred);
                    j.put("isInferred", isInferred);
                    justifications.add(j);
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("justifications", justifications);
            resp.put("totalFound", rawAxioms.size());
            resp.put("executionTimeMs", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("explain-axiom failed for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to explain axiom"
            ));
        }
    }

    private List<Map<String, Object>> findMatchingAxioms(
            OWLOntology ontology, OWLDataFactory df,
            String entityIri, String relatedIri, String sec) {
        return findMatchingOwlAxioms(ontology, df, entityIri, relatedIri, sec).stream()
                .map(ax -> axiomToMap(ontology, ax))
                .collect(Collectors.toList());
    }

    private Set<OWLAxiom> findMatchingOwlAxioms(
            OWLOntology ontology, OWLDataFactory df,
            String entityIri, String relatedIri, String sec) {

        Set<OWLAxiom> results = new LinkedHashSet<>();
        if (entityIri == null || relatedIri == null) return results;

        IRI eIri = IRI.create(entityIri);

        try {
            if (sec.contains("range")) {
                df.getOWLDataProperty(eIri).accept(new org.semanticweb.owlapi.model.OWLPropertyExpressionVisitor() {
                    @Override public void visit(OWLDataProperty p) {
                        ontology.getDataPropertyRangeAxioms(p).stream()
                                .filter(ax -> axiomInvolves(ax, relatedIri))
                                .forEach(results::add);
                    }
                    @Override public void visit(OWLObjectProperty p) {}
                    @Override public void visit(OWLObjectInverseOf p) {}
                });
                ontology.getObjectPropertyRangeAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("domain")) {
                ontology.getDataPropertyDomainAxioms(df.getOWLDataProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);
                ontology.getObjectPropertyDomainAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("subclass") || sec.contains("sub class")) {
                ontology.getSubClassAxiomsForSubClass(df.getOWLClass(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("equivalent")) {
                ontology.getEquivalentClassesAxioms(df.getOWLClass(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);
                ontology.getEquivalentObjectPropertiesAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);
                ontology.getEquivalentDataPropertiesAxioms(df.getOWLDataProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("disjoint")) {
                ontology.getDisjointClassesAxioms(df.getOWLClass(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);
                ontology.getDisjointObjectPropertiesAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("inverse")) {
                ontology.getInverseObjectPropertyAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("subproperty") || sec.contains("superprop") || sec.contains("super property")) {
                ontology.getObjectSubPropertyAxiomsForSubProperty(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);
                ontology.getDataSubPropertyAxiomsForSubProperty(df.getOWLDataProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("same")) {
                ontology.getSameIndividualAxioms(df.getOWLNamedIndividual(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else if (sec.contains("different")) {
                ontology.getDifferentIndividualAxioms(df.getOWLNamedIndividual(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, relatedIri))
                        .forEach(results::add);

            } else {
                IRI rIri = IRI.create(relatedIri);
                ontology.getAxioms().stream()
                        .filter(ax -> ax.getSignature().stream().anyMatch(e -> e.getIRI().equals(eIri))
                                   && ax.getSignature().stream().anyMatch(e -> e.getIRI().equals(rIri)))
                        .forEach(results::add);
            }
        } catch (Exception e) {
            log.warn("findMatchingOwlAxioms error entity={} related={}: {}", entityIri, relatedIri, e.getMessage());
        }
        return results;
    }

    private Set<OWLAxiom> findSupportingAxioms(
            OWLOntology ontology, OWLDataFactory df,
            String entityIri, String relatedIri, String sec) {

        Set<OWLAxiom> supporting = new LinkedHashSet<>();
        if (entityIri == null) return supporting;

        IRI eIri = IRI.create(entityIri);
        try {
            if (ontology.containsClassInSignature(eIri)) {
                ontology.getAxioms(df.getOWLClass(eIri)).stream()
                        .filter(ax -> !ax.isAnnotationAxiom())
                        .filter(ax -> relatedIri == null || axiomInvolves(ax, relatedIri))
                        .forEach(supporting::add);
            }
            if (ontology.containsObjectPropertyInSignature(eIri)) {
                ontology.getAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> !ax.isAnnotationAxiom())
                        .filter(ax -> relatedIri == null || axiomInvolves(ax, relatedIri))
                        .forEach(supporting::add);
            }
            if (ontology.containsDataPropertyInSignature(eIri)) {
                ontology.getAxioms(df.getOWLDataProperty(eIri)).stream()
                        .filter(ax -> !ax.isAnnotationAxiom())
                        .filter(ax -> relatedIri == null || axiomInvolves(ax, relatedIri))
                        .forEach(supporting::add);
            }
            if (ontology.containsIndividualInSignature(eIri)) {
                ontology.getAxioms(df.getOWLNamedIndividual(eIri)).stream()
                        .filter(ax -> !ax.isAnnotationAxiom())
                        .filter(ax -> relatedIri == null || axiomInvolves(ax, relatedIri))
                        .forEach(supporting::add);
            }
        } catch (Exception e) {
            log.warn("findSupportingAxioms error entity={}: {}", entityIri, e.getMessage());
        }
        return supporting;
    }

    private boolean isInferredRelationship(
            OWLOntology ontology, OWLDataFactory df,
            String entityIri, String relatedIri, String sec) {

        if (entityIri == null || relatedIri == null) return false;
        try {
            OWLReasoner reasoner = reasonerService.getReasoner(ontology, ReasonerType.HERMIT);
            IRI eIri = IRI.create(entityIri);
            IRI rIri = IRI.create(relatedIri);

            if ((sec.contains("subclass") || sec.contains("sub class"))
                    && ontology.containsClassInSignature(eIri) && ontology.containsClassInSignature(rIri)) {
                OWLClass sub = df.getOWLClass(eIri);
                OWLClass sup = df.getOWLClass(rIri);
                boolean entailed = reasoner.getSuperClasses(sub, true).getFlattened().contains(sup);
                boolean asserted = ontology.containsAxiom(df.getOWLSubClassOfAxiom(sub, sup));
                return entailed && !asserted;
            }
            if (sec.contains("equivalent") && ontology.containsClassInSignature(eIri)
                    && ontology.containsClassInSignature(rIri)) {
                OWLClass cls = df.getOWLClass(eIri);
                OWLClass other = df.getOWLClass(rIri);
                boolean entailed = reasoner.getEquivalentClasses(cls).getEntities().contains(other);
                boolean asserted = ontology.getEquivalentClassesAxioms(cls).stream()
                        .anyMatch(ax -> axiomInvolves(ax, relatedIri));
                return entailed && !asserted;
            }
            if ((sec.contains("subproperty") || sec.contains("superprop"))
                    && ontology.containsObjectPropertyInSignature(eIri)
                    && ontology.containsObjectPropertyInSignature(rIri)) {
                OWLObjectProperty sub = df.getOWLObjectProperty(eIri);
                OWLObjectProperty sup = df.getOWLObjectProperty(rIri);
                boolean entailed = reasoner.getSuperObjectProperties(sub, true).getFlattened().contains(sup);
                boolean asserted = ontology.containsAxiom(df.getOWLSubObjectPropertyOfAxiom(sub, sup));
                return entailed && !asserted;
            }
        } catch (Exception e) {
            log.warn("isInferredRelationship check failed: {}", e.getMessage());
        }
        return false;
    }

    private boolean axiomInvolves(OWLAxiom axiom, String iri) {
        return axiom.getSignature().stream().anyMatch(e -> e.getIRI().toString().equals(iri));
    }

    private Map<String, Object> axiomToMap(OWLOntology ontology, OWLAxiom axiom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", axiom.getAxiomType().getName());
        m.put("isAsserted", true);
        m.put("manchester", renderAxiomLabel(ontology, axiom));
        m.put("entities", axiom.getSignature().stream()
                .map(e -> {
                    Map<String, String> em = new LinkedHashMap<>();
                    em.put("iri",   e.getIRI().toString());
                    em.put("label", getLabel(e, ontology));
                    em.put("type",  e.getEntityType().getName());
                    return em;
                })
                .collect(Collectors.toList()));
        return m;
    }

    private String renderAxiomLabel(OWLOntology ontology, OWLAxiom axiom) {
        try {
            String typeName = axiom.getAxiomType().getName();
            String entities = axiom.getSignature().stream()
                    .map(e -> "'" + getLabel(e, ontology) + "'")
                    .collect(Collectors.joining(", "));
            return typeName + ": " + entities;
        } catch (Exception e) {
            return axiom.getAxiomType().getName();
        }
    }

    public static class ExplainAxiomRequest {
        private String entityIri;
        private String relatedIri;
        private String sectionName;
        private String justificationType = "regular";
        private int maxJustifications = 3;

        public String getEntityIri() { return entityIri; }
        public void setEntityIri(String v) { this.entityIri = v; }
        public String getRelatedIri() { return relatedIri; }
        public void setRelatedIri(String v) { this.relatedIri = v; }
        public String getSectionName() { return sectionName; }
        public void setSectionName(String v) { this.sectionName = v; }
        public String getJustificationType() { return justificationType; }
        public void setJustificationType(String v) { this.justificationType = v; }
        public int getMaxJustifications() { return maxJustifications; }
        public void setMaxJustifications(int v) { this.maxJustifications = v; }
    }
}
