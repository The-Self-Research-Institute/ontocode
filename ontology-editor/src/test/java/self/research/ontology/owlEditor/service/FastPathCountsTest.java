package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FastPathCountsTest {

    private final OntologyMetrics metrics = mock(OntologyMetrics.class);

    @Test
    void aLargeWebOntologyKeepsItsSavedCountsAndStartsNoWholeGraphWork() {
        Map<String, Object> saved = Map.of("classCount", 85_169, "axiomCount", 1_045_620);

        var tasks = FastPathCounts.tasks(metrics, "p", saved, false, false);

        assertTrue(tasks.isEmpty());
        verifyNoInteractions(metrics);
    }

    @Test
    void aLargeOntologyIsRecognisedFromClassesOrTriplesWhenAxiomsAreMissing() {
        assertTrue(FastPathCounts.tasks(metrics, "p", Map.of("classCount", 60_000), false, false).isEmpty());
        assertTrue(FastPathCounts.tasks(metrics, "p", Map.of("tripleCount", 700_000L), false, false).isEmpty());
    }

    @Test
    void theTripleCountImportsSaveUnderCountsIsRecognised() {
        Map<String, Object> saved = Map.of("counts", Map.of("triples", 1_500_000L));

        assertTrue(FastPathCounts.tasks(metrics, "p", saved, false, false).isEmpty());
        verifyNoInteractions(metrics);
    }

    @Test
    void aGraphKnownToBeLargeOrStillUncountedStartsNoWholeGraphWork() {
        var tasks = FastPathCounts.tasks(metrics, "p", Map.of(), false, true);

        assertTrue(tasks.isEmpty());
        verifyNoInteractions(metrics);
    }

    @Test
    void aSmallWebOntologyStillRefreshesEveryCount() {
        var tasks = FastPathCounts.tasks(metrics, "p", Map.of("classCount", 120, "axiomCount", 900), false, false);

        assertEquals(4, tasks.size());
    }

    @Test
    void theDesktopLiveModelIsAlwaysCountedBecauseItIsCheap() {
        var tasks = FastPathCounts.tasks(metrics, "p", Map.of("axiomCount", 1_045_620), true, true);

        assertEquals(4, tasks.size());
    }
}
