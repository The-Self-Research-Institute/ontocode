package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantSparqlGuardTest {

    private static final int MAX = 20_000;

    private static String codeOf(String query) {
        return AssistantSparqlGuard.check(query, MAX).errorCode();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT ?s WHERE { ?s ?p ?o } LIMIT 5",
            "PREFIX owl: <http://www.w3.org/2002/07/owl#> SELECT ?c WHERE { ?c a owl:Class }",
            "SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }",
            "SELECT ?s { ?s <http://x/p>* ?o }",
            "SELECT ?s WHERE { ?s ?p ?o FILTER EXISTS { ?o a ?t } }",
            "SELECT ?s WHERE { { SELECT ?s WHERE { ?s ?p ?o } LIMIT 3 } }"
    })
    void plainSelectsAreAllowed(String query) {
        assertTrue(AssistantSparqlGuard.check(query, MAX).allowed(), query);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM NAMED <urn:other> WHERE { GRAPH <urn:other> { ?s ?p ?o } }",
            "SELECT * FROM <urn:other> WHERE { ?s ?p ?o }",
            "SELECT * WHERE { GRAPH ?g { ?s ?p ?o } }",
            "SELECT * WHERE { GRAPH <urn:other> { ?s <http://x/p>* ?o } }",
            "SELECT * WHERE { SERVICE <http://fuseki:3030/ds/sparql> { ?s ?p ?o } }",
            "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { GRAPH <urn:other> { ?s ?p ?o } } }",
            "SELECT * WHERE { { SELECT * WHERE { GRAPH ?g { ?s ?p ?o } } } }"
    })
    void anythingThatLeavesTheProjectGraphIsRejected(String query) {
        assertEquals("SCOPE_NOT_ALLOWED", codeOf(query), query);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ASK { ?s ?p ?o }",
            "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }",
            "DESCRIBE <http://x/a>",
            "INSERT DATA { <http://x/a> <http://x/p> 1 }",
            "DROP GRAPH <urn:other>",
            ""
    })
    void nonSelectOrMalformedIsRejected(String query) {
        assertEquals("NOT_SELECT_ONLY", codeOf(query), query);
    }

    @Test
    void syntaxErrorsReportTheParserReasonSoTheModelCanFixTheQuery() {
        AssistantSparqlGuard.Verdict verdict = AssistantSparqlGuard.check("SELECT ?s WHERE { ?s ex:p ?o }", MAX);
        assertEquals("MALFORMED_QUERY", verdict.errorCode());
        assertTrue(verdict.message().startsWith("The query could not be parsed: "), verdict.message());
        assertEquals("MALFORMED_QUERY", codeOf("not sparql at all"));
    }

    @Test
    void overlongQueriesAreRejectedBeforeParsing() {
        assertEquals("QUERY_TOO_LONG", AssistantSparqlGuard.check("SELECT ?s WHERE { ?s ?p ?o }" + " ".repeat(MAX), MAX).errorCode());
    }

    @Test
    void whitespaceHeavyInputCannotStallTheGuard() {
        String hostile = "PREFIX" + " ".repeat(16_000) + "x";
        assertTimeout(Duration.ofSeconds(2), () -> codeOf(hostile));
    }
}
