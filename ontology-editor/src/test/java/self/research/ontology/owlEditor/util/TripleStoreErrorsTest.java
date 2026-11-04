package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripleStoreErrorsTest {

    @Test
    void fusekiTimeoutBodyIsRecognised() {
        assertTrue(TripleStoreErrors.isQueryTimeout(new QueryEvaluationException("Query timed out")));
    }

    @Test
    void wrappedTimeoutIsRecognised() {
        RuntimeException wrapped = new RuntimeException("SPARQL query execution failed",
                new java.net.SocketTimeoutException("Read timed out"));
        assertTrue(TripleStoreErrors.isQueryTimeout(wrapped));
    }

    @Test
    void otherFailuresAreNotTimeouts() {
        assertFalse(TripleStoreErrors.isQueryTimeout(new QueryEvaluationException("Malformed query")));
        assertFalse(TripleStoreErrors.isQueryTimeout(new RuntimeException()));
    }

    @Test
    void messageIsPlain() {
        assertTrue(TripleStoreErrors.QUERY_TIMEOUT_MESSAGE.contains("LIMIT"));
    }
}
