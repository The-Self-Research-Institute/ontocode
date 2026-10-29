package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.io.WriterDocumentTarget;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralClassAxiomRoundTripTest {

    private static final String SOURCE = """
            Prefix(:=<http://example.org/zoo#>)
            Prefix(owl:=<http://www.w3.org/2002/07/owl#>)
            Ontology(<http://example.org/zoo>
            Declaration(Class(:Animal))
            Declaration(Class(:Bird))
            Declaration(Class(:Fish))
            Declaration(Class(:Swimmer))
            Declaration(Class(:Flyer))
            Declaration(Class(:Pet))
            Declaration(ObjectProperty(:eats))
            Declaration(ObjectProperty(:livesIn))
            Declaration(Class(:Water))
            Declaration(Class(:Seed))
            SubClassOf(:Bird :Animal)
            SubClassOf(:Fish :Animal)
            SubClassOf(ObjectIntersectionOf(ObjectSomeValuesFrom(:eats :Seed) ObjectSomeValuesFrom(:livesIn :Water)) :Pet)
            SubClassOf(ObjectUnionOf(:Bird :Fish) :Animal)
            SubClassOf(ObjectAllValuesFrom(:eats :Seed) ObjectSomeValuesFrom(:livesIn :Water))
            EquivalentClasses(ObjectSomeValuesFrom(:eats :Seed) ObjectIntersectionOf(:Bird ObjectSomeValuesFrom(:livesIn :Water)))
            DisjointClasses(ObjectSomeValuesFrom(:eats :Seed) ObjectSomeValuesFrom(:livesIn :Water))
            DisjointClasses(ObjectIntersectionOf(:Bird :Flyer) ObjectIntersectionOf(:Fish :Swimmer) :Pet)
            )
            """;

    private OWLOntology expected;
    private SailRepository repo;
    private RepositoryConnection conn;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        expected = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(SOURCE));
        repo = new SailRepository(new MemoryStore());
        conn = repo.getConnection();
    }

    @AfterEach
    void tearDown() {
        conn.close();
        repo.shutDown();
    }

    private static boolean isGeneral(OWLAxiom ax) {
        if (ax instanceof OWLSubClassOfAxiom s) {
            return s.getSubClass().isAnonymous();
        }
        if (ax instanceof OWLNaryClassAxiom n) {
            return n.classExpressions().allMatch(c -> c.isAnonymous());
        }
        return false;
    }

    private static Set<OWLAxiom> logical(OWLOntology o) {
        return o.getLogicalAxioms().stream().map(a -> (OWLAxiom) a.getAxiomWithoutAnnotations()).collect(Collectors.toSet());
    }

    private static long generalAxioms(OWLOntology o) {
        return o.getLogicalAxioms().stream().filter(GeneralClassAxiomRoundTripTest::isGeneral).count();
    }

    private void storeThroughTheEditorAddPath() throws Exception {
        Set<OWLAxiom> rest = new HashSet<>();
        Set<OWLAxiom> general = new HashSet<>();
        for (OWLAxiom ax : expected.getAxioms()) {
            (isGeneral(ax) ? general : rest).add(ax);
        }
        conn.prepareUpdate(OwlAxiomSparqlWriter.toInsertData(rest)).execute();
        for (OWLAxiom ax : general) {
            conn.prepareUpdate(OwlAxiomSparqlWriter.toInsertData(Set.of(ax))).execute();
        }
    }

    private void storeAsAnUploadedFile() throws Exception {
        StringWriter w = new StringWriter();
        expected.getOWLOntologyManager().saveOntology(expected, new TurtleDocumentFormat(), new WriterDocumentTarget(w));
        conn.add(new StringReader(w.toString()), "", RDFFormat.TURTLE);
    }

    private void store(String how) throws Exception {
        if ("added".equals(how)) {
            storeThroughTheEditorAddPath();
        } else {
            storeAsAnUploadedFile();
        }
    }

    private String exportTriples(RDFFormat format) {
        StringWriter w = new StringWriter();
        conn.export(Rio.createWriter(format, w));
        return w.toString();
    }

    private static OWLOntology load(String content) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(content));
    }

    private void assertSameGeneralAxioms(OWLOntology actual) {
        assertEquals(5, generalAxioms(expected));
        assertEquals(5, generalAxioms(actual));
        Set<OWLAxiom> missing = new HashSet<>(logical(expected));
        missing.removeAll(logical(actual));
        assertTrue(missing.isEmpty(), "missing: " + missing);
        Set<OWLAxiom> extra = new HashSet<>(logical(actual));
        extra.removeAll(logical(expected));
        assertTrue(extra.isEmpty(), "extra: " + extra);
    }

    @ParameterizedTest
    @ValueSource(strings = {"added", "uploaded"})
    void rdfXmlExport(String how) throws Exception {
        store(how);
        assertSameGeneralAxioms(load(exportTriples(RDFFormat.RDFXML)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"added", "uploaded"})
    void turtleExport(String how) throws Exception {
        store(how);
        assertSameGeneralAxioms(load(exportTriples(RDFFormat.TURTLE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"added", "uploaded"})
    void nTriplesExport(String how) throws Exception {
        store(how);
        assertSameGeneralAxioms(load(exportTriples(RDFFormat.NTRIPLES)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"added", "uploaded"})
    void jsonLdExport(String how) throws Exception {
        store(how);
        assertSameGeneralAxioms(load(exportTriples(RDFFormat.JSONLD)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owlxml", "functional", "manchester"})
    void owlApiBackedExports(String format) throws Exception {
        storeThroughTheEditorAddPath();
        String converted = new OntologyExporter(null, null, null)
                .convertRdfXmlToOwlApiFormat(exportTriples(RDFFormat.RDFXML), format);
        assertSameGeneralAxioms(load(converted));
    }

    @ParameterizedTest
    @ValueSource(strings = {"added", "uploaded"})
    void desktopLoadOfAnExportedFile(String how) throws Exception {
        store(how);
        Path file = dir.resolve("exported.owl");
        Files.writeString(file, exportTriples(RDFFormat.RDFXML), StandardCharsets.UTF_8);
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        assertSameGeneralAxioms(manager.loadOntologyFromOntologyDocument(file.toFile()));
        assertTrue(manager.ontologies().findFirst().orElseThrow().getAxiomCount(AxiomType.SUBCLASS_OF) > 0);
    }
}
