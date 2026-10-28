package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlUpdateScoperTest {

    private static final String G = "http://ontocode.org/project/p1";
    private static final String DRAFT = "http://ontocode.org/draft/p1/u1";
    private static final String OTHER = "http://ontocode.org/project/p2";
    private static final String PART = "http://ontocode.org/project/p1/ns/part";
    private static final String X = "http://x/";

    private final SimpleValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repo;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private String scope(String update) {
        return SparqlUpdateScoper.scope(update, G, List.of(), null, SparqlUpdateLimits.defaults());
    }

    private String scopeTo(String graph, String update) {
        return SparqlUpdateScoper.scope(update, graph, List.of(), null, SparqlUpdateLimits.defaults());
    }

    private void run(String scoped) {
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate(scoped).execute();
        }
    }

    private void apply(String update) {
        run(scope(update));
    }

    private void seed(String graph, String update) {
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate("INSERT DATA { GRAPH <" + graph + "> { " + update + " } }").execute();
        }
    }

    private long size(String graph) {
        try (RepositoryConnection c = repo.getConnection()) {
            return c.size(vf.createIRI(graph));
        }
    }

    private long defaultSize() {
        try (RepositoryConnection c = repo.getConnection()) {
            return c.size((org.eclipse.rdf4j.model.Resource) null);
        }
    }

    private boolean has(String graph, String s, String p, String o) {
        try (RepositoryConnection c = repo.getConnection()) {
            return c.hasStatement(vf.createIRI(s), vf.createIRI(p), vf.createIRI(o), false, vf.createIRI(graph));
        }
    }

    private SparqlQueryRejectedException rejected(String update) {
        return assertThrows(SparqlQueryRejectedException.class, () -> scope(update));
    }

    @Test
    void insertDataLandsInTheProjectGraphNotTheDefaultGraph() {
        apply("INSERT DATA { <http://x/A> <http://x/p> <http://x/B> }");
        assertTrue(has(G, X + "A", X + "p", X + "B"));
        assertEquals(0, defaultSize());
    }

    @Test
    void singleLinePrologueBeforeTheOperationStillTargetsTheGraph() {
        apply("PREFIX ex: <http://x/> PREFIX owl: <http://www.w3.org/2002/07/owl#> INSERT DATA { ex:A owl:sameAs ex:B }");
        assertTrue(has(G, X + "A", "http://www.w3.org/2002/07/owl#sameAs", X + "B"));
        assertEquals(0, defaultSize());
    }

    @Test
    void singleLinePrologueBeforeDeleteDataStillTargetsTheGraph() {
        seed(G, "<http://x/A> <http://x/p> <http://x/B>");
        apply("prefix ex: <http://x/> BASE <http://x/> DELETE DATA { ex:A <http://x/p> ex:B }");
        assertEquals(0, size(G));
    }

    @Test
    void multiLinePrologueCommentsAndBaseWork() {
        apply("# leading comment\nPREFIX ex: <http://x/>\n# another\nBASE <http://x/base/>\n"
                + "INSERT DATA { ex:A ex:p <rel> } # trailing\n");
        assertTrue(has(G, X + "A", X + "p", X + "base/rel"));
    }

    @Test
    void unicodeEscapesAreResolvedByTheParserNotByText() {
        apply("INSERT DATA { <http://x/\\u0041> <http://x/p> <http://x/B> }");
        assertTrue(has(G, X + "A", X + "p", X + "B"));
    }

    @Test
    void aSemicolonOrBraceInsideATextValueDoesNotSplitTheUpdate() {
        apply("INSERT DATA { <http://x/A> <http://x/p> \"a;b}\" } ; INSERT DATA { <http://x/A> <http://x/q> \"{c;\" }");
        assertEquals(2, size(G));
    }

    @Test
    void theWordUsingInsideATextValueIsJustText() {
        apply("INSERT { _:b1 <http://x/p> \"measured using a probe\" . _:b1 a <http://x/C> . } WHERE { }");
        assertEquals(2, size(G));
    }

    @Test
    void blankNodesAndListsInInsertDataKeepTheirShape() {
        apply("INSERT DATA { <http://x/A> <http://x/p> [ a <http://x/R> ; <http://x/q> ( <http://x/1> <http://x/2> ) ] }");
        try (RepositoryConnection c = repo.getConnection()) {
            assertTrue(c.prepareBooleanQuery("ASK { GRAPH <" + G + "> { <http://x/A> <http://x/p> ?b . ?b a <http://x/R> ; "
                    + "<http://x/q> ?l . ?l <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/1> ; "
                    + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#rest>/<http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/2> } }").evaluate());
        }
        assertEquals(0, defaultSize());
    }

    @Test
    void deleteDataRemovesOnlyFromTheTargetGraph() {
        seed(G, "<http://x/A> <http://x/p> <http://x/B>");
        seed(OTHER, "<http://x/A> <http://x/p> <http://x/B>");
        apply("DELETE DATA { <http://x/A> <http://x/p> <http://x/B> }");
        assertEquals(0, size(G));
        assertEquals(1, size(OTHER));
    }

    @Test
    void deleteInsertWhereReadsAndWritesOnlyTheTargetGraph() {
        seed(G, "<http://x/O> a <http://x/T>");
        seed(OTHER, "<http://x/O> a <http://x/Z>");
        apply("DELETE { <http://x/O> a ?t } INSERT { <http://x/N> a ?t } WHERE { <http://x/O> a ?t }");
        assertTrue(has(G, X + "N", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", X + "T"));
        assertFalse(has(G, X + "O", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", X + "T"));
        assertEquals(1, size(OTHER));
        assertEquals(0, defaultSize());
    }

    @Test
    void insertWhereWithTemplateBlankNodesAndFilterNotExists() {
        seed(G, "<http://x/A> a <http://x/C> . <http://x/B> a <http://x/C> . <http://x/B> <http://x/done> <http://x/yes>");
        apply("INSERT { ?c <http://x/sub> [ a <http://x/Restriction> ; <http://x/on> ?c ] } "
                + "WHERE { ?c a <http://x/C> FILTER NOT EXISTS { ?c <http://x/done> ?d } }");
        try (RepositoryConnection c = repo.getConnection()) {
            assertTrue(c.prepareBooleanQuery("ASK { GRAPH <" + G + "> { <http://x/A> <http://x/sub> ?b . ?b a <http://x/Restriction> } }").evaluate());
            assertFalse(c.prepareBooleanQuery("ASK { GRAPH <" + G + "> { <http://x/B> <http://x/sub> ?b } }").evaluate());
        }
    }

    @Test
    void listVariablesUsedInTheTemplateAreNotCollapsedOutOfTheWherePattern() {
        seed(G, "<http://x/C> <http://x/u> _:l . _:l <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/A> ; "
                + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> _:m . _:m <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/B> ; "
                + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> <http://www.w3.org/1999/02/22-rdf-syntax-ns#nil>");
        apply("DELETE { <http://x/C> <http://x/u> ?l . ?l <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/A> ; "
                + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> ?m . ?m <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/B> ; "
                + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> <http://www.w3.org/1999/02/22-rdf-syntax-ns#nil> } "
                + "WHERE { <http://x/C> <http://x/u> ?l . ?l <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/A> ; "
                + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> ?m . ?m <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://x/B> ; "
                + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> <http://www.w3.org/1999/02/22-rdf-syntax-ns#nil> }");
        assertEquals(0, size(G));
    }

    @Test
    void theScopedTextDoesNotCarryAServerFilePathAsBase() {
        String scoped = scope("INSERT DATA { <http://x/A> <http://x/p> <http://x/B> }");
        assertFalse(scoped.contains("BASE"), scoped);
        assertFalse(scoped.contains("file:"), scoped);
    }

    @Test
    void deleteWhereOnlyTouchesTheTargetGraph() {
        seed(G, "<http://x/A> <http://x/p> <http://x/B> . <http://x/A> <http://x/q> <http://x/C>");
        seed(OTHER, "<http://x/A> <http://x/p> <http://x/B>");
        apply("DELETE WHERE { <http://x/A> ?p ?o }");
        assertEquals(0, size(G));
        assertEquals(1, size(OTHER));
    }

    @Test
    void graphClauseNamingTheTargetIsAccepted() {
        apply("INSERT DATA { GRAPH <" + G + "> { <http://x/A> <http://x/p> <http://x/B> } }");
        assertEquals(1, size(G));
        seed(G, "<http://x/C> <http://x/p> <http://x/D>");
        apply("DELETE { GRAPH <" + G + "> { ?s <http://x/p> ?o } } WHERE { GRAPH <" + G + "> { ?s <http://x/p> ?o } }");
        assertEquals(0, size(G));
    }

    @Test
    void clearDropAndCreateOfTheTargetGraphAreAccepted() {
        seed(G, "<http://x/A> <http://x/p> <http://x/B>");
        apply("CLEAR GRAPH <" + G + ">");
        assertEquals(0, size(G));
        seed(G, "<http://x/A> <http://x/p> <http://x/B>");
        apply("DROP SILENT GRAPH <" + G + ">");
        assertEquals(0, size(G));
        apply("CREATE SILENT GRAPH <" + G + ">");
    }

    @Test
    void addMoveCopyBetweenAuthorizedGraphsWorkAndOthersAreRejected() {
        seed(G, "<http://x/A> <http://x/p> <http://x/B>");
        run(SparqlUpdateScoper.scope("ADD <" + G + "> TO <" + DRAFT + ">", G, List.of(DRAFT), null, SparqlUpdateLimits.defaults()));
        assertEquals(1, size(DRAFT));
        run(SparqlUpdateScoper.scope("MOVE <" + DRAFT + "> TO <" + G + ">", G, List.of(DRAFT), null, SparqlUpdateLimits.defaults()));
        assertEquals(0, size(DRAFT));
        run(SparqlUpdateScoper.scope("COPY <" + G + "> TO <" + DRAFT + ">", G, List.of(DRAFT), null, SparqlUpdateLimits.defaults()));
        assertEquals(1, size(DRAFT));
        rejected("ADD <" + G + "> TO <" + OTHER + ">");
        rejected("MOVE <" + OTHER + "> TO <" + G + ">");
        rejected("COPY <" + G + "> TO <" + OTHER + ">");
        rejected("ADD DEFAULT TO <" + G + ">");
    }

    @Test
    void draftTargetNeverTouchesThePublicGraph() {
        seed(G, "<http://x/A> <http://x/p> <http://x/B>");
        seed(DRAFT, "<http://x/A> <http://x/p> <http://x/B>");
        run(scopeTo(DRAFT, "DELETE { ?s <http://x/p> ?o } INSERT { ?s <http://x/p> <http://x/C> } WHERE { ?s <http://x/p> ?o }"));
        assertTrue(has(DRAFT, X + "A", X + "p", X + "C"));
        assertTrue(has(G, X + "A", X + "p", X + "B"));
        assertEquals(1, size(G));
        assertThrows(SparqlQueryRejectedException.class, () -> scopeTo(DRAFT, "INSERT DATA { GRAPH <" + G + "> { <http://x/A> <http://x/p> <http://x/Z> } }"));
    }

    @Test
    void anotherUsersDraftIsRejected() {
        String theirs = "http://ontocode.org/draft/p1/u2";
        assertThrows(SparqlQueryRejectedException.class, () -> scopeTo(DRAFT, "INSERT DATA { GRAPH <" + theirs + "> { <http://x/A> <http://x/p> <http://x/Z> } }"));
        assertThrows(SparqlQueryRejectedException.class, () -> scopeTo(DRAFT, "DELETE { ?s ?p ?o } WHERE { GRAPH <" + theirs + "> { ?s ?p ?o } }"));
    }

    @Test
    void partitionGraphsAreAuthorizedForTheProjectAndLoadedOnlyWhenNeeded() {
        int[] loads = {0};
        java.util.function.Supplier<java.util.Collection<String>> parts = () -> {
            loads[0]++;
            return List.of(PART);
        };
        seed(PART, "<http://x/A> <http://x/p> <http://x/B>");
        SparqlUpdateScoper.scope("INSERT DATA { <http://x/A> <http://x/p> <http://x/B> }", G, List.of(), parts, SparqlUpdateLimits.defaults());
        SparqlUpdateScoper.scope("INSERT { ?s <http://x/q> ?o } WHERE { ?s <http://x/p> ?o }", G, List.of(), parts, SparqlUpdateLimits.defaults());
        assertEquals(0, loads[0]);
        run(SparqlUpdateScoper.scope("INSERT { ?s <http://x/q> ?o } WHERE { GRAPH <" + PART + "> { ?s <http://x/p> ?o } }",
                G, List.of(), parts, SparqlUpdateLimits.defaults()));
        assertEquals(1, loads[0]);
        assertTrue(has(G, X + "A", X + "q", X + "B"));
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlUpdateScoper.scope(
                "INSERT DATA { GRAPH <http://ontocode.org/project/p1/ns/nope> { <http://x/A> <http://x/p> <http://x/B> } }",
                G, List.of(), parts, SparqlUpdateLimits.defaults()));
        assertThrows(SparqlQueryRejectedException.class, () -> SparqlUpdateScoper.scope(
                "INSERT DATA { GRAPH <" + PART + "> { <http://x/A> <http://x/p> <http://x/B> } }",
                G, List.of(), null, SparqlUpdateLimits.defaults()));
    }

    @Test
    void whereGraphVariableOnlySeesAuthorizedGraphs() {
        seed(G, "<http://x/A> <http://x/p> <http://x/B>");
        seed(OTHER, "<http://x/A> <http://x/p> <http://x/SECRET>");
        apply("INSERT { <http://x/seen> <http://x/o> ?o } WHERE { GRAPH ?g { <http://x/A> <http://x/p> ?o } }");
        assertTrue(has(G, X + "seen", X + "o", X + "B"));
        assertFalse(has(G, X + "seen", X + "o", X + "SECRET"));
    }

    @Test
    void rejectionMatrix() {
        assertEquals(SparqlUpdateScoper.MSG_DATASET,
                rejected("WITH <" + G + "> DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }").getMessage());
        assertEquals(SparqlUpdateScoper.MSG_DATASET,
                rejected("DELETE { ?s ?p ?o } USING <" + G + "> WHERE { ?s ?p ?o }").getMessage());
        assertEquals(SparqlUpdateScoper.MSG_DATASET,
                rejected("INSERT { ?s ?p ?o } USING NAMED <" + G + "> WHERE { GRAPH ?g { ?s ?p ?o } }").getMessage());
        assertTrue(rejected("INSERT DATA { GRAPH <" + OTHER + "> { <http://x/A> <http://x/p> <http://x/B> } }").getMessage().contains(OTHER));
        assertTrue(rejected("DELETE DATA { GRAPH <" + OTHER + "> { <http://x/A> <http://x/p> <http://x/B> } }").getMessage().contains(OTHER));
        assertTrue(rejected("DELETE WHERE { GRAPH <" + OTHER + "> { ?s ?p ?o } }").getMessage().contains(OTHER));
        assertTrue(rejected("INSERT { ?s ?p ?o } WHERE { GRAPH <" + OTHER + "> { ?s ?p ?o } }").getMessage().contains(OTHER));
        assertTrue(rejected("DELETE { GRAPH <" + OTHER + "> { ?s ?p ?o } } WHERE { ?s ?p ?o }").getMessage().contains(OTHER));
        assertEquals(SparqlUpdateScoper.MSG_VARIABLE_GRAPH,
                rejected("DELETE { GRAPH ?g { ?s ?p ?o } } WHERE { GRAPH ?g { ?s ?p ?o } }").getMessage());
        assertEquals(SparqlUpdateScoper.MSG_VARIABLE_GRAPH,
                rejected("DELETE WHERE { GRAPH ?g { ?s ?p ?o } }").getMessage());
        assertEquals(SparqlQueryGuard.MSG_SERVICE,
                rejected("INSERT { ?s ?p ?o } WHERE { SERVICE <http://evil/sparql> { ?s ?p ?o } }").getMessage());
        assertEquals(SparqlUpdateScoper.MSG_LOAD, rejected("LOAD <http://evil/data.ttl> INTO GRAPH <" + G + ">").getMessage());
        assertEquals(SparqlUpdateScoper.MSG_LOAD, rejected("LOAD SILENT <file:///etc/passwd>").getMessage());
        assertTrue(rejected("INSERT { ?s <http://x/p> ?v } WHERE { ?s ?p ?o BIND(<java:java.lang.Runtime.exec>(?o) AS ?v) }")
                .getMessage().contains("not supported"));
        assertTrue(rejected("INSERT { ?s <http://x/p> ?v } WHERE { ?s <http://jena.apache.org/text#query> ?v }")
                .getMessage().contains("property functions"));
        assertEquals(SparqlUpdateScoper.MSG_WHOLE_STORE, rejected("CLEAR ALL").getMessage());
        assertEquals(SparqlUpdateScoper.MSG_WHOLE_STORE, rejected("CLEAR DEFAULT").getMessage());
        assertEquals(SparqlUpdateScoper.MSG_WHOLE_STORE, rejected("DROP NAMED").getMessage());
        assertTrue(rejected("CLEAR GRAPH <" + OTHER + ">").getMessage().contains(OTHER));
        assertTrue(rejected("DROP GRAPH <" + OTHER + ">").getMessage().contains(OTHER));
        assertTrue(rejected("CREATE GRAPH <" + OTHER + ">").getMessage().contains(OTHER));
        assertTrue(rejected("INSERT DATA { <http://x/A> ").getMessage().startsWith("The update could not be parsed"));
    }

    @Test
    void nothingTouchesTheDefaultGraphWhenAnUpdateIsRejected() {
        seed(OTHER, "<http://x/A> <http://x/p> <http://x/B>");
        rejected("INSERT DATA { <http://x/A> <http://x/p> <http://x/B> } ; LOAD <http://evil/x>");
        assertEquals(0, defaultSize());
        assertEquals(0, size(G));
    }

    @Test
    void oversizedTextIsRefusedBeforeParsing() {
        SparqlUpdateLimits limits = new SparqlUpdateLimits(100, 0, 0);
        String junk = "x".repeat(101);
        SparqlUpdateTooLargeException e = assertThrows(SparqlUpdateTooLargeException.class,
                () -> SparqlUpdateScoper.scope(junk, G, List.of(), null, limits));
        assertTrue(e.getMessage().contains("100"));
        assertThrows(SparqlUpdateTooLargeException.class,
                () -> SparqlUpdateScoper.scope("é".repeat(60), G, List.of(), null, limits));
    }

    @Test
    void tooManyOperationsAndTooManyQuadsAreRefused() {
        SparqlUpdateLimits limits = new SparqlUpdateLimits(0, 3, 2);
        SparqlQueryRejectedException ops = assertThrows(SparqlQueryRejectedException.class, () -> SparqlUpdateScoper.scope(
                "CLEAR SILENT GRAPH <" + G + "> ; CLEAR SILENT GRAPH <" + G + "> ; CLEAR SILENT GRAPH <" + G + ">", G, List.of(), null, limits));
        assertTrue(ops.getMessage().contains("more than 2 operations"));
        SparqlQueryRejectedException quads = assertThrows(SparqlQueryRejectedException.class, () -> SparqlUpdateScoper.scope(
                "INSERT DATA { <http://x/A> <http://x/p> <http://x/1>, <http://x/2>, <http://x/3>, <http://x/4> }", G, List.of(), null, limits));
        assertTrue(quads.getMessage().contains("more than 3 statements"));
        SparqlUpdateScoper.scope("INSERT DATA { <http://x/A> <http://x/p> <http://x/1>, <http://x/2>, <http://x/3> }", G, List.of(), null, limits);
    }

    private OWLDataFactory df() {
        return OWLManager.getOWLDataFactory();
    }

    private Set<OWLAxiom> axioms() {
        OWLDataFactory df = df();
        OWLClass a = df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(X + "A"));
        OWLClass b = df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(X + "B"));
        OWLClass c = df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(X + "C"));
        OWLObjectProperty p = df.getOWLObjectProperty(org.semanticweb.owlapi.model.IRI.create(X + "p"));
        return Set.of(
                df.getOWLEquivalentClassesAxiom(a, df.getOWLObjectUnionOf(b, c)),
                df.getOWLSubClassOfAxiom(b, df.getOWLObjectSomeValuesFrom(p, c)),
                df.getOWLDisjointUnionAxiom(a, Set.of(b, c)));
    }

    @Test
    void owlAxiomWriterInsertDataAndDeleteWhereRoundTripThroughTheScoper() throws Exception {
        Set<OWLAxiom> axioms = axioms();
        String insert = OwlAxiomSparqlWriter.toInsertData(axioms);
        apply(insert);
        long inserted = size(G);
        assertTrue(inserted > 10, "axioms written: " + inserted);
        assertEquals(0, defaultSize());
        for (OWLAxiom axiom : axioms) {
            apply(OwlAxiomSparqlWriter.toDeleteWhere(Set.of(axiom)));
        }
        assertTrue(size(G) < inserted);
        long afterDelete = size(G);
        apply(insert);
        assertTrue(size(G) > afterDelete);
    }

    @Test
    void scopedTextKeepsTheSameTriplesAsTheOriginalInTheTargetGraph() throws Exception {
        String insert = OwlAxiomSparqlWriter.toInsertData(axioms());
        apply(insert);
        Model scopedResult = new LinkedHashModel();
        try (RepositoryConnection c = repo.getConnection()) {
            c.getStatements(null, null, null, false, vf.createIRI(G)).forEach(s -> scopedResult.add(s.getSubject(), s.getPredicate(), s.getObject()));
        }
        SailRepository plain = new SailRepository(new MemoryStore());
        plain.init();
        Model plainResult = new LinkedHashModel();
        try (RepositoryConnection c = plain.getConnection()) {
            c.prepareUpdate(insert).execute();
            c.getStatements(null, null, null, false).forEach(s -> plainResult.add(s.getSubject(), s.getPredicate(), s.getObject()));
        } finally {
            plain.shutDown();
        }
        assertTrue(org.eclipse.rdf4j.model.util.Models.isomorphic(scopedResult, plainResult));
    }
}
