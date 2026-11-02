package self.research.ontology.owlEditor.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.eclipse.rdf4j.rio.RDFFormat;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class DLQueryService {

    private static final Logger log = LoggerFactory.getLogger(DLQueryService.class);

    private ReasoningTaskRunner taskRunner = ReasoningTaskRunner.standalone();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTaskRunner(ReasoningTaskRunner taskRunner) {
        this.taskRunner = taskRunner;
    }

    private final GridFsTemplate gridfs;
    private final SparqlDatasetService datasetService;
    private final OWLReasonerFactory reasonerFactory;
    private final long maxReasonerTriples;
    private final ProjectImportService importService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HeavyWorkAdmission heavyWork = HeavyWorkAdmission.disabled();

    public DLQueryService(GridFsTemplate gridfs,
                          SparqlDatasetService datasetService,
                          @Value("${ontocode.reasoner.max-triples:5000000}") long maxReasonerTriples,
                          ProjectImportService importService) {
        this.gridfs = gridfs;
        this.datasetService = datasetService;
        this.maxReasonerTriples = maxReasonerTriples;
        this.importService = importService;
        OWLReasonerFactory factory = null;
        try {
            factory = new org.semanticweb.HermiT.ReasonerFactory();
            log.info("DLQueryService initialized with HermiT reasoner");
        } catch (Exception e) {
            log.warn("HermiT not available for DL Query: {}", e.getMessage());
        }
        this.reasonerFactory = factory;
    }

    public Map<String, Object> executeQuery(String projectId, String expression, List<String> queryTypes)
            throws Exception {
        return executeQuery(projectId, expression, queryTypes, null);
    }

    public Map<String, Object> executeQuery(String projectId, String expression, List<String> queryTypes,
                                            String draftUserId) throws Exception {
        long startTime = System.currentTimeMillis();
        List<String> resolvedTypes = queryTypes == null || queryTypes.isEmpty()
                ? Arrays.asList("subclasses", "instances")
                : queryTypes;

        try (QuerySession session = openSession(projectId, draftUserId)) {
            OWLOntology ontology = session.ontology();
            OWLReasoner reasoner = session.reasoner();

            OWLClassExpression classExpression = parseClassExpression(ontology, expression);
            if (classExpression == null) {
                return Map.of(
                        "success", false,
                        "error", "Failed to parse class expression: " + expression,
                        "hint", "Check Manchester OWL syntax. Examples: 'Person', 'Person and hasAge some integer'"
                );
            }

            if (resolvedTypes.stream().anyMatch(t -> t.equalsIgnoreCase("instances") || t.equalsIgnoreCase("directinstances"))) {
                reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
            }

            Map<String, Object> results = new HashMap<>();
            for (String queryType : resolvedTypes) {
                switch (queryType.toLowerCase()) {
                    case "directsuperclasses" ->
                            results.put("directSuperclasses", getSuperClasses(session, classExpression, ontology, true));
                    case "superclasses" ->
                            results.put("superclasses", getSuperClasses(session, classExpression, ontology, false));
                    case "equivalentclasses" ->
                            results.put("equivalentClasses", getEquivalentClasses(session, classExpression, ontology));
                    case "directsubclasses" ->
                            results.put("directSubclasses", getSubClasses(session, classExpression, ontology, true));
                    case "subclasses" ->
                            results.put("subclasses", getSubClasses(session, classExpression, ontology, false));
                    case "instances" ->
                            results.put("instances", getInstances(session, classExpression, ontology, false));
                    case "directinstances" ->
                            results.put("directInstances", getInstances(session, classExpression, ontology, true));
                    default -> log.warn("Unknown DL query type: {}", queryType);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("query", expression);
            response.put("queryType", resolvedTypes);
            response.put("results", results);
            response.put("executionTime", duration);
            if (session.note() != null) {
                response.put("downgradedWarning", session.note());
            }
            response.put("completion", session.complete() ? "complete" : "partial");
            log.info("DL Query completed for project {} in {}ms", projectId, duration);
            return response;
        }
    }

    public OWLOntology loadOntologyForParse(String projectId) throws Exception {
        LoadedOntology loaded = loadOntology(projectId, null);
        loaded.hold().close();
        return loaded.ontology();
    }

    public void disposeOntology(OWLOntology ontology) {
        if (ontology == null) {
            return;
        }
        try {
            ontology.getOWLOntologyManager().removeOntology(ontology);
        } catch (Exception e) {
            log.debug("Failed to remove ontology from manager: {}", e.getMessage());
        }
    }

    public OWLClassExpression parseClassExpression(OWLOntology ontology, String expression) {
        try {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            ShortFormProvider shortFormProvider = new SimpleShortFormProvider();
            Set<OWLOntology> importsClosure = ontology.getImportsClosure();
            BidirectionalShortFormProvider bidiProvider = new BidirectionalShortFormProviderAdapter(
                    manager, importsClosure, shortFormProvider);
            OWLEntityChecker entityChecker = new ShortFormEntityChecker(bidiProvider);

            ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
            parser.setOWLEntityChecker(entityChecker);
            parser.setDefaultOntology(ontology);
            parser.setStringToParse(expression);
            return parser.parseClassExpression();
        } catch (Exception e) {
            log.error("Failed to parse Manchester expression '{}': {}", expression, e.getMessage());
            try {
                return findClassByName(ontology, expression);
            } catch (Exception e2) {
                log.error("Fallback class lookup also failed: {}", e2.getMessage());
                return null;
            }
        }
    }

    private QuerySession openSession(String projectId, String draftUserId) throws Exception {
        if (reasonerFactory == null) {
            throw new IllegalStateException("No DL reasoner available on this server");
        }
        LoadedOntology loaded = loadOntology(projectId, draftUserId);
        try {
            return buildSession(projectId, loaded);
        } catch (Exception | Error e) {
            loaded.hold().close();
            throw e;
        }
    }

    private QuerySession buildSession(String projectId, LoadedOntology loaded) {
        self.research.ontology.common.rules.DisabledRules.strip(loaded.ontology());
        org.semanticweb.HermiT.Configuration hermitConfig = new org.semanticweb.HermiT.Configuration();
        hermitConfig.ignoreUnsupportedDatatypes = true;
        self.research.ontology.common.PreparedOntology prepared = self.research.ontology.common.SwrlBuiltIns
                .prepareChained(loaded.ontology(), self.research.ontology.common.RestRuleStage.shared(),
                        input -> new org.semanticweb.HermiT.ReasonerFactory().createReasoner(input), "HermiT 1.4.5.519", projectId + ":" + loaded.ontology().getAxiomCount());
        OWLReasoner reasoner = reasonerFactory.createReasoner(prepared.forHermit(), hermitConfig);

        return new QuerySession(loaded.manager(), loaded.ontology(), reasoner, prepared.note(), prepared.complete(),
                loaded.hold());
    }

    private LoadedOntology loadOntology(String projectId, String draftUserId) throws Exception {
        HeavyWorkAdmission.Hold[] held = {HeavyWorkAdmission.Hold.NONE};
        boolean loaded = false;
        try {
            LoadedOntology result = loadOntologyAdmitted(projectId, draftUserId, held);
            loaded = true;
            return new LoadedOntology(result.manager(), result.ontology(), held[0]);
        } finally {
            if (!loaded) {
                held[0].close();
            }
        }
    }

    private LoadedOntology loadOntologyAdmitted(String projectId, String draftUserId,
                                                HeavyWorkAdmission.Hold[] held) throws Exception {

        importService.syncProjectToFuseki(projectId);
        try {
            long tripleCount = datasetService.getDatasetSize(projectId);
            if (tripleCount > maxReasonerTriples) {
                throw new IllegalArgumentException(
                        "This ontology is too large for DL Query. Try a simpler expression or use the SPARQL tab instead.");
            }
            log.info("DL Query size check passed for {}: {} triples", projectId, tripleCount);
            held[0] = heavyWork.admitLoad(draftUserId, self.research.ontology.common.admission.WorkKind.DL_QUERY,
                    tripleCount);
        } catch (IllegalArgumentException | BusyException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not check ontology size before DL Query (will proceed): {}", e.getMessage());
            held[0] = heavyWork.admitLoad(draftUserId, self.research.ontology.common.admission.WorkKind.DL_QUERY, 0);
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("dlquery-" + projectId + "-", ".ttl");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                exportForQuery(projectId, draftUserId, out);
            }
            if (Files.size(tempFile) > 0) {
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                try (InputStream in = Files.newInputStream(tempFile)) {
                    OWLOntology ontology = manager.loadOntologyFromOntologyDocument(in);
                    log.info("DL Query loaded ontology from Fuseki for {} ({} axioms)", projectId, ontology.getAxiomCount());
                    return new LoadedOntology(manager, ontology);
                }
            }
        } catch (Exception e) {
            log.warn("Fuseki load failed for DL Query project {}, trying GridFS: {}", projectId, e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }

        GridFSFile file = gridfs.findOne(new Query(Criteria.where("metadata.projectId").is(projectId)));
        if (file == null) {
            file = gridfs.findOne(new Query(Criteria.where("filename").is(projectId + ".owl")));
        }
        if (file == null) {
            throw new RuntimeException("Ontology file not found for project: " + projectId);
        }

        GridFsResource resource = gridfs.getResource(file);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream inputStream = resource.getInputStream()) {
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputStream);
            return new LoadedOntology(manager, ontology);
        }
    }

    private void exportForQuery(String projectId, String draftUserId, OutputStream out) {
        if (draftUserId == null || draftUserId.isBlank()) {
            datasetService.exportDatasetToStream(projectId, RDFFormat.TURTLE, out);
            return;
        }
        String previousUser = SparqlQueryContext.getUserId();
        boolean previousDraft = SparqlQueryContext.wantsDraft();
        SparqlQueryContext.setUserId(draftUserId);
        SparqlQueryContext.setWantsDraft(true);
        try {
            datasetService.exportDatasetToStream(projectId, draftUserId, RDFFormat.TURTLE, out);
        } finally {
            SparqlQueryContext.setUserId(previousUser);
            SparqlQueryContext.setWantsDraft(previousDraft);
        }
    }

    private OWLClass findClassByName(OWLOntology ontology, String name) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        if (name.startsWith("http://") || name.startsWith("https://")) {
            return df.getOWLClass(IRI.create(name));
        }
        for (OWLClass cls : ontology.getClassesInSignature(true)) {
            String localName = cls.getIRI().getShortForm();
            if (localName.equalsIgnoreCase(name)
                    || cls.getIRI().toString().endsWith("#" + name)
                    || cls.getIRI().toString().endsWith("/" + name)) {
                return cls;
            }
        }
        for (OWLClass cls : ontology.getClassesInSignature(true)) {
            for (OWLAnnotation ann : EntitySearcher.getAnnotations(cls, ontology, df.getRDFSLabel())
                    .collect(Collectors.toList())) {
                if (ann.getValue() instanceof OWLLiteral literal && literal.getLiteral().equalsIgnoreCase(name)) {
                    return cls;
                }
            }
        }
        return null;
    }

    private <T> Set<T> runBounded(QuerySession session, java.util.function.Function<OWLReasoner, Set<T>> work,
            String description, OWLClassExpression expr, OWLOntology ontology) {
        if (session.abandoned()) {
            log.warn("Skipping {} for '{}': an earlier step of this query is still running", description, expr);
            return Collections.emptySet();
        }
        ReasoningTaskRunner.Execution<Set<T>> execution = taskRunner.submit("dl-query " + description, context -> {
            context.attach(session.reasoner());
            return work.apply(session.reasoner());
        });
        session.track(execution);
        try {
            return execution.await(Limit.DL_QUERY_TIMEOUT_SECONDS.value() * 1000L);
        } catch (TimeoutException te) {
            log.warn("Timed out computing {} for '{}' ({} classes in signature) — returning empty result",
                    description, expr, ontology.getClassesInSignature().size());
            if (!execution.stopped()) {
                session.abandon();
            }
            return Collections.emptySet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptySet();
        } catch (ExecutionException e) {
            log.error("Error computing {} for {}", description, expr, e);
            return Collections.emptySet();
        }
    }

    private List<Map<String, Object>> getSuperClasses(QuerySession session,
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        Set<OWLClass> classes = runBounded(session,
                reasoner -> reasoner.getSuperClasses(expr, direct).getFlattened(),
                direct ? "direct superclasses" : "superclasses", expr, ontology);
        return classes.stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> getSubClasses(QuerySession session,
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        Set<OWLClass> classes = runBounded(session,
                reasoner -> reasoner.getSubClasses(expr, direct).getFlattened(),
                direct ? "direct subclasses" : "subclasses", expr, ontology);
        return classes.stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> getEquivalentClasses(QuerySession session,
            OWLClassExpression expr, OWLOntology ontology) {
        Set<OWLClass> classes = runBounded(session,
                reasoner -> reasoner.getEquivalentClasses(expr).getEntities(),
                "equivalent classes", expr, ontology);
        return classes.stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> getInstances(QuerySession session,
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        Set<OWLNamedIndividual> individuals = runBounded(session,
                reasoner -> reasoner.getInstances(expr, direct).getFlattened(),
                direct ? "direct instances" : "instances", expr, ontology);
        return individuals.stream()
                .map(ind -> createResultItem("individual", ind.getIRI().toString(), getLabel(ind, ontology)))
                .collect(Collectors.toList());
    }

    private Map<String, Object> createResultItem(String type, String iri, String label) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", type);
        item.put("iri", iri);
        item.put("label", label);
        return item;
    }

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (OWLAnnotation ann : EntitySearcher.getAnnotations(entity, ontology, df.getRDFSLabel())
                .collect(Collectors.toList())) {
            if (ann.getValue() instanceof OWLLiteral literal) {
                return literal.getLiteral();
            }
        }
        return entity.getIRI().getShortForm();
    }

    private record LoadedOntology(OWLOntologyManager manager, OWLOntology ontology, HeavyWorkAdmission.Hold hold) {
        LoadedOntology(OWLOntologyManager manager, OWLOntology ontology) {
            this(manager, ontology, HeavyWorkAdmission.Hold.NONE);
        }
    }

    private static final class QuerySession implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final OWLOntology ontology;
        private final OWLReasoner reasoner;
        private final String note;
        private final boolean complete;
        private final HeavyWorkAdmission.Hold hold;
        private final List<ReasoningTaskRunner.Execution<?>> executions = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean abandoned;

        private QuerySession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner, String note, boolean complete,
                             HeavyWorkAdmission.Hold hold) {
            this.hold = hold;
            this.manager = manager;
            this.ontology = ontology;
            this.reasoner = reasoner;
            this.note = note;
            this.complete = complete;
        }

        boolean complete() {
            return complete;
        }

        String note() {
            return note;
        }

        OWLOntology ontology() {
            return ontology;
        }

        OWLReasoner reasoner() {
            return reasoner;
        }

        void track(ReasoningTaskRunner.Execution<?> execution) {
            executions.add(execution);
        }

        void abandon() {
            abandoned = true;
        }

        boolean abandoned() {
            return abandoned;
        }

        @Override
        public void close() {
            ReasoningTaskRunner.afterAll(executions, this::releaseAll);
        }

        private void releaseAll() {
            try {
                reasoner.dispose();
            } catch (Exception e) {
                log.debug("Reasoner dispose failed: {}", e.getMessage());
            }
            try {
                manager.removeOntology(ontology);
            } catch (Exception e) {
                log.debug("Ontology remove failed: {}", e.getMessage());
            }
            hold.close();
        }
    }
}
