package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopLevelClassSparqlTest {

    private static final String PREFIXES = """
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            """;
    private static final String NS = "http://example.org/o#";

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

    private void update(String body) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(QueryLanguage.SPARQL, PREFIXES + "INSERT DATA { " + body + " }").execute();
        }
    }

    private List<String> select(String query) {
        List<String> found = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection()) {
            TupleQueryResult rs = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate();
            while (rs.hasNext()) {
                found.add(rs.next().getValue("c").stringValue().substring(NS.length()));
            }
        }
        return found;
    }

    private void draftScenario() {
        update("<" + NS + "Test3> a owl:Class ; rdfs:subClassOf owl:Thing . "
                + "<" + NS + "test4> a owl:Class ; rdfs:subClassOf owl:Thing . "
                + "<" + NS + "Test3> rdfs:subClassOf <" + NS + "test4> .");
    }

    @Test
    void theOldQueryListedAClassWithANamedParentAtTheTopLevel() {
        draftScenario();
        String old = PREFIXES + "SELECT ?c WHERE { ?c rdfs:subClassOf <http://www.w3.org/2002/07/owl#Thing> . FILTER(isIRI(?c)) } ORDER BY ?c";
        assertEquals(List.of("Test3", "test4"), select(old));
    }

    @Test
    void aClassWithANamedParentIsNotATopLevelClass() {
        draftScenario();
        assertEquals(List.of("test4"), select(TopLevelClassSparql.explicitRoots(PREFIXES, 100)));
    }

    @Test
    void aClassWhoseOnlyParentIsThingStaysATopLevelClass() {
        update("<" + NS + "A> a owl:Class ; rdfs:subClassOf owl:Thing . <" + NS + "B> a owl:Class ; rdfs:subClassOf owl:Thing .");
        assertEquals(List.of("A", "B"), select(TopLevelClassSparql.explicitRoots(PREFIXES, 100)));
    }

    @Test
    void aClassWithAnAnonymousParentAndThingStaysTopLevel() {
        update("<" + NS + "R> a owl:Class ; rdfs:subClassOf owl:Thing ; rdfs:subClassOf [ a owl:Restriction ] .");
        assertEquals(List.of("R"), select(TopLevelClassSparql.explicitRoots(PREFIXES, 100)));
    }

    @Test
    void theCountAgreesWithTheList() {
        draftScenario();
        update("<" + NS + "C> a owl:Class ; rdfs:subClassOf owl:Thing .");
        try (RepositoryConnection conn = repo.getConnection()) {
            try (TupleQueryResult rs = conn.prepareTupleQuery(QueryLanguage.SPARQL, TopLevelClassSparql.explicitRootCount(PREFIXES)).evaluate()) {
                assertEquals("2", rs.next().getValue("count").stringValue());
            }
        }
        assertEquals(2, select(TopLevelClassSparql.explicitRoots(PREFIXES, 100)).size());
    }

    @Test
    void limitIsAtLeastOne() {
        update("<" + NS + "A> rdfs:subClassOf owl:Thing . <" + NS + "B> rdfs:subClassOf owl:Thing .");
        assertEquals(1, select(TopLevelClassSparql.explicitRoots(PREFIXES, 0)).size());
    }
}
