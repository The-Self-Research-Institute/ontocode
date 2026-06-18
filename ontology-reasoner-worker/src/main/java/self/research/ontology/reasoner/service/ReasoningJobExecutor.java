package self.research.ontology.reasoner.service;

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
import org.springframework.stereotype.Service;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReasoningJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReasoningJobExecutor.class);

    private final OntologySessionService sessionService;
    private final OWLReasonerFactory dlReasonerFactory;

    public ReasoningJobExecutor(OntologySessionService sessionService) {
        this.sessionService = sessionService;
        OWLReasonerFactory factory = null;
        try {
            factory = openllet.owlapi.OpenlletReasonerFactory.getInstance();
        } catch (Exception e) {
            log.warn("Openllet not available for DL Query: {}", e.getMessage());
        }
        this.dlReasonerFactory = factory;
    }

    public Map<String, Object> execute(ReasoningJob job) throws Exception {
        return switch (job.getJobType()) {
            case DL_QUERY -> executeDlQuery(job);
            case REASONER_CONSISTENCY -> executeConsistency(job);
            case REASONER_CLASSIFY -> executeClassify(job);
            case REASONER_REALIZE -> executeRealize(job);
            case REASONER_RUN -> executeFullRun(job);
        };
    }

    private Map<String, Object> executeDlQuery(ReasoningJob job) throws Exception {
        if (dlReasonerFactory == null) {
            throw new IllegalStateException("No DL reasoner available on this worker");
        }
        List<String> types = job.getQueryTypes() == null || job.getQueryTypes().isEmpty()
                ? Arrays.asList("subclasses", "instances")
                : job.getQueryTypes();

        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), ReasonerType.OPENLLET)) {
            OWLOntology ontology = session.ontology();
            OWLReasoner reasoner = session.reasoner();
            OWLClassExpression expr = parseClassExpression(ontology, job.getExpression());
            if (expr == null) {
                return Map.of(
                        "success", false,
                        "error", "Failed to parse class expression: " + job.getExpression(),
                        "hint", "Check Manchester OWL syntax. Examples: 'Person', 'Person and hasAge some integer'"
                );
            }

            Map<String, Object> results = new HashMap<>();
            for (String queryType : types) {
                switch (queryType.toLowerCase(Locale.ROOT)) {
                    case "directsuperclasses" ->
                            results.put("directSuperclasses", superClasses(reasoner, expr, ontology, true));
                    case "superclasses" ->
                            results.put("superclasses", superClasses(reasoner, expr, ontology, false));
                    case "equivalentclasses" ->
                            results.put("equivalentClasses", equivalentClasses(reasoner, expr, ontology));
                    case "directsubclasses" ->
                            results.put("directSubclasses", subClasses(reasoner, expr, ontology, true));
                    case "subclasses" ->
                            results.put("subclasses", subClasses(reasoner, expr, ontology, false));
                    case "instances" ->
                            results.put("instances", instances(reasoner, expr, ontology, false));
                    case "directinstances" ->
                            results.put("directInstances", instances(reasoner, expr, ontology, true));
                    default -> log.warn("Unknown DL query type: {}", queryType);
                }
            }

            return Map.of(
                    "success", true,
                    "query", job.getExpression(),
                    "queryType", types,
                    "results", results
            );
        }
    }

    private Map<String, Object> executeConsistency(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type)) {
            OWLReasoner reasoner = session.reasoner();
            boolean consistent = reasoner.isConsistent();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("consistent", consistent);
            result.put("reasonerType", type.getDisplayName());
            result.put("projectId", job.getProjectId());
            if (!consistent) {
                var unsat = reasoner.getUnsatisfiableClasses().getEntities();
                OWLDataFactory df = session.ontology().getOWLOntologyManager().getOWLDataFactory();
                unsat.remove(df.getOWLNothing());
                result.put("unsatisfiableClasses", unsat.stream()
                        .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", label(cls, session.ontology())))
                        .collect(Collectors.toList()));
            }
            return result;
        }
    }

    private Map<String, Object> executeClassify(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type)) {
            OWLReasoner reasoner = session.reasoner();
            long start = System.currentTimeMillis();
            precomputeHierarchy(reasoner, type);
            long duration = System.currentTimeMillis() - start;
            return Map.of(
                    "success", true,
                    "reasonerType", type.getDisplayName(),
                    "durationMs", duration,
                    "message", "Classification completed successfully"
            );
        }
    }

    private Map<String, Object> executeRealize(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type)) {
            OWLReasoner reasoner = session.reasoner();
            long start = System.currentTimeMillis();
            reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
            long duration = System.currentTimeMillis() - start;
            return Map.of(
                    "success", true,
                    "reasonerType", type.getDisplayName(),
                    "durationMs", duration,
                    "message", "Realization completed successfully"
            );
        }
    }

    private Map<String, Object> executeFullRun(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type)) {
            OWLReasoner reasoner = session.reasoner();
            Map<String, Object> result = new HashMap<>();
            long totalStart = System.currentTimeMillis();

            long t0 = System.currentTimeMillis();
            boolean consistent = reasoner.isConsistent();
            result.put("consistencyCheckMs", System.currentTimeMillis() - t0);
            result.put("consistent", consistent);
            if (!consistent) {
                result.put("success", false);
                result.put("message", "Ontology is inconsistent.");
                return result;
            }

            t0 = System.currentTimeMillis();
            precomputeHierarchy(reasoner, type);
            result.put("classificationMs", System.currentTimeMillis() - t0);

            t0 = System.currentTimeMillis();
            reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
            result.put("realizationMs", System.currentTimeMillis() - t0);

            result.put("totalDurationMs", System.currentTimeMillis() - totalStart);
            result.put("reasonerType", type.getDisplayName());
            result.put("success", true);
            result.put("message", "Reasoning completed successfully");
            return result;
        }
    }

    private void precomputeHierarchy(OWLReasoner reasoner, ReasonerType type) {
        if (type == ReasonerType.ELK) {
            reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY);
        } else {
            reasoner.precomputeInferences(
                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
                    org.semanticweb.owlapi.reasoner.InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    org.semanticweb.owlapi.reasoner.InferenceType.DATA_PROPERTY_HIERARCHY
            );
        }
    }

    private ReasonerType parseReasonerType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReasonerType.OPENLLET;
        }
        String normalized = raw.toUpperCase(Locale.ROOT);
        if ("HERMIT".equals(normalized)) {
            return ReasonerType.OPENLLET;
        }
        return ReasonerType.valueOf(normalized);
    }

    private OWLClassExpression parseClassExpression(OWLOntology ontology, String expression) {
        try {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            ShortFormProvider shortFormProvider = new SimpleShortFormProvider();
            BidirectionalShortFormProvider bidi = new BidirectionalShortFormProviderAdapter(
                    manager, ontology.getImportsClosure(), shortFormProvider);
            OWLEntityChecker checker = new ShortFormEntityChecker(bidi);
            ManchesterOWLSyntaxParser parser = org.semanticweb.owlapi.apibinding.OWLManager.createManchesterParser();
            parser.setOWLEntityChecker(checker);
            parser.setDefaultOntology(ontology);
            parser.setStringToParse(expression);
            return parser.parseClassExpression();
        } catch (Exception e) {
            return findClassByName(ontology, expression);
        }
    }

    private OWLClass findClassByName(OWLOntology ontology, String name) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        if (name.startsWith("http://") || name.startsWith("https://")) {
            return df.getOWLClass(IRI.create(name));
        }
        for (OWLClass cls : ontology.getClassesInSignature(true)) {
            if (cls.getIRI().getShortForm().equalsIgnoreCase(name)) {
                return cls;
            }
        }
        return null;
    }

    private List<Map<String, Object>> superClasses(OWLReasoner reasoner, OWLClassExpression expr,
                                                   OWLOntology ontology, boolean direct) {
        return reasoner.getSuperClasses(expr, direct).getFlattened().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> item("class", c, ontology))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> subClasses(OWLReasoner reasoner, OWLClassExpression expr,
                                                 OWLOntology ontology, boolean direct) {
        return reasoner.getSubClasses(expr, direct).getFlattened().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> item("class", c, ontology))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> equivalentClasses(OWLReasoner reasoner, OWLClassExpression expr,
                                                        OWLOntology ontology) {
        return reasoner.getEquivalentClasses(expr).getEntities().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> item("class", c, ontology))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> instances(OWLReasoner reasoner, OWLClassExpression expr,
                                                OWLOntology ontology, boolean direct) {
        return reasoner.getInstances(expr, direct).getFlattened().stream()
                .map(i -> item("individual", i, ontology))
                .collect(Collectors.toList());
    }

    private Map<String, Object> item(String type, OWLEntity entity, OWLOntology ontology) {
        return Map.of("type", type, "iri", entity.getIRI().toString(), "label", label(entity, ontology));
    }

    private String label(OWLEntity entity, OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (OWLAnnotation ann : EntitySearcher.getAnnotations(entity, ontology, df.getRDFSLabel())
                .collect(Collectors.toList())) {
            if (ann.getValue() instanceof OWLLiteral literal) {
                return literal.getLiteral();
            }
        }
        return entity.getIRI().getShortForm();
    }
}
