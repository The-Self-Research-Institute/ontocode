package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlIsolationTest {

    static final String A = SparqlGraphUris.mainProjectGraph("projA");
    static final String A_PART = A + "/ns/x";
    static final String B = SparqlGraphUris.mainProjectGraph("projB");
    static final String DRAFT_A = SparqlGraphUris.userDraftGraph("projA", "userA");
    static final String DRAFT_B = SparqlGraphUris.userDraftGraph("projA", "userB");
    static final String P = "http://e/p";

    Repository repo;
    ValueFactory vf = SimpleValueFactory.getInstance();

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        add(A, "http://e/a1", "A-public");
        add(A_PART, "http://e/a2", "A-part");
        add(DRAFT_A, "http://e/d1", "draftA");
        add(DRAFT_B, "http://e/d2", "draftB");
        add(B, "http://e/b1", "B-secret");
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
        SparqlQueryContext.clear();
    }

    void add(String graph, String subject, String value) {
        try (RepositoryConnection c = repo.getConnection()) {
            c.add(vf.createIRI(subject), vf.createIRI(P), vf.createLiteral(value), vf.createIRI(graph));
        }
    }

    SparqlQueryScope publicA() {
        return SparqlQueryScope.publicScope("projA", A, List.of(A_PART));
    }

    SparqlQueryScope draftA() {
        return SparqlQueryScope.draftScope("projA", "userA");
    }

    SparqlUserQueryResult run(Repository r, SparqlQueryScope scope, String query) {
        SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(query, new LinkedHashSet<>(scope.graphs()));
        try (RepositoryConnection c = r.getConnection()) {
            return SparqlUserQueryRunner.run(c, checked, scope.graphs(), 100000);
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

    void assertRejected(SparqlQueryScope scope, String query) {
        assertThrows(SparqlQueryRejectedException.class, () -> run(repo, scope, query), query);
    }

    @Test
    void publicScopeSeesBaseAndPartitionOnly() {
        assertEquals(Set.of("A-public", "A-part"),
                column(run(repo, publicA(), "SELECT ?o WHERE { ?s <" + P + "> ?o }"), "o"));
    }

    @Test
    void draftScopeSeesOnlyOwnDraft() {
        assertEquals(Set.of("draftA"), column(run(repo, draftA(), "SELECT ?o WHERE { ?s <" + P + "> ?o }"), "o"));
    }

    @Test
    void graphVariableListsOnlyAuthorizedGraphs() {
        assertEquals(Set.of(A, A_PART),
                column(run(repo, publicA(), "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }"), "g"));
        assertEquals(Set.of(DRAFT_A),
                column(run(repo, draftA(), "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }"), "g"));
    }

    @Test
    void constantAuthorizedGraphIsAllowed() {
        assertEquals(Set.of("A-public"),
                column(run(repo, publicA(), "SELECT ?o WHERE { GRAPH <" + A + "> { ?s ?p ?o } }"), "o"));
    }

    @Test
    void projectBScopeNeverSeesProjectAData() {
        SparqlQueryScope b = SparqlQueryScope.publicScope("projB", B, List.of());
        assertEquals(Set.of("B-secret"), column(run(repo, b, "SELECT ?o WHERE { ?s <" + P + "> ?o }"), "o"));
        assertRejected(b, "SELECT ?o WHERE { GRAPH <" + A + "> { ?s ?p ?o } }");
        assertRejected(b, "SELECT ?o WHERE { GRAPH <" + DRAFT_A + "> { ?s ?p ?o } }");
    }

    @Test
    void projectAScopeNeverSeesProjectB() {
        assertRejected(publicA(), "SELECT ?o WHERE { GRAPH <" + B + "> { ?s ?p ?o } }");
        assertFalse(column(run(repo, publicA(), "SELECT ?o WHERE { ?s ?p ?o }"), "o").contains("B-secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "SELECT * FROM NAMED <http://ontocode.org/project/projB> WHERE { GRAPH ?g { ?s ?p ?o } }",
            "SELECT * \\u0046ROM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "SELECT * fRoM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "select * from named <http://ontocode.org/project/projB> where { ?s ?p ?o }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/draft/projA/userB> { ?s ?p ?o } }",
            "SELECT * WHERE { { SELECT ?s WHERE { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } } } }",
            "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { GRAPH <http://ontocode.org/project/projB> { ?s ?p2 ?o2 } } }",
            "SELECT * WHERE { ?s ?p ?o FILTER NOT EXISTS { GRAPH <http://ontocode.org/project/projB> { ?s ?p2 ?o2 } } }",
            "SELECT * WHERE { ?s ?p ?o OPTIONAL { GRAPH <http://ontocode.org/project/projB> { ?s ?p2 ?o2 } } }",
            "SELECT * WHERE { { ?s ?p ?o } UNION { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } } }",
            "SELECT * WHERE { ?s ?p ?o MINUS { GRAPH <http://ontocode.org/project/projB> { ?s ?p ?o } } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projB> { ?s <http://e/p>+ ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projB> { ?s <http://e/p>* ?o } }",
            "BASE <http://ontocode.org/project/> SELECT * WHERE { GRAPH <projB> { ?s ?p ?o } }",
            "PREFIX g: <http://ontocode.org/project/> SELECT * WHERE { GRAPH g:projB { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/projA/../projB> { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <http://ontocode.org/project/proj%42> { ?s ?p ?o } }",
            "SELECT * WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }",
            "SELECT * WHERE { SERVICE SILENT <http://example.org/sparql> { ?s ?p ?o } }",
            "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { SERVICE <http://example.org/sparql> { ?s ?p ?o } } }",
            "SELECT * WHERE { { SELECT ?s WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } } } }",
            "SELECT (<java:java.lang.Math.abs>(1) AS ?x) WHERE { ?s ?p ?o }",
            "SELECT * WHERE { ?s ?p ?o FILTER(<http://example.org/fn/custom>(?o)) }",
            "SELECT * WHERE { ?s <java:com.example.Prop> ?o }",
            "SELECT * WHERE { ?s <http://jena.apache.org/ARQ/list#member> ?o }",
            "SELECT * WHERE { ?s <http://jena.apache.org/text#query> ?o }",
            "SELECT * WHERE { ?s <http://jena.hpl.hp.com/ARQ/property#bag> ?o }",
            "SELECT (<http://jena.apache.org/ARQ/function#sha1sum>(?o) AS ?x) WHERE { ?s ?p ?o }",
            "SELECT * WHERE { ?s ?p ?o } \n# harmless comment\n VALUES ?x { 1 } FROM <http://e/g>",
            "ASK FROM <http://ontocode.org/project/projB> { ?s ?p ?o }",
            "CONSTRUCT { ?s ?p ?o } FROM <http://ontocode.org/project/projB> WHERE { ?s ?p ?o }",
            "DESCRIBE <http://e/b1> FROM <http://ontocode.org/project/projB>",
            "INSERT DATA { <http://e/x> <http://e/p> 1 }",
            "DELETE WHERE { ?s ?p ?o }",
            "LOAD <http://example.org/data.ttl>",
            "CLEAR GRAPH <http://ontocode.org/project/projB>",
            "DROP ALL",
            "this is not sparql"
    })
    void hostileQueriesAreRejected(String query) {
        assertRejected(publicA(), query);
        assertRejected(draftA(), query);
    }

    @Test
    void rejectionMessagesArePlain() {
        SparqlQueryRejectedException from = assertThrows(SparqlQueryRejectedException.class,
                () -> run(repo, publicA(), "SELECT * FROM <http://x/g> WHERE { ?s ?p ?o }"));
        assertTrue(from.getMessage().startsWith("FROM and FROM NAMED are not supported"));
        SparqlQueryRejectedException update = assertThrows(SparqlQueryRejectedException.class,
                () -> run(repo, publicA(), "INSERT DATA { <http://x/a> <http://x/p> 1 }"));
        assertTrue(update.getMessage().contains("UPDATE"));
    }

    @Test
    void keywordsInsideCommentsStringsAndPrefixesAreNotMistakenForClauses() {
        String q = "# FROM <http://ontocode.org/project/projB>\n"
                + "PREFIX from: <http://e/from#> PREFIX service: <http://e/service#>\n"
                + "SELECT ?o WHERE { ?s <" + P + "> ?o . FILTER(?o != \"FROM <x> SERVICE <y> GRAPH\") }";
        assertEquals(Set.of("A-public", "A-part"), column(run(repo, publicA(), q), "o"));
    }

    @Test
    void standardFunctionsRemainAvailable() {
        String q = "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> PREFIX fn: <http://www.w3.org/2005/xpath-functions#> "
                + "SELECT ?o (xsd:integer(\"7\") AS ?n) (STRLEN(?o) AS ?l) (ABS(-1) AS ?abs) (ROUND(1.5) AS ?r) (CEIL(1.2) AS ?c) "
                + "(ENCODE_FOR_URI(?o) AS ?e) (REPLACE(?o, \"A\", \"B\") AS ?rep) (MD5(?o) AS ?h) (SHA256(?o) AS ?h2) "
                + "(UCASE(?o) AS ?u) (STRBEFORE(?o, \"-\") AS ?sb) (COALESCE(?zz, 1) AS ?co) (IF(true, 1, 2) AS ?i) "
                + "(fn:upper-case(?o) AS ?fu) "
                + "WHERE { ?s <" + P + "> ?o FILTER(REGEX(?o, \"^A\")) } ORDER BY ?o";
        assertEquals(Set.of("A-part", "A-public"), column(run(repo, publicA(), q), "o"));
    }

    @Test
    void datasetAloneBlocksCrossGraphReadsEvenWhenTheGuardIsBypassed() {
        List<String> graphs = publicA().graphs();
        try (RepositoryConnection c = repo.getConnection()) {
            SparqlQueryGuard.Checked bypass = new SparqlQueryGuard.Checked(null, SparqlQueryGuard.Kind.SELECT,
                    "SELECT ?o WHERE { GRAPH <" + B + "> { ?s ?p ?o } }");
            assertEquals(Set.of(), column(SparqlUserQueryRunner.run(c, bypass, graphs, 10), "o"));
            SparqlQueryGuard.Checked listing = new SparqlQueryGuard.Checked(null, SparqlQueryGuard.Kind.SELECT,
                    "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }");
            assertEquals(Set.of(A, A_PART), column(SparqlUserQueryRunner.run(c, listing, graphs, 10), "g"));
            SparqlQueryGuard.Checked values = new SparqlQueryGuard.Checked(null, SparqlQueryGuard.Kind.SELECT,
                    "SELECT ?o WHERE { VALUES ?g { <" + B + "> <" + DRAFT_B + "> } GRAPH ?g { ?s ?p ?o } }");
            assertEquals(Set.of(), column(SparqlUserQueryRunner.run(c, values, graphs, 10), "o"));
            SparqlQueryGuard.Checked exists = new SparqlQueryGuard.Checked(null, SparqlQueryGuard.Kind.SELECT,
                    "SELECT ?o WHERE { ?s ?p ?o FILTER EXISTS { GRAPH ?g { ?x ?y \"B-secret\" } } }");
            assertEquals(Set.of(), column(SparqlUserQueryRunner.run(c, exists, graphs, 10), "o"));
            SparqlQueryGuard.Checked sub = new SparqlQueryGuard.Checked(null, SparqlQueryGuard.Kind.SELECT,
                    "SELECT ?o WHERE { { SELECT ?o WHERE { GRAPH ?g { ?s ?p ?o } } } FILTER(?o = \"B-secret\") }");
            assertEquals(Set.of(), column(SparqlUserQueryRunner.run(c, sub, graphs, 10), "o"));
            SparqlQueryGuard.Checked from = new SparqlQueryGuard.Checked(null, SparqlQueryGuard.Kind.SELECT,
                    "SELECT ?o FROM <" + B + "> WHERE { ?s ?p ?o }");
            assertEquals(Set.of("A-public", "A-part"), column(SparqlUserQueryRunner.run(c, from, graphs, 10), "o"));
        }
    }

    @Test
    void scopeAssertionRefusesGraphsOfOtherProjectsOrUsers() {
        assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryScope.publicScope("projA", B, List.of()));
        assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryScope.publicScope("projA", A, List.of(B)));
        assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlQueryScope.publicScope("projA", A, List.of(DRAFT_A)));
        assertThrows(SparqlQueryRejectedException.class,
                () -> new SparqlQueryScope("projA", true, List.of(DRAFT_B)).assertBelongsToProject("projA", "userA"));
        assertThrows(SparqlQueryRejectedException.class,
                () -> publicA().assertBelongsToProject("projB", null));
        assertThrows(SparqlQueryRejectedException.class,
                () -> new SparqlQueryScope("projA", false, List.of()).assertBelongsToProject("projA", null));
    }

    SparqlDatasetService service(Repository sharedRepo, boolean draftReady) {
        SparqlDatasetService svc = new SparqlDatasetService();
        Map<String, Repository> perFile = (Map<String, Repository>) ReflectionTestUtils.getField(svc, "perFileRepositories");
        perFile.put("projA", sharedRepo);
        perFile.put("projB", sharedRepo);
        DraftSessionRepository sessions = Mockito.mock(DraftSessionRepository.class);
        DraftSession session = new DraftSession();
        session.setCopyStatus(draftReady ? DraftCopyStatus.READY : DraftCopyStatus.COPYING);
        Mockito.when(sessions.findByProjectIdAndUserId("projA", "userA")).thenReturn(Optional.of(session));
        ReflectionTestUtils.setField(svc, "draftSessionRepository", sessions);
        ReflectionTestUtils.setField(svc, "fusekiQueryEndpoint", "http://localhost:3030/ontocode/query");
        svc.registerPartitionGraphs("projA", List.of(A_PART));
        return svc;
    }

    @Test
    void servicePublicPathUsesProjectGraphsFromTheUrlProject() {
        SparqlDatasetService svc = service(repo, true);
        SparqlUserQueryResult a = svc.execUserQuery("projA", "SELECT ?o WHERE { ?s <" + P + "> ?o }", 1000);
        assertEquals(Set.of("A-public", "A-part"), column(a, "o"));
        SparqlUserQueryResult b = svc.execUserQuery("projB", "SELECT ?o WHERE { ?s <" + P + "> ?o }", 1000);
        assertEquals(Set.of("B-secret"), column(b, "o"));
        assertThrows(SparqlQueryRejectedException.class,
                () -> svc.execUserQuery("projB", "SELECT ?o WHERE { GRAPH <" + A + "> { ?s ?p ?o } }", 1000));
        assertThrows(SparqlQueryRejectedException.class,
                () -> svc.execUserQuery("projA", "SELECT * FROM <" + B + "> WHERE { ?s ?p ?o }", 1000));
    }

    @Test
    void serviceDraftPathSeesOnlyTheCallersDraft() {
        SparqlDatasetService svc = service(repo, true);
        SparqlQueryContext.setUserId("userA");
        SparqlQueryContext.setWantsDraft(true);
        assertEquals(Set.of("draftA"),
                column(svc.execUserQuery("projA", "SELECT ?o WHERE { ?s <" + P + "> ?o }", 1000), "o"));
        assertThrows(SparqlQueryRejectedException.class,
                () -> svc.execUserQuery("projA", "SELECT ?o WHERE { GRAPH <" + A + "> { ?s ?p ?o } }", 1000));
        assertThrows(SparqlQueryRejectedException.class,
                () -> svc.execUserQuery("projA", "SELECT ?o WHERE { GRAPH <" + DRAFT_B + "> { ?s ?p ?o } }", 1000));
    }

    @Test
    void serviceExplainsWhenTheDraftCopyIsNotReady() {
        SparqlDatasetService svc = service(repo, false);
        SparqlQueryContext.setUserId("userA");
        SparqlQueryContext.setWantsDraft(true);
        SparqlQueryRejectedException e = assertThrows(SparqlQueryRejectedException.class,
                () -> svc.execUserQuery("projA", "SELECT * WHERE { ?s ?p ?o }", 1000));
        assertTrue(e.getMessage().contains("draft copy is not ready"));
    }

    @Test
    void mirrorPathUsesOnlyTheMirroredBaseGraph() {
        Repository mirror = new SailRepository(new MemoryStore());
        mirror.init();
        try (RepositoryConnection c = mirror.getConnection()) {
            IRI base = vf.createIRI(A);
            c.add(vf.createIRI("http://e/a1"), vf.createIRI(P), vf.createLiteral("A-public"), base);
            c.add(vf.createIRI("http://e/a2"), vf.createIRI(P), vf.createLiteral("A-part"), base);
            c.add(vf.createIRI("http://e/b1"), vf.createIRI(P), vf.createLiteral("B-secret"), vf.createIRI(B));
        }
        SparqlDatasetService svc = service(repo, true);
        SparqlQueryScope scope = publicA();
        SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(
                "SELECT DISTINCT ?g ?o WHERE { GRAPH ?g { ?s ?p ?o } }", new LinkedHashSet<>(scope.graphs()));
        SparqlUserQueryResult result = svc.execUserQueryInGraphs(mirror, scope, List.of(A), checked, 1000);
        assertEquals(Set.of(A), column(result, "g"));
        assertEquals(Set.of("A-public", "A-part"), column(result, "o"));
        assertThrows(SparqlQueryRejectedException.class,
                () -> svc.execUserQueryInGraphs(mirror, scope, List.of(B), checked, 1000));
    }

    @Test
    void extensionPointRefusesGraphsOutsideTheScope() {
        SparqlDatasetService svc = service(repo, true);
        SparqlQueryScope scope = publicA();
        SparqlQueryGuard.Checked checked = SparqlQueryGuard.check("SELECT * WHERE { ?s ?p ?o }", Set.copyOf(scope.graphs()));
        List<String> wide = new ArrayList<>(scope.graphs());
        wide.add(DRAFT_B);
        assertThrows(SparqlQueryRejectedException.class,
                () -> svc.execUserQueryInGraphs(repo, scope, wide, checked, 10));
    }
}
