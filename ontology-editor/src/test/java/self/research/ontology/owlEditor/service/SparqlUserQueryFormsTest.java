package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlUserQueryFormsTest {

    static final String A = SparqlGraphUris.mainProjectGraph("projA");
    static final String B = SparqlGraphUris.mainProjectGraph("projB");
    static final String E = "http://e/";

    Repository repo;
    ValueFactory vf = SimpleValueFactory.getInstance();

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        IRI g = vf.createIRI(A);
        IRI s1 = vf.createIRI(E + "s1");
        IRI s2 = vf.createIRI(E + "s2");
        BNode b1 = vf.createBNode("b1");
        BNode b2 = vf.createBNode("b2");
        BNode b3 = vf.createBNode("b3");
        BNode b4 = vf.createBNode("b4");
        try (RepositoryConnection c = repo.getConnection()) {
            c.add(s1, vf.createIRI(E + "p"), vf.createLiteral("x"), g);
            c.add(s1, vf.createIRI(E + "friend"), b1, g);
            c.add(b1, vf.createIRI(E + "name"), vf.createLiteral("Bob"), g);
            c.add(b1, vf.createIRI(E + "addr"), b2, g);
            c.add(b2, vf.createIRI(E + "city"), vf.createLiteral("Paris"), g);
            c.add(b2, vf.createIRI(E + "next"), b3, g);
            c.add(b3, vf.createIRI(E + "next"), b4, g);
            c.add(b4, vf.createIRI(E + "deep"), vf.createLiteral("too-deep"), g);
            c.add(s2, vf.createIRI(E + "p"), vf.createLiteral("y"), g);
            c.add(vf.createIRI(E + "other"), vf.createIRI(E + "refersTo"), s1, g);
            c.add(s1, vf.createIRI(E + "p"), vf.createLiteral("SECRET"), vf.createIRI(B));
        }
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    SparqlUserQueryResult run(String query, int cap) {
        SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(query, Set.of(A));
        try (RepositoryConnection c = repo.getConnection()) {
            return SparqlUserQueryRunner.run(c, checked, List.of(A), cap);
        }
    }

    Set<String> literals(SparqlUserQueryResult r) {
        return ((SparqlUserQueryResult.Graph) r).statements().stream()
                .filter(s -> s.getObject().isLiteral())
                .map(s -> s.getObject().stringValue())
                .collect(Collectors.toSet());
    }

    @Test
    void selectReturnsOrderedVariablesAndRows() {
        SparqlUserQueryResult.Select r = (SparqlUserQueryResult.Select) run(
                "SELECT ?o ?s WHERE { ?s <" + E + "p> ?o } ORDER BY ?o", 10);
        assertEquals(List.of("o", "s"), r.vars());
        assertEquals(2, r.rows().size());
    }

    @Test
    void askReturnsBoolean() {
        assertTrue(((SparqlUserQueryResult.Ask) run("ASK { ?s <" + E + "p> \"x\" }", 10)).value());
        assertFalse(((SparqlUserQueryResult.Ask) run("ASK { ?s <" + E + "p> \"SECRET\" }", 10)).value());
        assertFalse(((SparqlUserQueryResult.Ask) run("ASK { GRAPH ?g { ?s <" + E + "p> \"SECRET\" } }", 10)).value());
    }

    @Test
    void constructReturnsTriplesFromAuthorizedGraphsOnly() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(
                "CONSTRUCT { ?s <" + E + "p> ?o } WHERE { ?s <" + E + "p> ?o }", 100);
        assertEquals(Set.of("x", "y"), literals(g));
        assertFalse(g.truncated());
    }

    @Test
    void constructIsCappedBeforeSerializationAndStaysValid() {
        for (RDFFormat format : List.of(RDFFormat.TURTLE, RDFFormat.JSONLD, RDFFormat.RDFXML, RDFFormat.NTRIPLES)) {
            SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(
                    "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", 3);
            assertTrue(g.truncated());
            assertEquals(3, g.statements().size());
            String text = SparqlGraphSerializer.serialize(g.statements(), g.namespaces(), format);
            assertEquals(3, parse(text, format).size(), format.getName());
        }
    }

    @Test
    void noTruncationWhenResultFitsExactlyInTheCap() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(
                "CONSTRUCT { ?s <" + E + "p> ?o } WHERE { ?s <" + E + "p> ?o }", 2);
        assertEquals(2, g.statements().size());
        assertFalse(g.truncated());
    }

    @Test
    void allFormatsRoundTripThroughRio() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(
                "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", 1000);
        for (RDFFormat format : List.of(RDFFormat.TURTLE, RDFFormat.JSONLD, RDFFormat.RDFXML, RDFFormat.NTRIPLES)) {
            String text = SparqlGraphSerializer.serialize(g.statements(), g.namespaces(), format);
            assertEquals(g.statements().size(), parse(text, format).size(), format.getName());
        }
    }

    @Test
    void formatNamesAndAcceptHeadersMapToRioFormats() {
        assertEquals(RDFFormat.TURTLE, SparqlGraphSerializer.formatByName(null));
        assertEquals(RDFFormat.JSONLD, SparqlGraphSerializer.formatByName("jsonld"));
        assertEquals(RDFFormat.RDFXML, SparqlGraphSerializer.formatByName("rdfxml"));
        assertEquals(RDFFormat.NTRIPLES, SparqlGraphSerializer.formatByName("ntriples"));
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlGraphSerializer.formatByName("csv"));
        assertEquals(RDFFormat.JSONLD, SparqlGraphSerializer.formatByAccept("application/ld+json"));
        assertEquals(RDFFormat.RDFXML, SparqlGraphSerializer.formatByAccept("text/html, application/rdf+xml;q=0.9"));
        assertEquals(RDFFormat.NTRIPLES, SparqlGraphSerializer.formatByAccept("application/n-triples"));
        assertEquals(RDFFormat.TURTLE, SparqlGraphSerializer.formatByAccept("application/json, */*"));
        assertEquals(RDFFormat.TURTLE, SparqlGraphSerializer.formatByAccept(null));
    }

    @Test
    void describeIriGivesSubjectTriplesAndBlankNodeClosureToTheDepthCap() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run("DESCRIBE <" + E + "s1>", 1000);
        Set<String> lits = literals(g);
        assertTrue(lits.containsAll(Set.of("x", "Bob", "Paris")));
        assertFalse(lits.contains("SECRET"));
        assertFalse(lits.contains("y"));
        assertFalse(lits.contains("too-deep"));
        assertTrue(g.statements().stream().noneMatch(s -> s.getPredicate().stringValue().equals(E + "refersTo")));
    }

    @Test
    void describeVariablesUsesTheWherePattern() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(
                "DESCRIBE ?x WHERE { ?x <" + E + "p> \"y\" }", 1000);
        assertEquals(Set.of("y"), literals(g));
        assertEquals(1, g.statements().size());
    }

    @Test
    void describeMixesConstantsAndVariablesAndIgnoresCommentsAndEscapedKeywords() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(
                "# DESCRIBE <" + E + "s1>\n\\u0044ESCRIBE <" + E + "s2> ?x \\u0057HERE { ?x <" + E + "p> \"x\" }", 1000);
        assertTrue(literals(g).containsAll(Set.of("x", "y", "Bob")));
    }

    @Test
    void describeNeverReadsOtherGraphsEvenWhenAskedByName() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(
                "DESCRIBE ?x WHERE { ?x <" + E + "p> ?v . FILTER(?v = \"SECRET\") }", 1000);
        assertTrue(g.statements().isEmpty());
    }

    @Test
    void describeIsCapped() {
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run("DESCRIBE <" + E + "s1>", 2);
        assertTrue(g.truncated());
        assertEquals(2, g.statements().size());
    }

    @Test
    void describeWithFromIsRejected() {
        assertThrows(SparqlQueryRejectedException.class,
                () -> run("DESCRIBE <" + E + "s1> FROM <" + B + ">", 10));
    }

    @Test
    void describeConstructQueryStaysInsideTheRestrictedDataset() {
        String q = SparqlUserQueryRunner.describeConstruct(List.of(vf.createIRI(E + "s1")));
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) run(q, 1000);
        assertFalse(literals(g).contains("SECRET"));
    }

    @Test
    void updateIsRejectedByTheQueryEndpoint() {
        assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryGuard.check("INSERT DATA { <http://e/a> <http://e/p> 1 }", Set.of(A)));
        assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryGuard.check("DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }", Set.of(A)));
    }

    @Test
    void dispatchKindFollowsTheParsedQueryNotTheText() {
        assertEquals(SparqlQueryGuard.Kind.SELECT, SparqlQueryGuard.check("# ASK\nSELECT * { ?s ?p ?o }", Set.of(A)).kind());
        assertEquals(SparqlQueryGuard.Kind.ASK, SparqlQueryGuard.check("PREFIX select: <http://x/> ASK { ?s ?p ?o }", Set.of(A)).kind());
        assertEquals(SparqlQueryGuard.Kind.CONSTRUCT, SparqlQueryGuard.check("CONSTRUCT WHERE { ?s ?p ?o }", Set.of(A)).kind());
        assertEquals(SparqlQueryGuard.Kind.DESCRIBE, SparqlQueryGuard.check("DESCRIBE <http://x/a>", Set.of(A)).kind());
    }

    @Test
    void surrogateSelectKeepsThePatternAndDropsTheDescribeClause() {
        String sur = SparqlUserQueryRunner.surrogateSelect("PREFIX e: <http://e/> DESCRIBE ?x <http://e/a> WHERE { ?x e:p 1 } LIMIT 3");
        assertTrue(sur.startsWith("PREFIX e: <http://e/> SELECT * "));
        assertTrue(sur.contains("WHERE { ?x e:p 1 } LIMIT 3"));
        assertFalse(sur.toUpperCase().contains("DESCRIBE"));
    }

    private Set<Statement> parse(String text, RDFFormat format) {
        try {
            return new LinkedHashSet<>(List.copyOf(Rio.parse(new StringReader(text), "http://base/", format)));
        } catch (Exception e) {
            throw new AssertionError("not parseable as " + format.getName(), e);
        }
    }
}
