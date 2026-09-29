package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteListAxiomsSparqlTest {

    private static final String PREFIXES = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX : <http://example.org/>
            """;

    private static final String DATA = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix : <http://example.org/> .
            :A a owl:Class . :B a owl:Class . :C a owl:Class . :D a owl:Class . :F a owl:Class . :X a owl:Class .
            [] a owl:AllDisjointClasses ; owl:members ( :A :B :C ) .
            [] a owl:AllDisjointClasses ; owl:members ( :C :D ) .
            :X owl:disjointUnionOf ( :B :F ) .
            """;

    private SailRepository repo;
    private RepositoryConnection conn;

    @BeforeEach
    void load() throws Exception {
        repo = new SailRepository(new MemoryStore());
        conn = repo.getConnection();
        conn.add(new StringReader(DATA), "", RDFFormat.TURTLE);
    }

    @AfterEach
    void close() {
        conn.close();
        repo.shutDown();
    }

    private boolean ask(String pattern) {
        BooleanQuery q = conn.prepareBooleanQuery(QueryLanguage.SPARQL, PREFIXES + "ASK { " + pattern + " }");
        return q.evaluate();
    }

    private void deleteClassB() {
        String iri = "http://example.org/B";
        conn.prepareUpdate(QueryLanguage.SPARQL, PREFIXES + OntologyMutationService.buildDeleteListAxiomsSparql(iri) + ";\n"
                + "DELETE { <" + iri + "> ?p ?o } WHERE { <" + iri + "> ?p ?o };\n"
                + "DELETE { ?s ?p <" + iri + "> } WHERE { ?s ?p <" + iri + "> }").execute();
    }

    @Test
    void deletingAClassLeavesNoBrokenListBehind() {
        deleteClassB();
        assertFalse(ask("?cell rdf:rest ?r . FILTER NOT EXISTS { ?cell rdf:first ?f }"));
        assertFalse(ask("?ax owl:members ?l . FILTER NOT EXISTS { ?l rdf:first ?f }"));
    }

    @Test
    void theWholeDisjointAxiomNamingTheClassIsRemovedButOthersStay() {
        deleteClassB();
        assertFalse(ask("?ax owl:members ?l . ?l rdf:first :A"));
        assertTrue(ask("?ax a owl:AllDisjointClasses ; owl:members ?l . ?l rdf:first :C . ?l rdf:rest ?r . ?r rdf:first :D"));
        assertFalse(ask(":X owl:disjointUnionOf ?l"));
        assertTrue(ask(":A a owl:Class . :C a owl:Class . :F a owl:Class"));
    }

    @Test
    void classesInNoListAreUnaffected() {
        String before = String.valueOf(conn.size());
        conn.prepareUpdate(QueryLanguage.SPARQL,
                PREFIXES + OntologyMutationService.buildDeleteListAxiomsSparql("http://example.org/X")).execute();
        assertTrue(before.equals(String.valueOf(conn.size())));
    }
}
