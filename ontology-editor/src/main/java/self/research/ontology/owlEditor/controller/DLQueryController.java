package self.research.ontology.owlEditor.controller;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.service.DLQueryQueueManager;
import self.research.ontology.owlEditor.service.DLQueryQueueProcessor;
import self.research.ontology.owlEditor.service.DLQueryService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    private OntologyMutationService mutationService;

    @Autowired
    private ReasonerService reasonerService;

    @Autowired
    private DLQueryService dlQueryService;

    @Autowired
    private DLQueryQueueManager dlQueryQueueManager;

    @Autowired(required = false)
    private DLQueryQueueProcessor dlQueryQueueProcessor;

    @Autowired(required = false)
    private ReasonerWorkerClient reasonerWorkerClient;

    @Autowired(required = false)
    private ReasoningJobRelayService reasoningJobRelayService;

    @Value("${ontocode.reasoner-worker.enabled:false}")
    private boolean reasonerWorkerEnabled;

    /**
     * Submit a DL Query job (async). Results arrive via WebSocket /topic/dlquery/{jobId}
     * or GET /api/dl-query/jobs/{jobId}.
     * POST /api/ontology/{projectId}/dl-query
     */
    @PostMapping("/{projectId}/dl-query")
    public ResponseEntity<Map<String, Object>> executeDLQuery(
            @PathVariable String projectId,
            @RequestBody DLQueryRequest request
    ) {
        try {
            if (request.getExpression() == null || request.getExpression().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "DL query expression is required"
                ));
            }

            log.info("DL Query enqueue for project {}: expression='{}', types={}",
                    projectId, request.getExpression(), request.getQueryTypes());

            if (reasonerWorkerEnabled && reasonerWorkerClient != null && reasoningJobRelayService != null) {
                Map<String, Object> worker = reasonerWorkerClient.submitJob(
                        "DL_QUERY",
                        projectId,
                        request.getExpression().trim(),
                        request.getQueryTypes(),
                        null,
                        request.getUserEmail());
                if (Boolean.FALSE.equals(worker.get("success"))) {
                    return ResponseEntity.status(500).body(Map.of(
                            "success", false,
                            "error", ReasoningFriendlyErrors.forUser(stringOrNull(worker.get("error"))),
                            "query", request.getExpression()
                    ));
                }
                String jobId = stringOrNull(worker.get("jobId"));
                reasoningJobRelayService.rememberSubmittedJob(
                        jobId, projectId, request.getExpression().trim(), "DL_QUERY", worker);

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("async", true);
                response.put("jobId", jobId);
                response.put("status", worker.getOrDefault("status", "QUEUED"));
                response.put("queuePosition", worker.getOrDefault("queuePosition", 1));
                response.put("estimatedWaitTimeMs", worker.getOrDefault("estimatedWaitTimeMs", 0));
                response.put("query", request.getExpression());
                return ResponseEntity.accepted().body(response);
            }

            DLQueryJob job = dlQueryQueueManager.enqueue(
                    projectId,
                    request.getExpression().trim(),
                    request.getQueryTypes(),
                    request.getUserEmail());
            if (dlQueryQueueProcessor != null) {
                dlQueryQueueProcessor.processNext();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("async", true);
            response.put("jobId", job.getJobId());
            response.put("status", job.getStatus().name());
            response.put("queuePosition", job.getQueuePosition());
            response.put("estimatedWaitTimeMs", dlQueryQueueManager.getEstimatedWaitTimeMs(job.getJobId()));
            response.put("query", request.getExpression());
            return ResponseEntity.accepted().body(response);

        } catch (Exception e) {
            log.error("DL Query enqueue error for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", ReasoningFriendlyErrors.forUser(e.getMessage()),
                    "query", request.getExpression()
            ));
        }
    }

    private static String stringOrNull(Object value) {
        return value != null ? value.toString() : null;
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

            OWLOntology ontology = dlQueryService.loadOntologyForParse(projectId);
            try {
                OWLClassExpression classExpression = dlQueryService.parseClassExpression(ontology, request.getExpression());
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
                axioms.add(df.getOWLSubClassOfAxiom(newClass, df.getOWLThing()));
                axioms.add(df.getOWLAnnotationAssertionAxiom(
                        df.getRDFSLabel(),
                        newClass.getIRI(),
                        df.getOWLLiteral(request.getClassName().trim())
                ));
                axioms.add(df.getOWLEquivalentClassesAxiom(newClass, classExpression));

                String sparql = buildInsertDataFromAxioms(axioms);
                mutationService.applyRawUpdate(projectId, sparql);

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "classIri", classIri,
                        "className", request.getClassName().trim(),
                        "expression", request.getExpression().trim(),
                        "message", "Created defined class with EquivalentTo axiom"
                ));
            } finally {
                dlQueryService.disposeOntology(ontology);
            }
        } catch (Exception e) {
            log.error("Failed to add DL query expression to ontology for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to add DL query expression"
            ));
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
        private String userEmail;

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

        public String getUserEmail() {
            return userEmail;
        }

        public void setUserEmail(String userEmail) {
            this.userEmail = userEmail;
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

            OWLOntology ontology = dlQueryService.loadOntologyForParse(projectId);
            try {
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
            } finally {
                dlQueryService.disposeOntology(ontology);
            }

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

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        return entity.getIRI().getShortForm();
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
