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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Ephemeral in-memory DL Query execution — load, reason, dispose per job.
 * No long-lived ontology/reasoner caches (unlike the legacy DLQueryController maps).
 */
@Service
public class DLQueryService {

    private static final Logger log = LoggerFactory.getLogger(DLQueryService.class);

    // reasoner.getInstances()/getSubClasses()/getSuperClasses()/getEquivalentClasses() can
    // all hang for many minutes on a large ontology — "subclasses" and "instances" are the
    // two query types checked by default in the UI, so this isn't an edge case. Every
    // reasoner call in this service runs through runBounded() so a DL Query never hangs the
    // request regardless of which query types were selected; a small/simple ontology (the
    // common case) finishes well within this budget either way.
    private static final long REASONER_QUERY_TIMEOUT_MS = 10_000;
    private final ExecutorService dlQueryExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dl-query-get-instances-worker");
        t.setDaemon(true);
        return t;
    });

    private final GridFsTemplate gridfs;
    private final SparqlDatasetService datasetService;
    private final OWLReasonerFactory reasonerFactory;
    private final long maxReasonerTriples;
    private final ProjectImportService importService;

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
            factory = openllet.owlapi.OpenlletReasonerFactory.getInstance();
            log.info("DLQueryService initialized with Openllet reasoner");
        } catch (Exception e) {
            log.warn("Openllet not available for DL Query: {}", e.getMessage());
        }
        this.reasonerFactory = factory;
    }

    /**
     * Run a DL query and return the API response map. All OWLAPI objects are disposed before return.
     */
    public Map<String, Object> executeQuery(String projectId, String expression, List<String> queryTypes)
            throws Exception {
        long startTime = System.currentTimeMillis();
        List<String> resolvedTypes = queryTypes == null || queryTypes.isEmpty()
                ? Arrays.asList("subclasses", "instances")
                : queryTypes;

        try (QuerySession session = openSession(projectId)) {
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

            Map<String, Object> results = new HashMap<>();
            for (String queryType : resolvedTypes) {
                switch (queryType.toLowerCase()) {
                    case "directsuperclasses" ->
                            results.put("directSuperclasses", getSuperClasses(reasoner, classExpression, ontology, true));
                    case "superclasses" ->
                            results.put("superclasses", getSuperClasses(reasoner, classExpression, ontology, false));
                    case "equivalentclasses" ->
                            results.put("equivalentClasses", getEquivalentClasses(reasoner, classExpression, ontology));
                    case "directsubclasses" ->
                            results.put("directSubclasses", getSubClasses(reasoner, classExpression, ontology, true));
                    case "subclasses" ->
                            results.put("subclasses", getSubClasses(reasoner, classExpression, ontology, false));
                    case "instances" ->
                            results.put("instances", getInstances(reasoner, classExpression, ontology, false));
                    case "directinstances" ->
                            results.put("directInstances", getInstances(reasoner, classExpression, ontology, true));
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
            log.info("DL Query completed for project {} in {}ms", projectId, duration);
            return response;
        }
    }

    /** Load ontology for parse-only operations (add-to-ontology). No reasoner created. */
    public OWLOntology loadOntologyForParse(String projectId) throws Exception {
        LoadedOntology loaded = loadOntology(projectId);
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

    private QuerySession openSession(String projectId) throws Exception {
        if (reasonerFactory == null) {
            throw new IllegalStateException("No DL reasoner available on this server");
        }
        LoadedOntology loaded = loadOntology(projectId);
        OWLReasoner reasoner = reasonerFactory.createReasoner(loaded.ontology());
        // Do not precomputeInferences() — DL queries compute lazily and precompute can OOM large ontologies.
        return new QuerySession(loaded.manager(), loaded.ontology(), reasoner);
    }

    private LoadedOntology loadOntology(String projectId) throws Exception {
        // About to stream from Fuseki below — on desktop, Fuseki sync after a mutation is
        // deferred (debounced up to 20s+); the frontend's tab-activation gate covers switching
        // *to* DL Query, but not mutating while already on it. No-ops on cloud/when in sync.
        importService.syncProjectToFuseki(projectId);
        try {
            long tripleCount = datasetService.getDatasetSize(projectId);
            if (tripleCount > maxReasonerTriples) {
                throw new IllegalArgumentException(
                        "This ontology is too large for DL Query. Try a simpler expression or use the SPARQL tab instead.");
            }
            log.info("DL Query size check passed for {}: {} triples", projectId, tripleCount);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not check ontology size before DL Query (will proceed): {}", e.getMessage());
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("dlquery-" + projectId + "-", ".ttl");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                datasetService.exportDatasetToStream(projectId, RDFFormat.TURTLE, out);
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

    /**
     * Runs a reasoner call on a bounded worker thread and falls back to an empty result on
     * timeout or error instead of ever letting a DL Query hang the request indefinitely.
     */
    private <T> Set<T> runBounded(java.util.function.Supplier<Set<T>> work, String description,
            OWLClassExpression expr, OWLOntology ontology) {
        CompletableFuture<Set<T>> future = CompletableFuture.supplyAsync(work, dlQueryExecutor);
        try {
            return future.get(REASONER_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("Timed out computing {} for '{}' ({} classes in signature) — returning empty result",
                    description, expr, ontology.getClassesInSignature().size());
            return Collections.emptySet();
        } catch (Exception e) {
            log.error("Error computing {} for {}", description, expr, e);
            return Collections.emptySet();
        }
    }

    private List<Map<String, Object>> getSuperClasses(OWLReasoner reasoner,
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        Set<OWLClass> classes = runBounded(
                () -> reasoner.getSuperClasses(expr, direct).getFlattened(),
                direct ? "direct superclasses" : "superclasses", expr, ontology);
        return classes.stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> getSubClasses(OWLReasoner reasoner,
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        Set<OWLClass> classes = runBounded(
                () -> reasoner.getSubClasses(expr, direct).getFlattened(),
                direct ? "direct subclasses" : "subclasses", expr, ontology);
        return classes.stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> getEquivalentClasses(OWLReasoner reasoner,
            OWLClassExpression expr, OWLOntology ontology) {
        Set<OWLClass> classes = runBounded(
                () -> reasoner.getEquivalentClasses(expr).getEntities(),
                "equivalent classes", expr, ontology);
        return classes.stream()
                .filter(cls -> !cls.isOWLThing() && !cls.isOWLNothing())
                .map(cls -> createResultItem("class", cls.getIRI().toString(), getLabel(cls, ontology)))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> getInstances(OWLReasoner reasoner,
            OWLClassExpression expr, OWLOntology ontology, boolean direct) {
        Set<OWLNamedIndividual> individuals = runBounded(
                () -> reasoner.getInstances(expr, direct).getFlattened(),
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

    private record LoadedOntology(OWLOntologyManager manager, OWLOntology ontology) {}

    private static final class QuerySession implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final OWLOntology ontology;
        private final OWLReasoner reasoner;

        private QuerySession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner) {
            this.manager = manager;
            this.ontology = ontology;
            this.reasoner = reasoner;
        }

        OWLOntology ontology() {
            return ontology;
        }

        OWLReasoner reasoner() {
            return reasoner;
        }

        @Override
        public void close() {
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
        }
    }
}
