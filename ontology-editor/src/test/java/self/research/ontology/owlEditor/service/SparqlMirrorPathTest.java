package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlMirrorPathTest {

    static final String A = SparqlGraphUris.mainProjectGraph("projA");
    static final String A_PART = A + "/ns/x";
    static final String B = SparqlGraphUris.mainProjectGraph("projB");
    static final String P = "http://e/p";

    Repository store;
    ProjectRepoCache cache;
    SparqlDatasetService svc;
    ValueFactory vf = SimpleValueFactory.getInstance();

    @BeforeEach
    void setUp() {
        store = new SailRepository(new MemoryStore());
        store.init();
        add(A, "http://e/a1", "A-public");
        add(A_PART, "http://e/a2", "A-part");
        add(B, "http://e/b1", "B-secret");
        cache = new ProjectRepoCache();
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "maxProjects", 3);
        ReflectionTestUtils.setField(cache, "maxTriples", 1_000_000L);
        ReflectionTestUtils.setField(cache, "noCacheThreshold", 1_000_000L);
        svc = new SparqlDatasetService();
        ((Map<String, Repository>) ReflectionTestUtils.getField(svc, "perFileRepositories")).put("projA", store);
        ReflectionTestUtils.setField(svc, "projectRepoCache", cache);
        ReflectionTestUtils.setField(svc, "fusekiQueryEndpoint", "http://localhost:3030/ontocode/query");
        svc.registerPartitionGraphs("projA", List.of(A_PART));
    }

    @AfterEach
    void tearDown() {
        cache.evictAll();
        store.shutDown();
        SparqlQueryContext.clear();
    }

    void add(String graph, String subject, String value) {
        try (RepositoryConnection c = store.getConnection()) {
            c.add(vf.createIRI(subject), vf.createIRI(P), vf.createLiteral(value), vf.createIRI(graph));
        }
    }

    Set<String> column(SparqlUserQueryResult result, String var) {
        Set<String> out = new TreeSet<>();
        for (BindingSet row : ((SparqlUserQueryResult.Select) result).rows()) {
            Value v = row.getValue(var);
            if (v != null) {
                out.add(v.stringValue());
            }
        }
        return out;
    }

    void warmMirror() throws InterruptedException {
        svc.execUserQuery("projA", "ASK { ?s ?p ?o }", 10);
        for (int i = 0; i < 200 && cache.getSize() == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(1, cache.getSize());
    }

    @Test
    void publicQueriesRunAgainstTheRamMirrorAndStayInsideTheProject() throws Exception {
        warmMirror();
        long hitsBefore = cache.getHits();
        SparqlUserQueryResult r = svc.execUserQuery("projA", "SELECT ?o WHERE { ?s <" + P + "> ?o }", 1000);
        assertEquals(Set.of("A-public", "A-part"), column(r, "o"));
        assertEquals(Set.of(A), column(svc.execUserQuery("projA",
                "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }", 1000), "g"));
        assertThrows(SparqlQueryRejectedException.class, () -> svc.execUserQuery("projA",
                "SELECT * WHERE { GRAPH <" + B + "> { ?s ?p ?o } }", 1000));
        assertThrows(SparqlQueryRejectedException.class, () -> svc.execUserQuery("projA",
                "SELECT * FROM <" + B + "> WHERE { ?s ?p ?o }", 1000));
        assertTrue(cache.getHits() > hitsBefore);
    }

    @Test
    void askConstructAndDescribeUseTheMirrorToo() throws Exception {
        warmMirror();
        assertTrue(((SparqlUserQueryResult.Ask) svc.execUserQuery("projA",
                "ASK { ?s <" + P + "> \"A-part\" }", 10)).value());
        assertFalse(((SparqlUserQueryResult.Ask) svc.execUserQuery("projA",
                "ASK { ?s <" + P + "> \"B-secret\" }", 10)).value());
        SparqlUserQueryResult.Graph g = (SparqlUserQueryResult.Graph) svc.execUserQuery("projA",
                "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", 10);
        assertEquals(2, g.statements().size());
        SparqlUserQueryResult.Graph d = (SparqlUserQueryResult.Graph) svc.execUserQuery("projA",
                "DESCRIBE <http://e/a1>", 10);
        assertEquals(1, d.statements().size());
    }

    @Test
    void runnerExecuteGuardsAndScopesAnyRepositoryForReuse() {
        List<String> graphs = List.of(A);
        assertEquals(Set.of("A-public"), column(SparqlUserQueryRunner.execute(store, graphs,
                "SELECT ?o WHERE { ?s <" + P + "> ?o }", 100), "o"));
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlUserQueryRunner.execute(store, graphs,
                "SELECT ?o WHERE { GRAPH <" + A_PART + "> { ?s ?p ?o } }", 100));
    }

    @Test
    void standardBuiltInsAreNotRejected() {
        String[] calls = {"STR(?o)", "LANG(?o)", "DATATYPE(?o)", "BOUND(?o)", "IRI(\"http://x/y\")", "BNODE()", "RAND()",
                "NOW()", "UUID()", "STRUUID()", "STRLEN(?o)", "SUBSTR(?o, 1, 2)", "UCASE(?o)", "LCASE(?o)",
                "STRSTARTS(?o, \"A\")", "STRENDS(?o, \"A\")", "CONTAINS(?o, \"A\")", "STRBEFORE(?o, \"-\")",
                "STRAFTER(?o, \"-\")", "ENCODE_FOR_URI(?o)", "CONCAT(?o, \"x\")", "LANGMATCHES(\"en\", \"*\")",
                "REGEX(?o, \"A\")", "REPLACE(?o, \"A\", \"B\")", "ABS(1)", "ROUND(1.5)", "CEIL(1.5)", "FLOOR(1.5)",
                "YEAR(NOW())", "MONTH(NOW())", "DAY(NOW())", "HOURS(NOW())", "MINUTES(NOW())", "SECONDS(NOW())",
                "TIMEZONE(NOW())", "TZ(NOW())", "MD5(?o)", "SHA1(?o)", "SHA256(?o)", "SHA384(?o)", "SHA512(?o)",
                "COALESCE(?zz, 1)", "IF(true, 1, 2)", "STRLANG(?o, \"en\")", "STRDT(?o, <http://www.w3.org/2001/XMLSchema#string>)",
                "sameTerm(?o, ?o)", "isIRI(?o)", "isBLANK(?o)", "isLITERAL(?o)", "isNUMERIC(?o)", "?o IN (1, 2)",
                "<http://www.w3.org/2001/XMLSchema#integer>(\"3\")", "<http://www.w3.org/2005/xpath-functions#upper-case>(?o)",
                "<http://www.w3.org/2005/xpath-functions/math#sqrt>(4)"};
        for (String call : calls) {
            String q = "SELECT (" + call + " AS ?x) WHERE { ?s <" + P + "> ?o }";
            SparqlQueryGuard.check(q, Set.of(A));
        }
        SparqlQueryGuard.check("SELECT (COUNT(*) AS ?c) (SAMPLE(?o) AS ?s1) (GROUP_CONCAT(?o; separator=\",\") AS ?g) "
                + "(MIN(?o) AS ?mi) (MAX(?o) AS ?ma) (AVG(1) AS ?av) (SUM(1) AS ?su) WHERE { ?s <" + P + "> ?o }", Set.of(A));
    }
}
