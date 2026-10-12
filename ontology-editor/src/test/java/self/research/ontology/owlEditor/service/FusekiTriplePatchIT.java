package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.InputStream;
import java.io.StringReader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "fuseki.url", matches = ".+")
class FusekiTriplePatchIT {

    private static final String BASE = System.getProperty("fuseki.url", "");
    private static final String GRAPH = "urn:it:patch:" + System.nanoTime();
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
    private static final String TTL_HEAD = "@prefix ex: <http://example.org/> .\n@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n";

    private SPARQLRepository repository;
    private TriplePatchExecutor executor;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        repository = new SPARQLRepository(BASE + "/query", BASE + "/update");
        repository.setUsernameAndPassword("admin", "admin");
        SparqlDatasetService datasetService = mock(SparqlDatasetService.class);
        when(datasetService.graphTarget("p")).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, GRAPH));
        executor = new TriplePatchExecutor(datasetService);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.prepareUpdate("DROP SILENT GRAPH <" + GRAPH + ">").execute();
        }
        repository.shutDown();
    }

    private long putGraph(Path file, String contentType) throws Exception {
        long started = System.nanoTime();
        HttpRequest put = HttpRequest.newBuilder(URI.create(BASE + "/data?graph=" + URLEncoder.encode(GRAPH, StandardCharsets.UTF_8)))
                .header("Content-Type", contentType).header("Authorization", AUTH)
                .PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
        HttpResponse<String> response = http.send(put, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, "GSP PUT failed: " + response.statusCode() + " " + response.body());
        return (System.nanoTime() - started) / 1_000_000;
    }

    private Model stored() {
        try (RepositoryConnection conn = repository.getConnection()) {
            return QueryResults.asModel(conn.prepareGraphQuery("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + GRAPH + "> { ?s ?p ?o } }").evaluate());
        }
    }

    private static Path write(String content, String ext) throws Exception {
        Path file = Files.createTempFile("it-", "." + ext);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private void assertPatchedTo(String format, String ext, RDFFormat rdfFormat, String before, String after,
                                 TriplePatchPlanner.Edit... edits) throws Exception {
        Path oldFile = write(before, ext);
        Path newFile = write(after, ext);
        putGraph(oldFile, rdfFormat.getDefaultMIMEType());
        Optional<TriplePatchPlanner.TriplePatch> plan = new TriplePatchPlanner().plan(format, oldFile, newFile, List.of(edits), GRAPH);
        assertTrue(plan.isPresent(), "expected a patch plan");
        assertEquals(TriplePatchExecutor.Outcome.APPLIED, executor.apply("p", plan.get()));
        assertTrue(Models.isomorphic(stored(), Rio.parse(new StringReader(after), GRAPH, rdfFormat)), "Fuseki should equal a full reimport");
    }

    @Test
    void turtlePatchesMatchAFullReimportOnFuseki() throws Exception {
        String before = TTL_HEAD + "ex:Dog a owl:Class ;\n    rdfs:subClassOf [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:Cat ] .\n"
                + "ex:Cat rdfs:label \"Cat\" .\n";
        String after = TTL_HEAD + "ex:Dog a owl:Class ;\n    rdfs:subClassOf [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:Mouse ] .\n"
                + "ex:Cat rdfs:label \"Cat\"@en .\n";
        assertPatchedTo("turtle", "ttl", RDFFormat.TURTLE, before, after,
                new TriplePatchPlanner.Edit(4, 1, 1), new TriplePatchPlanner.Edit(5, 1, 1));
    }

    @Test
    void addingAPrefixAndABlockPatchesOnFuseki() throws Exception {
        String before = TTL_HEAD + "ex:Dog a owl:Class .\n";
        String after = TTL_HEAD + "@prefix vet: <http://vet.example/> .\nex:Dog a owl:Class .\nvet:Clinic a owl:Class .\n";
        assertPatchedTo("turtle", "ttl", RDFFormat.TURTLE, before, after,
                new TriplePatchPlanner.Edit(3, 0, 1), new TriplePatchPlanner.Edit(4, 0, 1));
    }

    @Test
    void rdfXmlPatchMatchesAFullReimportOnFuseki() throws Exception {
        String head = "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "    xmlns:owl=\"http://www.w3.org/2002/07/owl#\" xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n";
        String before = head + "    <owl:Class rdf:about=\"http://example.org/Dog\"/>\n</rdf:RDF>\n";
        String after = head + "    <owl:Class rdf:about=\"http://example.org/Dog\">\n        <rdfs:label>Dog</rdfs:label>\n    </owl:Class>\n</rdf:RDF>\n";
        assertPatchedTo("rdfxml", "rdf", RDFFormat.RDFXML, before, after, new TriplePatchPlanner.Edit(3, 1, 3));
    }

    @Test
    void functionalSyntaxPatchMatchesAFullReimportOnFuseki() throws Exception {
        String head = "Prefix(:=<http://example.org/pets#>)\nPrefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<http://example.org/pets>\nDeclaration(Class(:Cat))\nDeclaration(Class(:Mouse))\nDeclaration(ObjectProperty(:chases))\n"
                + "SubClassOf(:Cat ObjectSomeValuesFrom(:chases :Mouse))\n";
        Path oldFile = write(head + ")\n", "ofn");
        Path newFile = write(head + "AnnotationAssertion(rdfs:label :Cat \"Cat\")\n)\n", "ofn");
        Path converted = OWLFormatConverter.convertToRDFXML(oldFile);
        putGraph(converted, RDFFormat.RDFXML.getDefaultMIMEType());
        Optional<TriplePatchPlanner.TriplePatch> plan = new OwlFormatPatchPlanner().plan(oldFile, newFile, GRAPH);
        assertTrue(plan.isPresent());
        assertEquals(TriplePatchExecutor.Outcome.APPLIED, executor.apply("p", plan.get()));
        Path expected = OWLFormatConverter.convertToRDFXML(newFile);
        try (InputStream in = Files.newInputStream(expected)) {
            assertTrue(Models.isomorphic(stored(), Rio.parse(in, GRAPH, RDFFormat.RDFXML)));
        }
    }

    @Test
    void measureFullReloadAgainstPatchOnFuseki() throws Exception {
        for (String size : System.getProperty("bench.sizes", "1,10,50").split(",")) {
            int megabytes = Integer.parseInt(size.trim());
            Path oldFile = Files.createTempFile("it-old-", ".ttl");
            Path newFile = Files.createTempFile("it-new-", ".ttl");
            int blocks = generate(oldFile, megabytes, -1);
            generate(newFile, megabytes, blocks / 2);
            long reload = putGraph(oldFile, "text/turtle");
            long started = System.nanoTime();
            Optional<TriplePatchPlanner.TriplePatch> plan = new TriplePatchPlanner().plan("turtle", oldFile, newFile,
                    List.of(new TriplePatchPlanner.Edit(3 + (long) (blocks / 2) * 3 + 1, 1, 1)), GRAPH);
            long planned = System.nanoTime();
            assertTrue(plan.isPresent());
            assertEquals(TriplePatchExecutor.Outcome.APPLIED, executor.apply("p", plan.get()));
            long applied = System.nanoTime();
            System.out.printf("[PERF] fuseki %d MB | full load %d | patch plan %d | patch apply %d (ms)%n",
                    megabytes, reload, (planned - started) / 1_000_000, (applied - planned) / 1_000_000);
            try (RepositoryConnection conn = repository.getConnection()) {
                conn.prepareUpdate("DROP SILENT GRAPH <" + GRAPH + ">").execute();
            }
            Files.deleteIfExists(oldFile);
            Files.deleteIfExists(newFile);
        }
    }

    private static int generate(Path file, int megabytes, int changedBlock) throws Exception {
        long target = megabytes * 1024L * 1024L;
        long written = 0;
        int block = 0;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(TTL_HEAD);
            written += TTL_HEAD.length();
            while (written < target) {
                String label = block == changedBlock ? "Changed " + block : "Class " + block;
                String text = "ex:C" + block + " a owl:Class ;\n    rdfs:label \"" + label + "\"@en ;\n    rdfs:subClassOf ex:C" + (block / 2) + " .\n";
                out.write(text);
                written += text.length();
                block++;
            }
        }
        return block;
    }
}
