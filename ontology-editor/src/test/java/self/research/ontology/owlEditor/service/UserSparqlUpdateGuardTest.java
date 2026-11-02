package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSparqlUpdateGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP ALL",
            "DROP GRAPH <urn:other>",
            "CLEAR GRAPH <urn:other>",
            "CLEAR ALL",
            "LOAD <http://internal.example/data.ttl>",
            "COPY <urn:a> TO <urn:b>",
            "MOVE <urn:a> TO <urn:b>",
            "ADD <urn:a> TO <urn:b>",
            "CREATE GRAPH <urn:new>",
            "INSERT DATA { <http://ex/a> <http://ex/b> <http://ex/c> } ; DROP ALL",
            "INSERT DATA { GRAPH <urn:victim> { <http://ex/a> <http://ex/b> <http://ex/c> } }",
            "DELETE DATA { GRAPH <urn:victim> { <http://ex/a> <http://ex/b> <http://ex/c> } }",
            "DELETE { GRAPH <urn:victim> { ?s ?p ?o } } WHERE { GRAPH <urn:victim> { ?s ?p ?o } }",
            "WITH <urn:victim> DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }",
            "INSERT { ?s ?p ?o } USING <urn:victim> WHERE { ?s ?p ?o }",
            "INSERT { ?s ?p ?o } USING NAMED <urn:victim> WHERE { ?s ?p ?o }",
            "INSERT { ?s ?p ?o } WHERE { SERVICE <http://internal.example/sparql> { ?s ?p ?o } }",
            "INSERT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }",
            "not sparql at all",
            ""
    })
    void refusesAnythingThatCanTouchMoreThanTheProjectGraph(String update) {
        assertFalse(UserSparqlUpdateGuard.check(update).allowed(), update);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT DATA { <http://ex/a> <http://ex/b> \"x\" }",
            "DELETE DATA { <http://ex/a> <http://ex/b> \"x\" }",
            "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }",
            "INSERT { ?s <http://ex/b> \"x\" } WHERE { ?s a <http://ex/C> }",
            "DELETE WHERE { <http://ex/a> ?p ?o }",
            "PREFIX ex: <http://ex/> INSERT DATA { ex:a ex:b ex:c }",
            "INSERT DATA { <http://ex/a> <http://ex/b> \"text with GRAPH and DROP ALL inside\" }",
            "# DROP ALL in a comment\nINSERT DATA { <http://ex/a> <http://ex/b> <http://ex/c> }",
            "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o } ; INSERT DATA { <http://ex/a> <http://ex/b> <http://ex/c> }"
    })
    void allowsPlainEditsOfTheProjectGraph(String update) {
        assertTrue(UserSparqlUpdateGuard.check(update).allowed(), update);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM NAMED <urn:victim> WHERE { GRAPH ?g { ?s ?p ?o } }",
            "SELECT * { GRAPH ?g { ?s ?p ?o } }",
            "SELECT * FROM <urn:victim> WHERE { ?s ?p ?o }",
            "SELECT * WHERE { SERVICE <http://internal.example/sparql> { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <urn:victim> { ?s ?p ?o } }",
            "garbage"
    })
    void refusesQueriesThatReachOtherGraphs(String query) {
        assertFalse(UserSparqlUpdateGuard.checkQuery(query).allowed(), query);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * WHERE { ?s ?p ?o } LIMIT 10",
            "SELECT ?c WHERE { ?c a <http://www.w3.org/2002/07/owl#Class> }",
            "SELECT * { ?s ?p ?o }",
            "SELECT * WHERE { ?s <http://ex/p>* ?o }"
    })
    void allowsQueriesOnTheProjectGraph(String query) {
        assertTrue(UserSparqlUpdateGuard.checkQuery(query).allowed(), query);
    }

    @Test
    void refusesVeryLongUpdates() {
        String big = "INSERT DATA { <http://ex/a> <http://ex/b> \"" + "x".repeat(2_000_000) + "\" }";

        assertFalse(UserSparqlUpdateGuard.check(big).allowed());
    }
}
