package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftUpdateGraphInjectionTest {

    private static final String DRAFT = "http://example.org/draft/u1";

    private static String inject(String update) {
        SparqlDatasetService service = Mockito.mock(SparqlDatasetService.class, Mockito.CALLS_REAL_METHODS);
        return ReflectionTestUtils.invokeMethod(service, "injectGraphContext", update, DRAFT);
    }

    @Test
    void deleteInsertWhereKeepsBothTemplatesInTheDraftGraph() {
        String result = inject("DELETE { <http://x/O> a ?t } INSERT { <http://x/N> a ?t } WHERE { <http://x/O> a ?t }");
        assertTrue(result.startsWith("WITH <" + DRAFT + ">"), result);
        assertFalse(result.contains("USING"), result);
    }

    @Test
    void deleteWhereIsRewrittenSoItOnlyTouchesTheTargetGraph() {
        String result = inject("DELETE WHERE { <http://x/A> ?p ?o }");
        assertEquals("WITH <" + DRAFT + "> DELETE { <http://x/A> ?p ?o } WHERE { <http://x/A> ?p ?o }", result.trim());
    }

    @Test
    void insertOnlyWhereInAMultiStatementUpdateGoesToTheDraftGraph() {
        String result = inject("DELETE DATA { <http://x/A> <http://x/p> \"1\" } ; INSERT { <http://x/B> <http://x/p> ?o } WHERE { <http://x/A> <http://x/p> ?o }");
        assertTrue(result.contains("WITH <" + DRAFT + "> INSERT {"), result);
    }
}
