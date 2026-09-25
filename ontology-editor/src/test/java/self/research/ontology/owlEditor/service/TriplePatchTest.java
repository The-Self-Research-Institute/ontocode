package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TriplePatchTest {

    private static final String GRAPH = "urn:test:graph";
    private static final String TTL_HEADER = String.join("\n",
            "@prefix ex: <http://example.org/> .",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            "");

    private Repository repository;
    private TriplePatchExecutor executor;
    private final TriplePatchPlanner planner = new TriplePatchPlanner();

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        SparqlDatasetService datasetService = mock(SparqlDatasetService.class);
        when(datasetService.graphTarget("p")).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, GRAPH));
        executor = new TriplePatchExecutor(datasetService);
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    private static Path write(String content, String extension) throws Exception {
        Path file = Files.createTempFile("patch-", "." + extension);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Model parse(String content, RDFFormat format) throws Exception {
        return Rio.parse(new StringReader(content), GRAPH, format);
    }

    private void load(String content, RDFFormat format) throws Exception {
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.add(parse(content, format), Values.iri(GRAPH));
        }
    }

    private Model stored() {
        try (RepositoryConnection conn = repository.getConnection()) {
            Model model = new LinkedHashModel();
            conn.getStatements(null, null, null, Values.iri(GRAPH)).forEach(st ->
                    model.add(st.getSubject(), st.getPredicate(), st.getObject()));
            return model;
        }
    }

    private Optional<TriplePatchPlanner.TriplePatch> patch(String format, String oldText, String newText,
                                                           TriplePatchPlanner.Edit... edits) throws Exception {
        String ext = format.equals("rdfxml") ? "rdf" : "ttl";
        return planner.plan(format, write(oldText, ext), write(newText, ext), List.of(edits), GRAPH);
    }

    private void assertPatchedTo(String format, RDFFormat rdfFormat, String oldText, String newText,
                                 TriplePatchPlanner.Edit... edits) throws Exception {
        load(oldText, rdfFormat);
        Optional<TriplePatchPlanner.TriplePatch> plan = patch(format, oldText, newText, edits);
        assertTrue(plan.isPresent(), "expected a patch plan");
        assertEquals(TriplePatchExecutor.Outcome.APPLIED, executor.apply("p", plan.get()));
        assertTrue(Models.isomorphic(stored(), parse(newText, rdfFormat)), "store should equal a full reimport");
    }

    private static String ttl(String... lines) {
        return TTL_HEADER + String.join("\n", lines) + "\n";
    }

    @Test
    void insertingAPredicateInsideABlockPatchesOnlyThatSubject() throws Exception {
        String before = ttl("ex:Dog a owl:Class ;", "    rdfs:subClassOf ex:Mammal .", "", "ex:Cat a owl:Class .");
        String after = ttl("ex:Dog a owl:Class ;", "    rdfs:label \"Dog\"@en ;", "    rdfs:subClassOf ex:Mammal .", "",
                "ex:Cat a owl:Class .");
        assertPatchedTo("turtle", RDFFormat.TURTLE, before, after, new TriplePatchPlanner.Edit(4, 0, 1));
    }

    @Test
    void changingALiteralReplacesTheOldValue() throws Exception {
        String before = ttl("ex:Dog rdfs:label \"Dgo\"@en .", "ex:Cat rdfs:label \"Cat\"@en .");
        String after = ttl("ex:Dog rdfs:label \"Dog\"@en .", "ex:Cat rdfs:label \"Cat\"@en .");
        assertPatchedTo("turtle", RDFFormat.TURTLE, before, after, new TriplePatchPlanner.Edit(3, 1, 1));
    }

    @Test
    void deletingAWholeBlockRemovesItsTriples() throws Exception {
        String before = ttl("ex:Dog a owl:Class .", "ex:Gone a owl:Class ;", "    rdfs:label \"x\" .", "ex:Cat a owl:Class .");
        String after = ttl("ex:Dog a owl:Class .", "ex:Cat a owl:Class .");
        assertPatchedTo("turtle", RDFFormat.TURTLE, before, after, new TriplePatchPlanner.Edit(4, 2, 0));
    }

    @Test
    void insertingANewBlockBetweenBlocksAddsIt() throws Exception {
        String before = ttl("ex:Dog a owl:Class .", "ex:Cat a owl:Class .");
        String after = ttl("ex:Dog a owl:Class .", "ex:Bird a owl:Class ;", "    rdfs:label \"Bird\" .", "ex:Cat a owl:Class .");
        assertPatchedTo("turtle", RDFFormat.TURTLE, before, after, new TriplePatchPlanner.Edit(4, 0, 2));
    }

    @Test
    void changingARestrictionReplacesTheBlankNodeTree() throws Exception {
        String before = ttl("ex:Dog a owl:Class ;", "    rdfs:subClassOf [ a owl:Restriction ;",
                "        owl:onProperty ex:hasOwner ;", "        owl:someValuesFrom ex:Owner ] .", "ex:Cat a owl:Class .");
        String after = ttl("ex:Dog a owl:Class ;", "    rdfs:subClassOf [ a owl:Restriction ;",
                "        owl:onProperty ex:hasOwner ;", "        owl:someValuesFrom ex:Person ] .", "ex:Cat a owl:Class .");
        assertPatchedTo("turtle", RDFFormat.TURTLE, before, after, new TriplePatchPlanner.Edit(6, 1, 1));
    }

    @Test
    void aTripleStillAssertedInAnotherBlockOfTheSameSubjectIsKept() throws Exception {
        String before = ttl("ex:Dog rdfs:label \"Dog\" ;", "    rdfs:comment \"x\" .", "ex:Cat a owl:Class .",
                "ex:Dog rdfs:label \"Dog\" .");
        String after = ttl("ex:Dog rdfs:comment \"x\" .", "ex:Cat a owl:Class .", "ex:Dog rdfs:label \"Dog\" .");
        assertPatchedTo("turtle", RDFFormat.TURTLE, before, after, new TriplePatchPlanner.Edit(3, 2, 1));
    }

    @Test
    void severalEditsAcrossTheDocumentPatchTogether() throws Exception {
        String before = ttl("ex:A ex:p ex:old .", "ex:B ex:q ex:x .", "ex:C ex:p ex:old .");
        String after = ttl("ex:A ex:p ex:new .", "ex:B ex:q ex:x .", "ex:C ex:p ex:new .");
        assertPatchedTo("turtle", RDFFormat.TURTLE, before, after,
                new TriplePatchPlanner.Edit(3, 1, 1), new TriplePatchPlanner.Edit(5, 1, 1));
    }

    @Test
    void rdfXmlClassWithARestrictionGetsALabelByPatch() throws Exception {
        String head = "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns=\"http://example.org/\" xml:base=\"http://example.org/\"\n"
                + "    xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n"
                + "    xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "    xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n";
        String dog = "    <owl:Class rdf:about=\"http://example.org/Dog\">\n"
                + "        <rdfs:subClassOf>\n"
                + "            <owl:Restriction>\n"
                + "                <owl:onProperty rdf:resource=\"http://example.org/hasOwner\"/>\n"
                + "                <owl:someValuesFrom rdf:resource=\"http://example.org/Owner\"/>\n"
                + "            </owl:Restriction>\n"
                + "        </rdfs:subClassOf>\n";
        String cat = "    <owl:Class rdf:about=\"http://example.org/Cat\"/>\n</rdf:RDF>\n";
        String before = head + dog + "    </owl:Class>\n" + cat;
        String after = head + dog + "        <rdfs:label xml:lang=\"en\">Dog</rdfs:label>\n    </owl:Class>\n" + cat;
        assertPatchedTo("rdfxml", RDFFormat.RDFXML, before, after, new TriplePatchPlanner.Edit(12, 0, 1));
    }

    @Test
    void editingAPrefixDeclarationFallsBackToAFullReimport() throws Exception {
        String before = ttl("ex:Dog a owl:Class .");
        String after = before.replace("@prefix ex: <http://example.org/> .", "@prefix ex: <http://example.com/> .");
        assertTrue(patch("turtle", before, after, new TriplePatchPlanner.Edit(0, 1, 1)).isEmpty());
    }

    @Test
    void labelledBlankNodesFallBackToAFullReimport() throws Exception {
        String before = ttl("ex:Dog ex:p _:b1 .", "_:b1 ex:q ex:x .");
        String after = ttl("ex:Dog ex:p _:b1 .", "_:b1 ex:q ex:y .");
        assertTrue(patch("turtle", before, after, new TriplePatchPlanner.Edit(4, 1, 1)).isEmpty());
    }

    @Test
    void nestedDescriptionsOfOtherSubjectsFallBackToAFullReimport() throws Exception {
        String head = "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "    xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n";
        String before = head + "    <rdf:Description rdf:about=\"http://example.org/A\">\n"
                + "        <rdfs:seeAlso><rdf:Description rdf:about=\"http://example.org/B\"><rdfs:label>b</rdfs:label>"
                + "</rdf:Description></rdfs:seeAlso>\n    </rdf:Description>\n</rdf:RDF>\n";
        String after = before.replace("<rdfs:label>b</rdfs:label>", "<rdfs:label>c</rdfs:label>");
        assertTrue(patch("rdfxml", before, after, new TriplePatchPlanner.Edit(4, 1, 1)).isEmpty());
    }

    @Test
    void aStoreThatDriftedFromTheDocumentIsRestoredAndReportedForReimport() throws Exception {
        String before = ttl("ex:Dog rdfs:label \"Dgo\" .", "ex:Cat a owl:Class .");
        String after = ttl("ex:Dog rdfs:label \"Dog\" .", "ex:Cat a owl:Class .");
        load(before, RDFFormat.TURTLE);
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.add(Values.iri("http://example.org/Dog"), Values.iri("http://example.org/extra"), Values.literal("drift"),
                    Values.iri(GRAPH));
        }
        Model original = stored();
        Optional<TriplePatchPlanner.TriplePatch> plan = patch("turtle", before, after, new TriplePatchPlanner.Edit(3, 1, 1));

        assertEquals(TriplePatchExecutor.Outcome.RESTORED_AFTER_MISMATCH, executor.apply("p", plan.orElseThrow()));
        assertTrue(Models.isomorphic(stored(), original), "the store should be back to how it was");
    }

    @Test
    void closureQueryCoversEveryLevelUpToTheRequestedDepth() {
        String query = TriplePatchExecutor.closureQuery(GRAPH, Set.<IRI>of(Values.iri("http://example.org/A")), 2);
        assertTrue(query.contains("VALUES ?s { <http://example.org/A> }"));
        assertTrue(query.contains("?v2_n2 ?p2 ?o2"));
    }
}
