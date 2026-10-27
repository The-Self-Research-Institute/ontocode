package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlTextTest {

    @Test
    void theGraphGoesOnTheOuterAskNotOnANestedSelect() {
        String from = "FROM <urn:g>";
        String nested = "ASK { { SELECT (COUNT(*) AS ?n) WHERE { SELECT DISTINCT ?s ?p ?o WHERE { ?s ?p ?o } } } FILTER(?n > 2) }";

        String scoped = SparqlText.withAskDataset(nested, from);

        assertEquals("ASK FROM <urn:g> WHERE { { SELECT (COUNT(*) AS ?n) WHERE { SELECT DISTINCT ?s ?p ?o WHERE { ?s ?p ?o } } } FILTER(?n > 2) }", scoped);
        assertEquals("ASK FROM <urn:g> WHERE { ?s ?p ?o }", SparqlText.withAskDataset("ASK WHERE { ?s ?p ?o }", from));
        assertEquals("PREFIX a: <urn:a#> ASK FROM <urn:g> WHERE { ?s ?p ?o }", SparqlText.withAskDataset("PREFIX a: <urn:a#> ASK { ?s ?p ?o }", from));
    }

    @Test
    void anAddressContainingFromIsNotAFromClause() {
        assertFalse(SparqlText.hasFromClause("ASK { <urn:Pizza_from_Italy> ?p ?o }"));
        assertTrue(SparqlText.hasFromClause("ASK FROM <urn:g> { ?s ?p ?o }"));
    }

    @Test
    void findsAKeywordInTheQueryItself() {
        assertTrue(SparqlText.hasKeyword("SELECT ?s WHERE { ?s ?p ?o }", "WHERE"));
        assertTrue(SparqlText.hasKeyword("select ?s where { ?s ?p ?o }", "WHERE"));
        assertTrue(SparqlText.hasKeyword("INSERT { ?s ?p ?o } USING <urn:g> WHERE { }", "USING"));
        assertTrue(SparqlText.hasKeyword("INSERT { ?s ?p ?o } USING<urn:g> WHERE { }", "USING"));
    }

    @Test
    void ignoresAKeywordInsideTextAddressesAndComments() {
        assertFalse(SparqlText.hasKeyword("INSERT DATA { <urn:a> <urn:p> \"measured using a probe\" }", "USING"));
        assertFalse(SparqlText.hasKeyword("INSERT DATA { <urn:a> <urn:p> 'using a probe' }", "USING"));
        assertFalse(SparqlText.hasKeyword("INSERT DATA { <urn:a> <urn:p> \"\"\"line\nusing x\"\"\" }", "USING"));
        assertFalse(SparqlText.hasKeyword("INSERT DATA { <http://example.org/USING/x> <urn:p> 1 }", "USING"));
        assertFalse(SparqlText.hasKeyword("SELECT ?s WHERE { ?s ?p ?o } # using later\n", "USING"));
        assertFalse(SparqlText.hasKeyword("SELECT ?s WHERE { ?s ?p \"say \\\"using\\\" now\" }", "USING"));
    }

    @Test
    void ignoresWordsThatOnlyContainTheKeyword() {
        assertFalse(SparqlText.hasKeyword("SELECT ?somewhere { ?s owl:someValuesFrom ?o }", "WHERE"));
        assertFalse(SparqlText.hasKeyword("SELECT ?s { ?s owl:someValuesFrom ?o }", "FROM"));
        assertFalse(SparqlText.hasKeyword("SELECT ?from { ?s ?p ?o }", "FROM"));
        assertFalse(SparqlText.hasKeyword("SELECT ?s { ?s ex:from_x ?o }", "FROM"));
    }

    @Test
    void aComparisonOperatorIsNotTreatedAsAnAddress() {
        assertTrue(SparqlText.hasKeyword("SELECT ?s { ?s ?p ?o FILTER(?o < 5) } LIMIT 1", "LIMIT"));
        assertTrue(SparqlText.hasKeyword("SELECT ?s { ?s ?p ?o FILTER(?o < 5) } USING <urn:g>", "USING"));
    }

    @Test
    void recognisesAFromGraphClauseOnly() {
        assertTrue(SparqlText.hasFromClause("SELECT ?s FROM <urn:g> WHERE { ?s ?p ?o }"));
        assertTrue(SparqlText.hasFromClause("SELECT ?s from   <urn:g> WHERE { ?s ?p ?o }"));
        assertFalse(SparqlText.hasFromClause("SELECT ?s WHERE { ?s owl:someValuesFrom <urn:x> }"));
        assertFalse(SparqlText.hasFromClause("SELECT ?s WHERE { ?s ?p \"data from <b>\" }"));
        assertFalse(SparqlText.hasFromClause("SELECT ?s FROM NAMED <urn:g> WHERE { ?s ?p ?o }"));
    }

    @Test
    void replacesTheRealWhereAndNotOneInsideANamespaceOrText() {
        String query = "PREFIX n: <http://example.org/nowhere#>\nSELECT ?s WHERE { ?s n:p \"where\" }";
        String result = SparqlText.replaceFirstKeyword(query, "WHERE", "FROM <urn:g> WHERE");
        assertEquals("PREFIX n: <http://example.org/nowhere#>\nSELECT ?s FROM <urn:g> WHERE { ?s n:p \"where\" }", result);
    }

    @Test
    void leavesTheQueryAloneWhenTheKeywordIsMissing() {
        assertEquals("ASK { ?s ?p ?o }", SparqlText.replaceFirstKeyword("ASK { ?s ?p ?o }", "WHERE", "X"));
    }

    @Test
    void removesFromGraphClausesButNotTextThatLooksLikeOne() {
        String query = "SELECT ?s FROM <urn:a> FROM <urn:b> WHERE { ?s ?p \"data FROM <urn:c>\" }";
        assertEquals("SELECT ?s     WHERE { ?s ?p \"data FROM <urn:c>\" }", SparqlText.removeFromClauses(query));
    }

    @Test
    void findsTheOpeningBraceOfTheBodyAfterAKeyword() {
        String query = "ASK # { not this\n { ?s ?p \"{\" }";
        assertEquals(query.indexOf("{ ?s"), SparqlText.indexOfOpenBrace(query, query.indexOf("ASK")));
    }

    @Test
    void splitsOnTopLevelSemicolonsOnly() {
        String update = "DELETE DATA { <urn:a> <urn:p> \"x;y\" } ; INSERT DATA { <urn:a> <urn:p> \"{;\" } ; CLEAR GRAPH <urn:g>";
        List<String> parts = SparqlText.splitStatements(update);
        assertEquals(3, parts.size());
        assertEquals("DELETE DATA { <urn:a> <urn:p> \"x;y\" }", parts.get(0));
        assertEquals("INSERT DATA { <urn:a> <urn:p> \"{;\" }", parts.get(1));
        assertEquals("CLEAR GRAPH <urn:g>", parts.get(2));
    }

    @Test
    void handlesAVeryLongUpdateWithoutRecursion() {
        StringBuilder body = new StringBuilder("INSERT DATA { ");
        for (int i = 0; i < 20_000; i++) {
            body.append("<urn:s").append(i).append("> <urn:p> \"value ").append(i).append(" using text\" . ");
        }
        body.append("}");
        assertFalse(SparqlText.hasKeyword(body.toString(), "USING"));
        assertEquals(1, SparqlText.splitStatements(body.toString()).size());
    }
}
