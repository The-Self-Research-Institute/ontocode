package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphStatementOpsScopeTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI MINE = VF.createIRI("urn:graph:mine");
    private static final IRI OTHER = VF.createIRI("urn:graph:other");
    private static final IRI P = VF.createIRI("http://x/p");

    private SailRepository repo;
    private RepositoryConnection conn;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        conn = repo.getConnection();
        conn.add(VF.createIRI("http://x/mine"), P, VF.createLiteral("m"), MINE);
        conn.add(VF.createIRI("http://x/other"), P, VF.createLiteral("o"), OTHER);
    }

    @AfterEach
    void tearDown() {
        conn.close();
        repo.shutDown();
    }

    private int rows(String query, List<String> scope) {
        return GraphStatementOps.selectCapped(conn, query, scope, 5, 100, 100_000).rows().size();
    }

    @Test
    void queriesSeeOnlyTheScopedGraph() {
        assertEquals(1, rows("SELECT ?s WHERE { ?s ?p ?o }", List.of(MINE.stringValue())));
    }

    @Test
    void theDatasetAloneDoesNotStopGraphKeywordsSoTheGuardMustRejectThem() {
        String escape = "SELECT ?s FROM NAMED <urn:graph:other> WHERE { GRAPH <urn:graph:other> { ?s ?p ?o } }";
        assertEquals(1, rows(escape, List.of(MINE.stringValue())));
        assertEquals("SCOPE_NOT_ALLOWED", AssistantSparqlGuard.check(escape, 20_000).errorCode());
    }

    @Test
    void aQueryWithoutWhereKeywordIsStillScoped() {
        assertEquals(1, rows("SELECT ?s { ?s ?p ?o }", List.of(MINE.stringValue())));
    }
}
