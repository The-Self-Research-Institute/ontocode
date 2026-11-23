package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphPresenceTest {

    private final RepositoryConnection conn = mock(RepositoryConnection.class);

    private void graphHasData(String graph, boolean value) {
        BooleanQuery ask = mock(BooleanQuery.class);
        when(ask.evaluate()).thenReturn(value);
        when(conn.prepareBooleanQuery("ASK FROM <" + graph + "> { ?s ?p ?o }")).thenReturn(ask);
    }

    @Test
    void itStopsAtTheFirstGraphThatHasATripleAndNeverCountsTheGraph() {
        graphHasData("g1", true);
        graphHasData("g2", true);

        assertTrue(GraphPresence.any(conn, List.of("g1", "g2")));

        verify(conn, never()).prepareBooleanQuery("ASK FROM <g2> { ?s ?p ?o }");
        verify(conn, never()).prepareTupleQuery(anyString());
    }

    @Test
    void itReportsEmptyOnlyWhenEveryGraphIsEmpty() {
        graphHasData("g1", false);
        graphHasData("g2", false);

        assertFalse(GraphPresence.any(conn, List.of("g1", "g2")));
        assertFalse(GraphPresence.any(conn, List.of()));
    }
}
