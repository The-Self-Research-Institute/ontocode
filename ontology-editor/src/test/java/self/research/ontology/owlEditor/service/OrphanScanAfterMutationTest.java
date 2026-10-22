package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.repository.TopLevelClassCacheRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OrphanScanAfterMutationTest {

    private static final long SLOW_FIRST_SCAN = 15_000;

    private final TopLevelClassCacheService cache = new TopLevelClassCacheService(mock(TopLevelClassCacheRepository.class));

    @Test
    void slowFirstScanStillSkipsWhenNothingChanged() {
        assertFalse(cache.needsOrphanScan("p1"));
        assertTrue(OntologyQueryService.canSkipOrphanScan(SLOW_FIRST_SCAN, false, cache.needsOrphanScan("p1")));
    }

    @Test
    void slowFirstScanNeverSkipsAfterAMutation() {
        cache.evict("p1");

        assertTrue(cache.needsOrphanScan("p1"));
        assertFalse(OntologyQueryService.canSkipOrphanScan(SLOW_FIRST_SCAN, false, cache.needsOrphanScan("p1")));
    }

    @Test
    void aFinishedScanAllowsSkippingAgain() {
        cache.evict("p1");

        cache.orphanScanFinished("p1", cache.orphanScanStarted("p1"));

        assertFalse(cache.needsOrphanScan("p1"));
    }

    @Test
    void aScanThatNeverFinishesKeepsForcingTheNextScan() {
        cache.evict("p1");

        cache.orphanScanStarted("p1");

        assertTrue(cache.needsOrphanScan("p1"));
    }

    @Test
    void aMutationDuringTheScanForcesTheNextScan() {
        cache.evict("p1");
        Long started = cache.orphanScanStarted("p1");

        cache.evict("p1");
        cache.orphanScanFinished("p1", started);

        assertTrue(cache.needsOrphanScan("p1"));
    }

    @Test
    void draftReaderAlwaysScans() {
        assertFalse(OntologyQueryService.canSkipOrphanScan(SLOW_FIRST_SCAN, false, true));
    }

    @Test
    void emptyFirstScanAlwaysScans() {
        assertFalse(OntologyQueryService.canSkipOrphanScan(SLOW_FIRST_SCAN, true, false));
    }
}
