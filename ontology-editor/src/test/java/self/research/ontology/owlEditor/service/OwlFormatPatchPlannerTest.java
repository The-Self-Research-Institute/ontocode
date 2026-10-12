package self.research.ontology.owlEditor.service;

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
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwlFormatPatchPlannerTest {

    private static final String GRAPH = "urn:test:owl";
    private static final String HEAD = String.join("\n",
            "Prefix(:=<http://example.org/pets#>)",
            "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)",
            "Ontology(<http://example.org/pets>",
            "Declaration(Class(:Animal))",
            "Declaration(Class(:Dog))",
            "Declaration(Class(:Cat))",
            "Declaration(Class(:Mouse))",
            "Declaration(ObjectProperty(:chases))",
            "SubClassOf(:Dog :Animal)",
            "");

    private Repository repository;
    private TriplePatchExecutor executor;

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

    private static Path write(String body) throws Exception {
        Path file = Files.createTempFile("owlpatch-", ".ofn");
        Files.writeString(file, HEAD + body + "\n)\n");
        return file;
    }

    private static Model converted(Path file) throws Exception {
        Path rdf = OWLFormatConverter.convertToRDFXML(file);
        try (InputStream in = Files.newInputStream(rdf)) {
            return Rio.parse(in, GRAPH, RDFFormat.RDFXML);
        } finally {
            Files.deleteIfExists(rdf);
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

    private Optional<TriplePatchPlanner.TriplePatch> patchAndApply(String before, String after) throws Exception {
        Path oldFile = write(before);
        Path newFile = write(after);
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.add(converted(oldFile), Values.iri(GRAPH));
        }
        Optional<TriplePatchPlanner.TriplePatch> plan = new OwlFormatPatchPlanner().plan(oldFile, newFile, GRAPH);
        if (plan.isPresent()) {
            assertEquals(TriplePatchExecutor.Outcome.APPLIED, executor.apply("p", plan.get()));
            assertTrue(Models.isomorphic(stored(), converted(newFile)), "store should equal a full reimport");
        }
        return plan;
    }

    private static final String CAT_CHASES_MOUSE = "SubClassOf(:Cat ObjectSomeValuesFrom(:chases :Mouse))";

    @Test
    void addingALabelPatchesOnlyThatClassAndKeepsUntouchedRestrictions() throws Exception {
        Optional<TriplePatchPlanner.TriplePatch> plan = patchAndApply(CAT_CHASES_MOUSE,
                CAT_CHASES_MOUSE + "\nAnnotationAssertion(rdfs:label :Dog \"Dog\")");

        assertTrue(plan.isPresent());
        assertEquals(1, plan.get().subjects().size());
    }

    @Test
    void changingARestrictionReplacesItsTree() throws Exception {
        Optional<TriplePatchPlanner.TriplePatch> plan = patchAndApply(CAT_CHASES_MOUSE,
                "SubClassOf(:Cat ObjectSomeValuesFrom(:chases :Dog))");

        assertTrue(plan.isPresent());
        assertEquals(1, plan.get().treeDeleteDepth().size());
    }

    @Test
    void anUnchangedDocumentGivesAnEmptyPatch() throws Exception {
        Optional<TriplePatchPlanner.TriplePatch> plan = patchAndApply(CAT_CHASES_MOUSE, CAT_CHASES_MOUSE);

        assertTrue(plan.isPresent());
        assertTrue(plan.get().subjects().isEmpty());
    }
}
