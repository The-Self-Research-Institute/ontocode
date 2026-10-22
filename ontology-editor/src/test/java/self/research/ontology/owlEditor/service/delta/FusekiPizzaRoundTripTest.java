package self.research.ontology.owlEditor.service.delta;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "FUSEKI_URL", matches = ".+")
class FusekiPizzaRoundTripTest {

    private static final String G = "urn:test:pizza";
    private static final String NS = "http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#";
    private static final String PREFIXES = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
            + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
            + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";

    private Repository repo;
    private SparqlDatasetService injector;
    private OntologyMutationService mutator;

    private static String base() {
        String url = System.getenv("FUSEKI_URL");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @BeforeEach
    void setUp() throws Exception {
        SPARQLRepository sparql = new SPARQLRepository(base() + "/sparql", base() + "/update");
        sparql.init();
        repo = sparql;
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate("DROP SILENT GRAPH <" + G + ">").execute();
        }
        Path file = Path.of(System.getProperty("user.dir")).getParent().resolve("test-data/PizzaTutorialWithDataV2 (1).owl");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/data?graph=" + URLEncoder.encode(G, StandardCharsets.UTF_8)))
                .header("Content-Type", "application/rdf+xml")
                .POST(HttpRequest.BodyPublishers.ofFile(file)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() / 100 == 2, response.body());
        injector = Mockito.mock(SparqlDatasetService.class, Mockito.CALLS_REAL_METHODS);
        mutator = Mockito.mock(OntologyMutationService.class, Mockito.CALLS_REAL_METHODS);
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private static MutationOp op(String type, String iri, String target) {
        return new MutationOp(type, iri, null, null, null, null, target, null, null, null, null, null, null, null, null);
    }

    private String updateFor(MutationOp... ops) {
        return List.of(ops).stream()
                .map(o -> (String) ReflectionTestUtils.invokeMethod(mutator, "toUpdate", "p", o))
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n;\n"));
    }

    private void apply(String update) {
        String body = update.replaceAll("(?m)^\\s*PREFIX\\s+(rdf|rdfs|owl|xsd):.*$", "");
        String injected = ReflectionTestUtils.invokeMethod(injector, "injectGraphContext", PREFIXES + body, G);
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate(injected).execute();
        }
    }

    private Model dump() {
        try (RepositoryConnection c = repo.getConnection()) {
            Model model = new LinkedHashModel();
            c.getStatements(null, null, null, false, SimpleValueFactory.getInstance().createIRI(G))
                    .forEach(s -> model.add(s.getSubject(), s.getPredicate(), s.getObject()));
            return model;
        }
    }

    private boolean ask(String query) {
        try (RepositoryConnection c = repo.getConnection()) {
            return c.prepareBooleanQuery(query).evaluate();
        }
    }

    private static String restrictionsOf(Model model, String local) {
        org.eclipse.rdf4j.model.IRI subject = SimpleValueFactory.getInstance().createIRI(NS + local);
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (org.eclipse.rdf4j.model.Statement link : model.filter(subject, org.eclipse.rdf4j.model.vocabulary.RDFS.SUBCLASSOF, null)) {
            if (link.getObject() instanceof org.eclipse.rdf4j.model.BNode node) {
                java.util.List<String> triples = new java.util.ArrayList<>();
                model.filter(node, null, null).forEach(s -> triples.add(s.getPredicate().getLocalName() + "="
                        + (s.getObject().stringValue().contains("#") ? s.getObject().stringValue().substring(s.getObject().stringValue().indexOf('#') + 1) : s.getObject().stringValue())));
                java.util.Collections.sort(triples);
                parts.add(triples.toString());
            }
        }
        java.util.Collections.sort(parts);
        return parts.toString();
    }

    private static void assertSame(String step, MutationOp[] ops, Model expected, Model actual, ExactDelta delta) {
        if (Models.isomorphic(expected, actual)) {
            return;
        }
        Model a = CanonicalGraph.canonicalise(expected);
        Model b = CanonicalGraph.canonicalise(actual);
        Model missing = new LinkedHashModel(a);
        missing.removeAll(b);
        Model extra = new LinkedHashModel(b);
        extra.removeAll(a);
        throw new AssertionError(step + " mismatch for " + ops[0].type() + " " + ops[0].iri() + " expected=" + expected.size()
                + " actual=" + actual.size() + "\nMISSING(" + missing.size() + ")=" + head(missing)
                + "\nEXTRA(" + extra.size() + ")=" + head(extra) + "\nDELTA del=" + delta.deleted().size()
                + " ins=" + delta.inserted().size());
    }

    private static String head(Model model) {
        return ExactDelta.write(model).lines().limit(8).collect(Collectors.joining("\n  ", "\n  ", ""));
    }

    @Test
    void thePizzaGraphHasNoStructurallyIdenticalBlankNodes() {
        Model raw = dump();
        Model canonical = CanonicalGraph.canonicalise(raw);
        java.util.Map<org.eclipse.rdf4j.model.Resource, Long> byParent = new java.util.HashMap<>();
        System.out.println("[PIZZA] raw=" + raw.size() + " canonical=" + canonical.size());
        raw.stream().filter(s -> s.getObject() instanceof org.eclipse.rdf4j.model.BNode)
                .forEach(s -> byParent.merge(s.getSubject(), 1L, Long::sum));
        System.out.println("[PIZZA] subjects with blank-node objects=" + byParent.size());
        assertTrue(raw.size() == canonical.size(), "canonical form collapsed " + (raw.size() - canonical.size()) + " triples");
    }

    private void wholeGraphRoundTrip(MutationOp... ops) {
        Model before = dump();
        long started = System.nanoTime();
        DeltaCapture capture = DeltaCapture.beginWholeGraph(repo, G).orElseThrow();
        long captureMs = (System.nanoTime() - started) / 1_000_000;
        apply(updateFor(ops));
        Model changed = dump();
        assertFalse(Models.isomorphic(before, changed), "the operation changed nothing");
        ExactDelta delta = capture.finish().orElseThrow();
        for (String query : DeltaSparql.holds(delta, true, G)) {
            assertTrue(ask(query), query);
        }
        String reverse = DeltaSparql.reverse(delta);
        int statements = reverse.split(" ;\n").length;
        apply(reverse);
        Model afterUndo = dump();
        System.out.println("[PIZZA] " + ops[0].iri().substring(ops[0].iri().indexOf('#') + 1) + " before=" + before.size()
                + " changed=" + changed.size() + " afterUndo=" + afterUndo.size() + " del=" + delta.deleted().size()
                + " ins=" + delta.inserted().size() + " reverseStatements=" + statements);
        System.out.println("[PIZZA] OLIVE before=" + restrictionsOf(before, "OliveTopping") + " afterUndo="
                + restrictionsOf(afterUndo, "OliveTopping") + " changed=" + restrictionsOf(changed, "OliveTopping"));
        for (String part : reverse.split(" ;\n")) {
            if (part.contains("OliveTopping") || part.contains("c5129ebabf3937d93") || part.contains("c8092f23f41f63409")) {
                System.out.println("[PIZZA] STMT=" + part.substring(0, Math.min(1500, part.length())).replace("http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#", "NS:").replace("http://www.w3.org/2002/07/owl#", "owl:").replace("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:"));
            }
        }
        ExactDelta.write(delta.inserted()).lines().filter(l -> l.contains("c5129ebabf3937d93"))
                .forEach(l -> System.out.println("[PIZZA] INS-LABEL " + l.replace("http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#", "NS:")));
        ExactDelta.write(delta.deleted()).lines().filter(l -> l.contains("c8092f23f41f63409"))
                .forEach(l -> System.out.println("[PIZZA] DEL-LABEL " + l.replace("http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#", "NS:")));
        System.out.println("[PIZZA] DELTA-DEL-OLIVE=" + ExactDelta.write(delta.deleted()).lines()
                .filter(l -> l.contains("OliveTopping") || l.contains("Mild")).limit(6).collect(Collectors.joining(" | ")));
        System.out.println("[PIZZA] DELTA-INS-OLIVE=" + ExactDelta.write(delta.inserted()).lines()
                .filter(l -> l.contains("OliveTopping") || l.contains("Mild")).limit(6).collect(Collectors.joining(" | ")));
        for (String query : DeltaSparql.holds(delta, false, G)) {
            assertTrue(ask(query), "holds(redo) failed: " + query);
        }
        assertSame("undo", ops, before, afterUndo, delta);
        apply(DeltaSparql.forward(delta));
        assertSame("redo", ops, changed, dump(), delta);
        apply(DeltaSparql.reverse(delta));
        assertSame("second undo", ops, before, dump(), delta);
        System.out.println("[PIZZA] triples=" + before.size() + " delta=" + (delta.deleted().size() + delta.inserted().size())
                + " beginMs=" + captureMs);
    }

    @Test
    void deletingRealClassesRestoresEverythingThatReferredToThem() {
        for (String name : List.of("CheeseTopping", "MargheritaPizza", "VegetarianPizza", "Pizza")) {
            wholeGraphRoundTrip(op("deleteClass", NS + name, null));
        }
    }

    @Test
    void deletingARealPropertyRestoresItsRestrictions() {
        wholeGraphRoundTrip(op("deleteObjectProperty", NS + "hasTopping", null));
    }

    @Test
    void editingRealClassesThroughTheNeighbourhoodCapture() {
        IRI pizza = SimpleValueFactory.getInstance().createIRI(NS + "Pizza");
        MutationOp restriction = new MutationOp("addObjectRestriction", NS + "MargheritaPizza", null, null, NS + "hasTopping", null,
                NS + "CheeseTopping", null, "some", null, null, null, null, null, null);
        Model before = dump();
        DeltaCapture capture = DeltaCapture.begin(repo, G, ExactSeeds.seeds(List.of(restriction)),
                ExactSeeds.incomingSeeds(List.of(restriction))).orElseThrow();
        apply(updateFor(restriction));
        Model changed = dump();
        ExactDelta delta = capture.finish().orElseThrow();
        apply(DeltaSparql.reverse(delta));
        assertTrue(Models.isomorphic(before, dump()));
        apply(DeltaSparql.forward(delta));
        assertTrue(Models.isomorphic(changed, dump()));
        assertTrue(pizza.stringValue().endsWith("Pizza"));
    }
}
